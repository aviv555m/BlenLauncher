package com.blen.core.skin;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.StandardOpenOption;
import java.nio.file.*;

/**
 * Fetches and manages player skins.
 * Uses the Ashcon API (https://api.ashcon.app) which works for cracked/offline players.
 */
public class SkinService {
    private static final String SKIN_API_BASE = "https://api.ashcon.app/mojang/v2/user/";
    private static final String MINOTAR_SKIN_BASE = "https://minotar.net/skin/";
    private static final String MINOTAR_AVATAR_BASE = "https://minotar.net/avatar/";
    private static final String ASSET_DIR = "saves/PlayerSkinCache";
    private static final String AVATAR_DIR = "avatars";

    private final Path skinCacheDir;
    private final Path avatarCacheDir;
    private final Gson gson = new Gson();

    public SkinService(Path installDir) {
        this.skinCacheDir = installDir.resolve(ASSET_DIR);
        this.avatarCacheDir = installDir.getParent().resolve(AVATAR_DIR);
    }

    /**
     * Fetch player info (UUID + username) from Mojang or Ashcon proxy, with fallback to Minotar.
     */
    public PlayerInfo fetchPlayerInfo(String username) {
        if (username == null || username.isBlank()) {
            return null;
        }

        try {
            URL url = URI.create(SKIN_API_BASE + username).toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(8_000);
            conn.setReadTimeout(12_000);
            conn.setRequestProperty("User-Agent", "BlenLauncher/1.1");

            int code = conn.getResponseCode();
            if (code == 200) {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line);
                    }
                    JsonObject json = gson.fromJson(sb.toString(), JsonObject.class);
                    return new PlayerInfo(
                        json.has("username") ? json.get("username").getAsString() : username,
                        json.has("uuid") ? json.get("uuid").getAsString() : com.blen.core.auth.AuthManager.generateOfflineUuid(username),
                        json.has("textures") ? extractSkinUrl(json.getAsJsonObject("textures")) : MINOTAR_SKIN_BASE + username
                    );
                } finally {
                    conn.disconnect();
                }
            }
            conn.disconnect();
        } catch (Exception ignored) {
        }

        // Fallback to Minotar / offline player info
        return new PlayerInfo(
            username,
            com.blen.core.auth.AuthManager.generateOfflineUuid(username),
            MINOTAR_SKIN_BASE + username
        );
    }

    /**
     * Download and cache player avatar (head icon) for UI display.
     */
    public Path downloadAndCacheAvatar(String username) {
        if (username == null || username.isBlank()) return null;
        try {
            Files.createDirectories(avatarCacheDir);
            Path cachedAvatar = avatarCacheDir.resolve(username + ".png");
            if (Files.exists(cachedAvatar) && Files.size(cachedAvatar) > 0) {
                return cachedAvatar;
            }

            URL url = URI.create(MINOTAR_AVATAR_BASE + username + "/64.png").toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(8_000);
            conn.setReadTimeout(12_000);
            conn.setRequestProperty("User-Agent", "BlenLauncher/1.1");

            if (conn.getResponseCode() == 200) {
                Path part = cachedAvatar.resolveSibling(username + ".part");
                try (InputStream in = conn.getInputStream()) {
                    Files.copy(in, part, StandardCopyOption.REPLACE_EXISTING);
                }
                Files.move(part, cachedAvatar, StandardCopyOption.REPLACE_EXISTING);
                return cachedAvatar;
            }
            conn.disconnect();
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * Download and cache a player's skin.
     */
    public Path downloadAndCacheSkin(String username) {
        PlayerInfo info = fetchPlayerInfo(username);
        if (info == null || info.skinUrl() == null) return null;

        try {
            Path cachedPath = skinCacheDir.resolve(info.uuid().replace("-", "") + ".png");
            if (Files.exists(cachedPath) && Files.size(cachedPath) > 0) return cachedPath;

            Files.createDirectories(skinCacheDir);

            URL url = URI.create(info.skinUrl()).toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(20_000);
            conn.setRequestProperty("User-Agent", "BlenLauncher/1.1");

            if (conn.getResponseCode() == 200) {
                Path part = cachedPath.resolveSibling(info.uuid().replace("-", "") + ".part");
                try (InputStream in = conn.getInputStream()) {
                    Files.copy(in, part, StandardCopyOption.REPLACE_EXISTING);
                }
                Files.move(part, cachedPath, StandardCopyOption.REPLACE_EXISTING);
                return cachedPath;
            }
            conn.disconnect();
        } catch (Exception ignored) {
        }

        return null;
    }

    /**
     * Extract skin URL from the textures JSON object.
     */
    private String extractSkinUrl(JsonObject textures) {
        if (!textures.has("SKIN")) return null;
        JsonObject skin = textures.getAsJsonObject("SKIN");
        if (!skin.has("url")) return null;
        return skin.get("url").getAsString();
    }

    /**
     * Record for player info.
     */
    public record PlayerInfo(String username, String uuid, String skinUrl) {}
}
