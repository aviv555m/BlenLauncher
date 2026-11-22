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
import java.util.List;
import java.util.ArrayList;
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
        try {
            JsonObject assetIndex = resolveAssetIndex(versionId);
            String assetIndexId = assetIndex.get("id").getAsString();
            Path indexFile = installDir.resolve("assets").resolve("indexes")
                .resolve(assetIndexId + ".json");
            return Files.exists(indexFile) && Files.size(indexFile) > 0;
        } catch (Exception ex) {
            try {
                Path fallback = installDir.resolve("assets").resolve("indexes").resolve(versionId + ".json");
                return Files.exists(fallback) && Files.size(fallback) > 0;
            } catch (Exception ignored) {
                return false;
            }
        }
    }

    /**
     * Download assets for a specific version using Mojang's asset index.
     * Uses parallel worker threads to rapidly download assets.
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

        if (objectsEl != null && objectsEl.isJsonObject()) {
            JsonObject objects = objectsEl.getAsJsonObject();
            int total = objects.size();
            java.util.concurrent.atomic.AtomicInteger downloaded = new java.util.concurrent.atomic.AtomicInteger(0);

            java.util.List<Map.Entry<String, JsonElement>> entriesToDownload = new ArrayList<>();
            for (Map.Entry<String, JsonElement> entry : objects.entrySet()) {
                String hash = entry.getValue().getAsJsonObject().get("hash").getAsString();
                Path filePath = objectsDir.resolve(hash.substring(0, 2)).resolve(hash);
                if (!Files.exists(filePath) || Files.size(filePath) == 0) {
                    entriesToDownload.add(entry);
                } else {
                    downloaded.incrementAndGet();
                }
            }

            if (!entriesToDownload.isEmpty()) {
                int toDownloadCount = entriesToDownload.size();
                if (callback != null) {
                    callback.onProgress(String.format("Downloading assets: %d missing / %d total...", toDownloadCount, total));
                }

                int numThreads = Math.min(16, Math.max(4, Runtime.getRuntime().availableProcessors() * 2));
                java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(numThreads);
                java.util.concurrent.atomic.AtomicLong lastReportTime = new java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis());

                for (Map.Entry<String, JsonElement> entry : entriesToDownload) {
                    executor.submit(() -> {
                        try {
                            String hash = entry.getValue().getAsJsonObject().get("hash").getAsString();
                            Path filePath = objectsDir.resolve(hash.substring(0, 2)).resolve(hash);
                            downloadObject(hash, filePath);
                        } catch (Exception ignored) {
                        } finally {
                            int current = downloaded.incrementAndGet();
                            if (callback != null) {
                                long now = System.currentTimeMillis();
                                if (now - lastReportTime.get() > 400 || current >= total) {
                                    lastReportTime.set(now);
                                    callback.onProgress(String.format("Downloading assets: %d/%d (%d%%)", current, total, (current * 100) / total));
                                }
                            }
                        }
                    });
                }

                executor.shutdown();
                executor.awaitTermination(30, java.util.concurrent.TimeUnit.MINUTES);
            }
        }

        // Save the index locally
        Files.writeString(indexPath.resolve(assetIndexId + ".json"), indexJson);
        if (callback != null) {
            callback.onProgress("Assets ready!");
        }
    }

    /**
     * Download a single asset object using hash-based path with temporary part file.
     */
    private void downloadObject(String hash, Path destination) throws IOException {
        String pathPrefix = hash.substring(0, 2);
        URL url = URI.create(ASSETS_URL + pathPrefix + "/" + hash).toURL();

        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(20_000);
        conn.setReadTimeout(30_000);
        conn.setRequestProperty("User-Agent", "BlenLauncher/1.1");

        Path parent = destination.getParent();
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent);
        }

        Path partFile = destination.resolveSibling(hash + ".part");

        try {
            if (conn.getResponseCode() != 200) {
                return;
            }
            try (InputStream in = conn.getInputStream();
                 OutputStream out = Files.newOutputStream(partFile,
                     StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) > 0) {
                    out.write(buffer, 0, n);
                }
            }
            Files.move(partFile, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } finally {
            try { Files.deleteIfExists(partFile); } catch (Exception ignored) {}
            conn.disconnect();
        }
    }

    private String downloadString(String urlString) throws IOException, InterruptedException {
        String currentUrl = urlString;
        for (int redirects = 0; redirects < 5; redirects++) {
            URL url = URI.create(currentUrl).toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(25_000);
            conn.setReadTimeout(35_000);
            conn.setRequestProperty("User-Agent", "BlenLauncher/1.1");

            int status = conn.getResponseCode();
            if (status == HttpURLConnection.HTTP_MOVED_TEMP || status == HttpURLConnection.HTTP_MOVED_PERM
                    || status == 307 || status == 308) {
                String newUrl = conn.getHeaderField("Location");
                if (newUrl != null && !newUrl.isBlank()) {
                    conn.disconnect();
                    currentUrl = newUrl;
                    continue;
                }
            }

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
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
        throw new IOException("Too many redirects: " + urlString);
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
            // Check if inheritsFrom provides assetIndex
            if (manifest.has("inheritsFrom")) {
                return resolveAssetIndex(manifest.get("inheritsFrom").getAsString());
            }
            throw new IOException("Version manifest has no assetIndex section for " + versionId);
        }
        JsonObject assetIndex = manifest.getAsJsonObject("assetIndex");
        if (!assetIndex.has("id") || !assetIndex.has("url")) {
            throw new IOException("Invalid assetIndex in manifest for " + versionId);
        }
        return assetIndex;
    }
}
