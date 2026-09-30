package com.safeloginstg.auth;

import com.safeloginstg.SafeLoginSTG;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/**
 * Client cookie helpers used to tell a returning launcher/client apart
 * when the same username joins as premium or cracked.
 */
public final class ClientTokenService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final SafeLoginSTG plugin;
    private final NamespacedKey cookieKey;

    public ClientTokenService(SafeLoginSTG plugin) {
        this.plugin = plugin;
        this.cookieKey = new NamespacedKey(plugin, "client_token");
    }

    public CompletableFuture<String> readToken(Player player) {
        try {
            return player.retrieveCookie(cookieKey).thenApply(bytes -> {
                if (bytes == null || bytes.length == 0) {
                    return null;
                }
                return new String(bytes, StandardCharsets.UTF_8);
            }).exceptionally(error -> {
                plugin.getLogger().log(Level.WARNING, "Cookie read failed for " + player.getName(), error);
                return null;
            });
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Cookie read failed for " + player.getName(), e);
            return CompletableFuture.completedFuture(null);
        }
    }

    public void writeToken(Player player, String token) {
        if (token == null || token.isBlank() || !player.isOnline()) {
            return;
        }
        // Store immediately and retry shortly after — some stages reject cookies once.
        attemptStore(player, token);
        Bukkit.getScheduler().runTaskLater(plugin, () -> attemptStore(player, token), 20L);
        Bukkit.getScheduler().runTaskLater(plugin, () -> attemptStore(player, token), 60L);
    }

    private void attemptStore(Player player, String token) {
        if (!player.isOnline()) {
            return;
        }
        try {
            player.storeCookie(cookieKey, token.getBytes(StandardCharsets.UTF_8));
        } catch (IllegalStateException e) {
            plugin.getLogger().fine("Cookie store not ready for " + player.getName() + ": " + e.getMessage());
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Cookie store failed for " + player.getName(), e);
        }
    }

    public static String newToken() {
        byte[] raw = new byte[24];
        RANDOM.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    public static boolean matches(String cookieToken, String storedToken) {
        if (cookieToken == null || storedToken == null || cookieToken.isEmpty() || storedToken.isEmpty()) {
            return false;
        }
        if (cookieToken.length() != storedToken.length()) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < cookieToken.length(); i++) {
            result |= cookieToken.charAt(i) ^ storedToken.charAt(i);
        }
        return result == 0;
    }

    public static boolean hasCookie(String cookieToken) {
        return cookieToken != null && !cookieToken.isEmpty();
    }
}
