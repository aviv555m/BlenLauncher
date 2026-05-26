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
    private static final String ASSET_DIR = "saves/PlayerSkinCache";

    private final Path skinCacheDir;
    private final Gson gson = new Gson();

    public SkinService(Path installDir) {
        this.skinCacheDir = installDir.resolve(ASSET_DIR);
    }

    /**
     * Fetch player info (UUID + username) from Mojang using the Ashcon proxy API.
     */
    public PlayerInfo fetchPlayerInfo(String username) throws IOException, InterruptedException {
        URL url = URI.create(SKIN_API_BASE + username).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(30_000);

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream()))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            JsonObject json = gson.fromJson(sb.toString(), JsonObject.class);

            return new PlayerInfo(
                json.get("username").getAsString(),
                json.get("uuid").getAsString(),
                json.has("textures") ? extractSkinUrl(json.getAsJsonObject("textures")) : null
            );
        } finally {
            conn.disconnect();
        }
    }

    /**
     * Download and cache a player's skin.
     */
    public Path downloadAndCacheSkin(String username) throws IOException, InterruptedException {
        PlayerInfo info = fetchPlayerInfo(username);
        if (info.skinUrl() == null) return null;

        // Check if already cached
        Path cachedPath = skinCacheDir.resolve(info.uuid().replace("-", ""));
        if (Files.exists(cachedPath)) return cachedPath;

        Files.createDirectories(skinCacheDir);

        URL url = URI.create(info.skinUrl()).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(30_000);
        conn.setReadTimeout(60_000);

           try (InputStream in = conn.getInputStream();
               OutputStream out = Files.newOutputStream(cachedPath, StandardOpenOption.CREATE_NEW)) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
            }
        } finally {
            conn.disconnect();
        }

        return cachedPath;
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
     * Record for player info fetched from Mojang API.
     */
    public record PlayerInfo(String username, String uuid, String skinUrl) {}
}
