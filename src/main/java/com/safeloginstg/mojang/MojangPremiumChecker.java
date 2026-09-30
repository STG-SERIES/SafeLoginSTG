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
    private static final long CACHE_TTL_MS = 30 * 60 * 1000L;

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

    /**
     * @return result with {@code definitive=false} when Mojang could not be reached / rate-limited
     */
    public CompletableFuture<PremiumLookup> lookup(String username) {
        String key = username.toLowerCase();
        CacheEntry cached = cache.get(key);
        if (cached != null && !cached.expired()) {
            return CompletableFuture.completedFuture(new PremiumLookup(cached.premium(), true));
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                // Mojang expects the raw username in the path; only encode unsafe chars.
                String encoded = URLEncoder.encode(username, StandardCharsets.UTF_8).replace("+", "%20");
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(PROFILE_URL + encoded))
                        .timeout(Duration.ofSeconds(8))
                        .header("Accept", "application/json")
                        .GET()
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();

                if (status == 200) {
                    JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
                    boolean premium = json.has("id") && !json.get("id").getAsString().isBlank();
                    cache.put(key, new CacheEntry(premium, System.currentTimeMillis() + CACHE_TTL_MS));
                    return new PremiumLookup(premium, true);
                }
                if (status == 204 || status == 404) {
                    cache.put(key, new CacheEntry(false, System.currentTimeMillis() + CACHE_TTL_MS));
                    return new PremiumLookup(false, true);
                }
                if (status == 429) {
                    plugin.getLogger().warning("Mojang API rate-limited while checking " + username + ".");
                    return new PremiumLookup(false, false);
                }
                plugin.getLogger().warning("Unexpected Mojang API status " + status + " for " + username + ".");
                return new PremiumLookup(false, false);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Failed to query Mojang for " + username + ".", e);
                return new PremiumLookup(false, false);
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

    public record PremiumLookup(boolean premium, boolean definitive) {
    }

    private record CacheEntry(boolean premium, long expiresAt) {
        boolean expired() {
            return System.currentTimeMillis() > expiresAt;
        }
    }
}
