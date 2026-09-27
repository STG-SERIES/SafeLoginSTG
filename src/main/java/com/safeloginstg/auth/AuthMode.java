package com.safeloginstg.auth;

/**
 * Last successful auth mode stored per account.
 */
public enum AuthMode {
    PREMIUM,
    CRACKED,
    NONE;

    public static AuthMode fromStorage(String raw) {
        if (raw == null || raw.isBlank()) {
            return NONE;
        }
        try {
            return AuthMode.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return NONE;
        }
    }
}
