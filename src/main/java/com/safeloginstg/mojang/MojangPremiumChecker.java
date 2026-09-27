package com.safeloginstg.mojang;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Checks whether a username is a paid Minecraft account via the Mojang profile API.
 */
public final class MojangPremiumChecker {

    private static final String PROFILE_URL = "https://api.mojang.com/users/profiles/minecraft/";
    private static final long CACHE_TTL_MS = 10 * 60 * 1000L;

    private final JavaPlugin plugin;
    private final HttpClient httpClient;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public MojangPremiumChecker(JavaPlugin plugin) {
        this.plugin = plugin;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public CompletableFuture<Boolean> isPremiumUsername(String username) {
        String key = username.toLowerCase();
        CacheEntry cached = cache.get(key);
        if (cached != null && !cached.expired()) {
            return CompletableFuture.completedFuture(cached.premium());
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                String encoded = URLEncoder.encode(username, StandardCharsets.UTF_8);
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(PROFILE_URL + encoded))
                        .timeout(Duration.ofSeconds(8))
                        .header("Accept", "application/json")
                        .GET()
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();

                boolean premium;
                if (status == 200) {
                    JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
                    premium = json.has("id") && !json.get("id").getAsString().isBlank();
                } else if (status == 204 || status == 404) {
                    premium = false;
                } else if (status == 429) {
                    plugin.getLogger().warning("Mojang API rate-limited while checking " + username + "; treating as cracked.");
                    premium = false;
                } else {
                    plugin.getLogger().warning("Unexpected Mojang API status " + status + " for " + username + "; treating as cracked.");
                    premium = false;
                }

                cache.put(key, new CacheEntry(premium, System.currentTimeMillis() + CACHE_TTL_MS));
                return premium;
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Failed to query Mojang for " + username + "; treating as cracked.", e);
                return false;
            }
        });
    }

    public Optional<Boolean> peekCache(String username) {
        CacheEntry cached = cache.get(username.toLowerCase());
        if (cached == null || cached.expired()) {
            return Optional.empty();
        }
        return Optional.of(cached.premium());
    }

    private record CacheEntry(boolean premium, long expiresAt) {
        boolean expired() {
            return System.currentTimeMillis() > expiresAt;
        }
    }
}
