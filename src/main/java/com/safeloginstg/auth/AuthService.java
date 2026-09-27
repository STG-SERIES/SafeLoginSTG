package com.safeloginstg.auth;

import com.safeloginstg.SafeLoginSTG;
import com.safeloginstg.dialog.AuthDialogs;
import com.safeloginstg.mojang.MojangPremiumChecker;
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
            if (error != null) {
                plugin.getLogger().warning("Failed to read client cookie for " + player.getName() + ": " + error.getMessage());
            }
            String safeCookie = error == null ? cookieToken : null;

            if (serverOnlineMode) {
                Bukkit.getScheduler().runTask(plugin, () -> continueJoin(player, true, safeCookie));
                return;
            }

            premiumChecker.isPremiumUsername(player.getName()).thenAccept(premiumName ->
                    Bukkit.getScheduler().runTask(plugin, () -> continueJoin(player, premiumName, safeCookie))
            );
        });
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

        // Premium client that still owns the premium session can skip the password.
        if (premiumName
                && account.lastAuthMode() == AuthMode.PREMIUM
                && trustedPremium) {
            markAuthenticated(player, Component.text(
                    "Premium session recognized — logged in automatically.",
                    NamedTextColor.GREEN
            ));
            return;
        }

        // Everyone else (cracked, or premium after a cracked login) must enter the password.
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
            dialogs.showRegister(player, "No account found. Please create a password.");
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
        // Refresh trust flags against the latest stored tokens.
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
     * <p>Premium Mojang names can auto-login only while {@link AuthMode#PREMIUM} and the
     * client still holds the premium cookie. Switching to a cracked client marks the
     * account {@link AuthMode#CRACKED}; the next premium client must enter the password
     * once to reclaim premium auto-login.</p>
     */
    private void finalizeAuth(Player player, JoinContext context, boolean firstRegister) {
        AccountRecord existing = repository.findAccount(player.getName()).orElse(null);
        String premiumToken = existing == null ? null : existing.premiumToken();
        String crackedToken = existing == null ? null : existing.crackedToken();
        AuthMode lastMode = existing == null ? AuthMode.NONE : existing.lastAuthMode();

        AuthMode newMode;
        String newPremium = premiumToken;
        String newCracked = crackedToken;
        String cookieToStore;

        if (!context.premiumName()) {
            // Non-premium usernames are always cracked sessions.
            newMode = AuthMode.CRACKED;
            newCracked = ClientTokenService.newToken();
            cookieToStore = newCracked;
        } else if (firstRegister) {
            // First-time premium username: create password, then allow auto-login on this client.
            newMode = AuthMode.PREMIUM;
            newPremium = ClientTokenService.newToken();
            cookieToStore = newPremium;
        } else if (lastMode == AuthMode.CRACKED) {
            if (context.trustedPremium()) {
                // Returning premium launcher reclaiming the account after a cracked login.
                newMode = AuthMode.PREMIUM;
                newPremium = ClientTokenService.newToken();
                cookieToStore = newPremium;
            } else if (context.trustedCracked()) {
                // Same cracked client logging in again — stay cracked (password every time).
                newMode = AuthMode.CRACKED;
                newCracked = ClientTokenService.newToken();
                cookieToStore = newCracked;
            } else {
                // Unknown / new cracked-style client while account is in cracked mode.
                newMode = AuthMode.CRACKED;
                newCracked = ClientTokenService.newToken();
                cookieToStore = newCracked;
            }
        } else {
            // lastMode PREMIUM or NONE, but this client is not the trusted premium cookie holder
            // (cracked impostor, or premium cookie lost) → mark cracked for this client.
            if (context.trustedPremium()) {
                newMode = AuthMode.PREMIUM;
                newPremium = ClientTokenService.newToken();
                cookieToStore = newPremium;
            } else {
                newMode = AuthMode.CRACKED;
                newCracked = ClientTokenService.newToken();
                // Keep existing premium token so the real premium client can reclaim later.
                cookieToStore = newCracked;
            }
        }

        repository.updateSession(player.getName(), newMode, newPremium, newCracked);
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
                premiumChecker.isPremiumUsername(online.getName()).thenAccept(premiumName ->
                        Bukkit.getScheduler().runTask(plugin, () -> {
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
