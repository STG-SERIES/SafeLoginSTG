package com.safeloginstg.auth;

import com.safeloginstg.SafeLoginSTG;
import com.safeloginstg.dialog.AuthDialogs;
import com.safeloginstg.mojang.MojangPremiumChecker;
import com.safeloginstg.mojang.MojangPremiumChecker.PremiumLookup;
import com.safeloginstg.storage.YamlAuthRepository;
import com.safeloginstg.storage.YamlAuthRepository.AccountRecord;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class AuthService {

    private final SafeLoginSTG plugin;
    private final YamlAuthRepository repository;
    private final PasswordHasher hasher;
    private final MojangPremiumChecker premiumChecker;
    private final AuthDialogs dialogs;
    private final ClientTokenService tokens;

    private final Set<UUID> authenticated = ConcurrentHashMap.newKeySet();
    private final Map<UUID, AuthState> states = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitTask> timeoutTasks = new ConcurrentHashMap<>();
    /** Pending join context until register/login completes. */
    private final Map<UUID, JoinContext> joinContexts = new ConcurrentHashMap<>();

    public AuthService(
            SafeLoginSTG plugin,
            YamlAuthRepository repository,
            PasswordHasher hasher,
            MojangPremiumChecker premiumChecker,
            AuthDialogs dialogs,
            ClientTokenService tokens
    ) {
        this.plugin = plugin;
        this.repository = repository;
        this.hasher = hasher;
        this.premiumChecker = premiumChecker;
        this.dialogs = dialogs;
        this.tokens = tokens;
    }

    public void handleJoin(Player player) {
        UUID id = player.getUniqueId();
        authenticated.remove(id);
        joinContexts.remove(id);

        if (player.hasPermission("safeloginstg.bypass")) {
            markAuthenticated(player, Component.text("Bypass permission — logged in.", NamedTextColor.GREEN));
            return;
        }

        states.put(id, AuthState.CHECKING);
        scheduleTimeout(player);

        boolean serverOnlineMode = Bukkit.getOnlineMode();
        tokens.readToken(player).whenComplete((cookieToken, error) -> {
            String safeCookie = (error == null) ? cookieToken : null;

            if (serverOnlineMode) {
                Bukkit.getScheduler().runTask(plugin, () -> continueJoin(player, true, safeCookie));
                return;
            }

            premiumChecker.lookup(player.getName()).thenAccept(lookup ->
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        boolean premiumName = resolvePremiumName(player.getName(), lookup);
                        continueJoin(player, premiumName, safeCookie);
                    })
            );
        });
    }

    /**
     * Prefer a definitive Mojang answer; fall back to the sticky flag saved on the account
     * when Mojang is rate-limited or unreachable.
     */
    private boolean resolvePremiumName(String playerName, PremiumLookup lookup) {
        if (lookup.definitive()) {
            return lookup.premium();
        }
        return repository.findAccount(playerName)
                .map(account -> account.mojangPremium() || account.lastAuthMode() == AuthMode.PREMIUM)
                .orElse(false);
    }

    private void continueJoin(Player player, boolean premiumName, String cookieToken) {
        if (!player.isOnline() || isAuthenticated(player)) {
            return;
        }

        AccountRecord account = repository.findAccount(player.getName()).orElse(null);
        boolean trustedPremium = account != null
                && ClientTokenService.matches(cookieToken, account.premiumToken());
        boolean trustedCracked = account != null
                && ClientTokenService.matches(cookieToken, account.crackedToken());

        JoinContext context = new JoinContext(premiumName, cookieToken, trustedPremium, trustedCracked);
        joinContexts.put(player.getUniqueId(), context);

        if (account == null) {
            states.put(player.getUniqueId(), AuthState.NEED_REGISTER);
            dialogs.showRegister(player, null);
            return;
        }

        // Heal accounts wrongly demoted to CRACKED when cookies were missing (pre-fix bug).
        if (premiumName
                && account.lastAuthMode() == AuthMode.CRACKED
                && !trustedCracked
                && !ClientTokenService.hasCookie(cookieToken)) {
            String healedPremium = account.premiumToken() != null
                    ? account.premiumToken()
                    : ClientTokenService.newToken();
            repository.updateSession(
                    player.getName(),
                    AuthMode.PREMIUM,
                    healedPremium,
                    account.crackedToken(),
                    true
            );
            account = repository.findAccount(player.getName()).orElse(account);
            tokens.writeToken(player, healedPremium);
        }

        /*
         * Premium auto-login:
         * - Mojang premium (or sticky mojang-premium) username
         * - Account not currently marked CRACKED (cracked client used this name)
         * - Do NOT require a matching cookie — cookies are unreliable and were demoting
         *   premium players to CRACKED, forcing a password every join.
         *
         * Only force a password for a premium name when:
         * - last mode is CRACKED (needs one reclaim login), or
         * - client cookie positively matches the cracked token.
         */
        if (premiumName && account.lastAuthMode() != AuthMode.CRACKED && !trustedCracked) {
            markAuthenticated(player, Component.text(
                    "Premium account — logged in automatically.",
                    NamedTextColor.GREEN
            ));
            // Refresh premium cookie in the background when possible.
            if (account.premiumToken() != null) {
                tokens.writeToken(player, account.premiumToken());
            }
            return;
        }

        if (premiumName && trustedCracked) {
            // Known cracked launcher cookie on a premium username → password every time.
            states.put(player.getUniqueId(), AuthState.NEED_LOGIN);
            dialogs.showLogin(player, null);
            return;
        }

        if (premiumName && account.lastAuthMode() == AuthMode.CRACKED) {
            // Premium client reclaiming after a cracked session — password once.
            states.put(player.getUniqueId(), AuthState.NEED_LOGIN);
            dialogs.showLogin(player, null);
            return;
        }

        // Cracked-only usernames: always login.
        states.put(player.getUniqueId(), AuthState.NEED_LOGIN);
        dialogs.showLogin(player, null);
    }

    public void handleQuit(Player player) {
        UUID id = player.getUniqueId();
        authenticated.remove(id);
        states.remove(id);
        joinContexts.remove(id);
        cancelTask(timeoutTasks, id);
    }

    public boolean isAuthenticated(Player player) {
        return authenticated.contains(player.getUniqueId());
    }

    public AuthState getState(Player player) {
        return states.getOrDefault(player.getUniqueId(), AuthState.CHECKING);
    }

    public void handleRegister(Player player, String password, String confirmation) {
        if (isAuthenticated(player) || getState(player) != AuthState.NEED_REGISTER) {
            return;
        }

        int min = plugin.getConfig().getInt("min-password-length", 4);
        int max = plugin.getConfig().getInt("max-password-length", 64);

        if (password.length() < min) {
            dialogs.showRegister(player, "Password must be at least " + min + " characters.");
            return;
        }
        if (password.length() > max) {
            dialogs.showRegister(player, "Password must be at most " + max + " characters.");
            return;
        }
        if (!password.equals(confirmation)) {
            dialogs.showRegister(player, "Passwords do not match.");
            return;
        }

        String hash = hasher.hash(password);
        repository.upsertPassword(player.getName(), player.getUniqueId(), hash);

        JoinContext context = joinContexts.getOrDefault(
                player.getUniqueId(),
                new JoinContext(false, null, false, false)
        );
        finalizeAuth(player, context, true);
        markAuthenticated(player, Component.text("Account created — you are now logged in.", NamedTextColor.GREEN));
    }

    public void handleLogin(Player player, String password) {
        if (isAuthenticated(player) || getState(player) != AuthState.NEED_LOGIN) {
            return;
        }

        var accountOpt = repository.findAccount(player.getName());
        if (accountOpt.isEmpty()) {
            states.put(player.getUniqueId(), AuthState.NEED_REGISTER);
            dialogs.showRegister(player, "No account found. Please create a new password.");
            return;
        }

        AccountRecord account = accountOpt.get();
        if (!hasher.verify(password, account.passwordHash())) {
            dialogs.showLogin(player, "Incorrect password.");
            return;
        }

        JoinContext context = joinContexts.getOrDefault(
                player.getUniqueId(),
                new JoinContext(false, null, false, false)
        );
        context = new JoinContext(
                context.premiumName(),
                context.cookieToken(),
                ClientTokenService.matches(context.cookieToken(), account.premiumToken()),
                ClientTokenService.matches(context.cookieToken(), account.crackedToken())
        );
        joinContexts.put(player.getUniqueId(), context);

        finalizeAuth(player, context, false);
        markAuthenticated(player, Component.text("Logged in successfully.", NamedTextColor.GREEN));
    }

    /**
     * Updates last-auth-mode and client cookies after a successful password auth.
     *
     * <p>Important: a missing cookie must NOT demote a Mojang premium username to
     * {@link AuthMode#CRACKED}. That was causing premium players to enter a password
     * on every join.</p>
     */
    private void finalizeAuth(Player player, JoinContext context, boolean firstRegister) {
        AccountRecord existing = repository.findAccount(player.getName()).orElse(null);
        String premiumToken = existing == null ? null : existing.premiumToken();
        String crackedToken = existing == null ? null : existing.crackedToken();

        AuthMode newMode;
        String newPremium = premiumToken;
        String newCracked = crackedToken;
        String cookieToStore;
        boolean mojangPremium = context.premiumName()
                || (existing != null && existing.mojangPremium());

        if (!context.premiumName()) {
            // Non-premium usernames are always cracked sessions.
            newMode = AuthMode.CRACKED;
            newCracked = ClientTokenService.newToken();
            cookieToStore = newCracked;
            mojangPremium = false;
        } else if (context.trustedCracked() && !firstRegister) {
            // Positive cracked-client cookie on a premium username → stay cracked.
            newMode = AuthMode.CRACKED;
            newCracked = ClientTokenService.newToken();
            cookieToStore = newCracked;
            mojangPremium = true;
        } else {
            // Premium username: register, reclaim, or normal login → PREMIUM.
            // Missing cookies no longer demote the account.
            newMode = AuthMode.PREMIUM;
            newPremium = ClientTokenService.newToken();
            cookieToStore = newPremium;
            mojangPremium = true;
        }

        repository.updateSession(player.getName(), newMode, newPremium, newCracked, mojangPremium);
        tokens.writeToken(player, cookieToStore);
    }

    public void kickLogout(Player player) {
        player.kick(Component.text("Logged out.", NamedTextColor.YELLOW));
    }

    public boolean setPassword(String playerName, String newPassword) {
        int min = plugin.getConfig().getInt("min-password-length", 4);
        int max = plugin.getConfig().getInt("max-password-length", 64);
        if (newPassword.length() < min || newPassword.length() > max) {
            return false;
        }
        Player online = Bukkit.getPlayerExact(playerName);
        UUID uuid = online != null ? online.getUniqueId() : null;
        repository.upsertPassword(playerName, uuid, hasher.hash(newPassword));
        return true;
    }

    /**
     * Wipes the account so the next join (or immediately if online) requires first-time signup.
     *
     * @return true if an account existed and was reset
     */
    public boolean resetAccount(String playerName) {
        if (!repository.deleteAccount(playerName)) {
            return false;
        }

        Player online = Bukkit.getPlayerExact(playerName);
        if (online != null && online.isOnline()) {
            UUID id = online.getUniqueId();
            authenticated.remove(id);
            joinContexts.remove(id);
            states.put(id, AuthState.NEED_REGISTER);
            scheduleTimeout(online);

            Runnable showRegister = () -> {
                if (!online.isOnline() || isAuthenticated(online)) {
                    return;
                }
                states.put(id, AuthState.NEED_REGISTER);
                dialogs.showRegister(online, "Your account was reset. Please create a new password.");
            };

            if (Bukkit.getOnlineMode()) {
                joinContexts.put(id, new JoinContext(true, null, false, false));
                showRegister.run();
            } else {
                premiumChecker.lookup(online.getName()).thenAccept(lookup ->
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            boolean premiumName = lookup.definitive() && lookup.premium();
                            joinContexts.put(id, new JoinContext(premiumName, null, false, false));
                            showRegister.run();
                        })
                );
            }

            online.sendMessage(Component.text(
                    "Your password was reset by an admin. Please create a new one.",
                    NamedTextColor.YELLOW
            ));
        }
        return true;
    }

    public void showCurrentDialog(Player player) {
        if (!player.isOnline() || isAuthenticated(player)) {
            return;
        }
        AuthState state = getState(player);
        if (state == AuthState.NEED_REGISTER) {
            dialogs.showRegister(player, null);
        } else if (state == AuthState.NEED_LOGIN) {
            dialogs.showLogin(player, null);
        }
    }

    public void shutdown() {
        timeoutTasks.values().forEach(BukkitTask::cancel);
        timeoutTasks.clear();
        authenticated.clear();
        states.clear();
        joinContexts.clear();
    }

    private void markAuthenticated(Player player, Component message) {
        UUID id = player.getUniqueId();
        authenticated.add(id);
        states.put(id, AuthState.AUTHENTICATED);
        joinContexts.remove(id);
        cancelTask(timeoutTasks, id);
        player.closeDialog();
        if (message != null) {
            player.sendMessage(message);
        }
    }

    private void scheduleTimeout(Player player) {
        UUID id = player.getUniqueId();
        cancelTask(timeoutTasks, id);
        int seconds = plugin.getConfig().getInt("auth-timeout-seconds", 120);
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline() && !isAuthenticated(player)) {
                player.kick(Component.text("Authentication timed out.", NamedTextColor.RED));
            }
        }, seconds * 20L);
        timeoutTasks.put(id, task);
    }

    private static void cancelTask(Map<UUID, BukkitTask> map, UUID id) {
        BukkitTask task = map.remove(id);
        if (task != null) {
            task.cancel();
        }
    }

    private record JoinContext(
            boolean premiumName,
            String cookieToken,
            boolean trustedPremium,
            boolean trustedCracked
    ) {
    }
}
