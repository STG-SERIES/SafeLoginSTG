package com.safeloginstg.storage;

import com.safeloginstg.auth.AuthMode;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Local YAML account store. Passwords are stored as PBKDF2 hashes only.
 */
public final class YamlAuthRepository {

    private final File file;
    private final Logger logger;
    private FileConfiguration config;

    public YamlAuthRepository(File dataFolder, Logger logger) {
        if (!dataFolder.exists() && !dataFolder.mkdirs()) {
            throw new IllegalStateException("Could not create plugin data folder: " + dataFolder);
        }
        this.file = new File(dataFolder, "accounts.yml");
        this.logger = logger;
    }

    public void init() {
        if (!file.exists()) {
            try {
                if (!file.createNewFile()) {
                    throw new IOException("createNewFile returned false");
                }
            } catch (IOException e) {
                throw new IllegalStateException("Failed to create accounts.yml", e);
            }
        }
        this.config = YamlConfiguration.loadConfiguration(file);
        if (!config.isConfigurationSection("accounts")) {
            config.createSection("accounts");
            save();
        }
    }

    public Optional<AccountRecord> findAccount(String playerName) {
        String base = path(playerName);
        if (!config.contains(base + ".hash")) {
            return Optional.empty();
        }
        return Optional.of(new AccountRecord(
                config.getString(base + ".hash"),
                AuthMode.fromStorage(config.getString(base + ".last-auth-mode")),
                config.getString(base + ".premium-token"),
                config.getString(base + ".cracked-token")
        ));
    }

    public Optional<String> findPasswordHash(String playerName) {
        return findAccount(playerName).map(AccountRecord::passwordHash);
    }

    public boolean hasAccount(String playerName) {
        return findPasswordHash(playerName).isPresent();
    }

    public AuthMode getLastAuthMode(String playerName) {
        return findAccount(playerName).map(AccountRecord::lastAuthMode).orElse(AuthMode.NONE);
    }

    public synchronized void upsertPassword(String playerName, UUID uuid, String passwordHash) {
        String base = path(playerName);
        config.set(base + ".hash", passwordHash);
        config.set(base + ".uuid", uuid == null ? null : uuid.toString());
        config.set(base + ".updated-at", System.currentTimeMillis());
        if (!config.contains(base + ".created-at")) {
            config.set(base + ".created-at", System.currentTimeMillis());
        }
        if (!config.contains(base + ".last-auth-mode")) {
            config.set(base + ".last-auth-mode", AuthMode.NONE.name());
        }
        save();
    }

    public synchronized void updateSession(
            String playerName,
            AuthMode lastAuthMode,
            String premiumToken,
            String crackedToken
    ) {
        String base = path(playerName);
        if (!config.contains(base + ".hash")) {
            return;
        }
        config.set(base + ".last-auth-mode", lastAuthMode.name());
        config.set(base + ".premium-token", premiumToken);
        config.set(base + ".cracked-token", crackedToken);
        config.set(base + ".updated-at", System.currentTimeMillis());
        save();
    }

    /**
     * Deletes the account entirely so the player must register again.
     *
     * @return true if an account existed and was removed
     */
    public synchronized boolean deleteAccount(String playerName) {
        String base = path(playerName);
        if (!config.contains(base)) {
            return false;
        }
        config.set(base, null);
        save();
        return true;
    }

    public void close() {
        save();
    }

    private void save() {
        try {
            config.save(file);
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Failed to save accounts.yml", e);
        }
    }

    private static String path(String playerName) {
        return "accounts." + playerName.toLowerCase();
    }

    public record AccountRecord(
            String passwordHash,
            AuthMode lastAuthMode,
            String premiumToken,
            String crackedToken
    ) {
    }
}
