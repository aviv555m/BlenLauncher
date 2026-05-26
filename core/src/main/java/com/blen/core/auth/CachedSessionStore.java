package com.blen.core.auth;

import java.io.*;
import java.nio.file.*;
import java.util.Optional;

/**
 * Persists session data to disk so the user stays logged in across launcher restarts.
 */
public class CachedSessionStore {
    private static final String SESSION_FILE = "session.json";

    private final Path basePath;

    public CachedSessionStore(Path basePath) {
        this.basePath = basePath;
    }

    public Optional<Session> loadSession() {
        Path file = basePath.resolve(SESSION_FILE);
        if (!Files.exists(file)) return Optional.empty();

        try {
            String json = Files.readString(file);
            // Simple JSON parse: {"username":"...","uuid":"...","accessToken":"..."}
            com.google.gson.Gson gson = new com.google.gson.Gson();
            return Optional.of(gson.fromJson(json, Session.class));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public void saveSession(Session session) throws IOException {
        Files.createDirectories(basePath);
        com.google.gson.Gson gson = new com.google.gson.GsonBuilder().setPrettyPrinting().create();
        Files.writeString(basePath.resolve(SESSION_FILE), gson.toJson(session));
    }

    public void clearSession() throws IOException {
        Path file = basePath.resolve(SESSION_FILE);
        if (Files.exists(file)) Files.delete(file);
    }
}
