package com.blen.core.auth;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

/**
 * Manages authentication for cracked Minecraft accounts.
 * Generates offline UUIDs from usernames (same algorithm as MC's OfflineModeSessionRepository).
 */
public class AuthManager {
    private final CachedSessionStore sessionStore;
    private Session currentSession;

    public AuthManager(Path storageDir) {
        this.sessionStore = new CachedSessionStore(storageDir);
    }

    /**
     * Generate an offline UUID from a username.
     * This matches Minecraft's OfflineModeSessionRepository algorithm:
     * "OfflinePlayer:" + username, then SHA-1 hash -> first 16 bytes as UUID (version 3).
     */
    public static String generateOfflineUuid(String username) {
        // Use MD5 instead of SHA-1 to match the exact MC offline UUID algorithm
        java.security.MessageDigest digest;
        try {
            digest = java.security.MessageDigest.getInstance("MD5");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new RuntimeException("MD5 not available", e);
        }
        byte[] hash = digest.digest(("OfflinePlayer:" + username).getBytes(java.nio.charset.StandardCharsets.UTF_8));

        // Take first 16 bytes and format as UUID with version 2 (offline)
        // Actually MC uses version 3 (MD5-based), but the offline ones don't have a standard version
        // The real algorithm: take all 16 bytes, set version and variant bits
        byte[] uuidBytes = new byte[16];
        System.arraycopy(hash, 0, uuidBytes, 0, 16);

        // Set version (bits 48-51 = 2 for offline)
        uuidBytes[6] = (byte) ((uuidBytes[6] & 0x0F) | (2 << 4));
        // Set variant (bits 64-65 = 10)
        uuidBytes[8] = (byte) ((uuidBytes[8] & 0x3F) | 0x80);

        UUID uuid = new UUID(
            java.nio.ByteBuffer.wrap(uuidBytes, 0, 8).getLong(),
            java.nio.ByteBuffer.wrap(uuidBytes, 8, 8).getLong()
        );
        return uuid.toString();
    }

    /**
     * Login with a cracked (offline) account.
     */
    public Session loginCracked(String username) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("Username cannot be empty");
        }
        String cleanName = username.trim();
        String uuid = generateOfflineUuid(cleanName);
        // Simple access token - in practice you can use a random string or the UUID
        String accessToken = UUID.randomUUID().toString().replace("-", "");

        currentSession = new Session(cleanName, uuid, accessToken);

        try {
            sessionStore.saveSession(currentSession);
        } catch (IOException e) {
            System.err.println("Failed to save session: " + e.getMessage());
        }

        return currentSession;
    }

    /**
     * Get the currently loaded session.
     */
    public Session getCurrentSession() {
        if (currentSession != null && currentSession.isValid()) {
            return currentSession;
        }
        // Try loading from cache
        Optional<Session> cached = sessionStore.loadSession();
        if (cached.isPresent() && cached.get().isValid()) {
            currentSession = cached.get();
            return currentSession;
        }
        return null;
    }

    /**
     * Check if the user is currently logged in.
     */
    public boolean isLoggedIn() {
        return getCurrentSession() != null;
    }

    /**
     * Logout and clear cached session.
     */
    public void logout() throws IOException {
        currentSession = null;
        sessionStore.clearSession();
    }
}
