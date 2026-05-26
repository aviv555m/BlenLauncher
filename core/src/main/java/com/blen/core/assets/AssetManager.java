package com.blen.core.assets;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.StandardOpenOption;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages Minecraft assets (textures, sounds, models, languages).
 * Downloads from Mojang's asset index system.
 */
public class AssetManager {
    private static final String ASSETS_URL = "https://resources.download.minecraft.net/";

    private final Path installDir;
    private final AtomicBoolean assetsLoaded = new AtomicBoolean(false);

    public AssetManager(Path installDir) {
        this.installDir = installDir;
    }

    /**
     * Check if assets for a given version are loaded.
     */
    public boolean hasAssets(String versionId) {
        Path indexFile = installDir.resolve("assets").resolve("indexes")
            .resolve(versionId + ".json");
        return Files.exists(indexFile);
    }

    /**
     * Download assets for a specific version using Mojang's asset index.
     */
    public void downloadAssets(String versionId, ProgressCallback callback) throws IOException, InterruptedException {
        Path indexPath = installDir.resolve("assets").resolve("indexes");
        Files.createDirectories(indexPath);

        JsonObject assetIndex = resolveAssetIndex(versionId);
        String assetIndexId = assetIndex.get("id").getAsString();
        String indexUrl = assetIndex.get("url").getAsString();
        String indexJson = downloadString(indexUrl);

        Gson gson = new Gson();
        JsonObject index = gson.fromJson(indexJson, JsonObject.class);

        Path objectsDir = installDir.resolve("assets").resolve("objects");
        JsonElement objectsEl = index.get("objects");

        if (objectsEl.isJsonObject()) {
            JsonObject objects = objectsEl.getAsJsonObject();
            int total = objects.size();
            int downloaded = 0;

            for (Map.Entry<String, JsonElement> entry : objects.entrySet()) {
                String hash = entry.getValue().getAsJsonObject().get("hash").getAsString();

                // The actual file path uses the hash: first 2 chars as subdir
                Path filePath = objectsDir.resolve(hash.substring(0, 2))
                    .resolve(hash);

                if (!Files.exists(filePath)) {
                    downloadObject(hash, filePath, callback);
                }

                downloaded++;
                if (downloaded % 100 == 0 && callback != null) {
                    callback.onProgress("Downloading assets: " + downloaded + "/" + total);
                }
            }
        }

        // Save the index locally
        Files.writeString(indexPath.resolve(assetIndexId + ".json"), indexJson);
    }

    /**
     * Download a single asset object using hash-based path.
     */
    private void downloadObject(String hash, Path destination, ProgressCallback callback) throws IOException {
        // Mojang uses the hash as both filename and path prefix (first 2 chars)
        String pathPrefix = hash.substring(0, 2);
        URL url = URI.create(ASSETS_URL + pathPrefix + "/" + hash).toURL();

        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(30_000);
        conn.setReadTimeout(60_000);
        conn.setRequestProperty("User-Agent", "BlenLauncher/1.0");

        long contentLength = conn.getContentLengthLong();

        try {
            Path parent = destination.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }

            try (InputStream in = conn.getInputStream();
                 OutputStream out = Files.newOutputStream(destination, StandardOpenOption.CREATE_NEW)) {
                byte[] buffer = new byte[8192];
                int n;
                long totalRead = 0;
                long lastReport = System.currentTimeMillis();
                while ((n = in.read(buffer)) > 0) {
                    out.write(buffer, 0, n);
                    totalRead += n;
                    // Intentionally avoid per-asset UI updates to prevent log spam/freeze-like UX.
                }
            }
        } finally {
            conn.disconnect();
        }
    }

    private String downloadString(String urlString) throws IOException, InterruptedException {
        URL url = URI.create(urlString).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(30_000);
        conn.setReadTimeout(60_000);
        conn.setRequestProperty("User-Agent", "BlenLauncher/1.0");

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream()))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        } finally {
            conn.disconnect();
        }
    }

    public interface ProgressCallback {
        void onProgress(String message);
    }

    private JsonObject resolveAssetIndex(String versionId) throws IOException {
        Path versionManifestPath = installDir.resolve("versions")
            .resolve(versionId)
            .resolve(versionId + ".json");

        if (!Files.exists(versionManifestPath)) {
            throw new IOException("Missing version manifest: " + versionManifestPath);
        }

        JsonObject manifest = new Gson().fromJson(Files.readString(versionManifestPath), JsonObject.class);
        if (!manifest.has("assetIndex")) {
            throw new IOException("Version manifest has no assetIndex section for " + versionId);
        }
        JsonObject assetIndex = manifest.getAsJsonObject("assetIndex");
        if (!assetIndex.has("id") || !assetIndex.has("url")) {
            throw new IOException("Invalid assetIndex in manifest for " + versionId);
        }
        return assetIndex;
    }
}
