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
        if (username == null) {
            return UUID.randomUUID().toString();
        }
        return UUID.nameUUIDFromBytes(
            ("OfflinePlayer:" + username).getBytes(java.nio.charset.StandardCharsets.UTF_8)
        ).toString();
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
