package com.blen.core.version;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.StandardOpenOption;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Manages Minecraft version list, downloads, and installation.
 * Fetches version data from Mojang's official API.
 */
public class VersionManager {
    // Use Mojang's official version manifest which contains per-version manifest URLs
    private static final String VERSIONS_URL = "https://launchermeta.mojang.com/mc/game/version_manifest_v2.json";
    private static final String DOWNLOAD_BASE = "https://piston-data.mojang.com";

    private final Path installDir;
    private List<MinecraftVersion> availableVersions;
    // Map version id -> manifest URL (populated when fetching the versions list)
    private final Map<String, String> versionManifestUrls = new HashMap<>();
    private final Gson gson = new Gson();
    private final AtomicBoolean versionsLoaded = new AtomicBoolean(false);

    public VersionManager(Path installDir) {
        this.installDir = installDir;
    }

    /**
     * Fetch the list of available Minecraft versions from Mojang.
     */
    public List<MinecraftVersion> getAvailableVersions() throws IOException, InterruptedException {
        if (versionsLoaded.get() && availableVersions != null) {
            return availableVersions;
        }

        // Download version list JSON (try network first, fall back to bundled resource)
        String json = null;
        try {
            json = downloadString(VERSIONS_URL);
        } catch (Exception ex) {
            // network failed; try bundled fallback
            InputStream in = VersionManager.class.getResourceAsStream("/versions_fallback.json");
            if (in == null) {
                throw new IOException("Failed to load versions from network and no fallback available", ex);
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                json = sb.toString();
            }
        }

        JsonElement rootEl = gson.fromJson(json, JsonElement.class);

        // Parse the versions array from the response (support object with 'versions' or a raw array)
        JsonArray versionsArr;
        if (rootEl.isJsonObject() && rootEl.getAsJsonObject().has("versions")) {
            versionsArr = rootEl.getAsJsonObject().getAsJsonArray("versions");
        } else if (rootEl.isJsonArray()) {
            versionsArr = rootEl.getAsJsonArray();
        } else {
            throw new IOException("Unexpected versions JSON structure");
        }
        List<MinecraftVersion> versions = new ArrayList<>();

        for (JsonElement el : versionsArr) {
            JsonObject v = el.getAsJsonObject();
            String id = v.has("id") ? v.get("id").getAsString() : "unknown";
            String type = v.has("type") ? v.get("type").getAsString() : "release";
            long time = 0;
            if (v.has("releaseTime")) {
                try {
                    if (v.get("releaseTime").isJsonPrimitive() && v.get("releaseTime").getAsJsonPrimitive().isNumber()) {
                        time = v.get("releaseTime").getAsLong();
                    }
                } catch (Exception ex) {
                    time = 0;
                }
            }

            // some manifests (like Mojang's) include a 'url' field with the per-version manifest
            if (v.has("url")) {
                try {
                    versionManifestUrls.put(id, v.get("url").getAsString());
                } catch (Exception ignore) {
                }
            }

            versions.add(new MinecraftVersion(id, type, time));
        }

        // Sort: releases first (newest first), then snapshots (newest first)
        List<MinecraftVersion> releases = versions.stream()
            .filter(v -> "release".equals(v.getType()))
            .sorted(Comparator.comparingLong(MinecraftVersion::getTime).reversed())
            .collect(Collectors.toList());

        List<MinecraftVersion> snapshots = versions.stream()
            .filter(v -> "snapshot".equals(v.getType()))
            .sorted(Comparator.comparingLong(MinecraftVersion::getTime).reversed())
            .collect(Collectors.toList());

        availableVersions = new ArrayList<>();
        availableVersions.addAll(releases);
        availableVersions.addAll(snapshots);

        versionsLoaded.set(true);
        return availableVersions;
    }

    /**
     * Get only release versions.
     */
    public List<MinecraftVersion> getReleaseVersions() throws IOException, InterruptedException {
        return getAvailableVersions().stream()
            .filter(v -> "release".equals(v.getType()))
            .collect(Collectors.toList());
    }

    /**
     * Check if a version is already installed locally.
     */
    public boolean isVersionInstalled(String versionId) {
        Path versionDir = installDir.resolve("versions").resolve(versionId);
        return Files.exists(versionDir) && Files.exists(versionDir.resolve(versionId + ".jar"));
    }

    /**
     * Get the path to a specific version's directory.
     */
    public Path getVersionDir(String versionId) {
        return installDir.resolve("versions").resolve(versionId);
    }

    /**
     * Download and install a Minecraft version (jar + libraries).
     * Returns true if installation succeeded.
     */
    public boolean downloadVersion(MinecraftVersion version, ProgressCallback callback) throws IOException, InterruptedException {
        Path versionDir = getVersionDir(version.getId());
        Files.createDirectories(versionDir);

        // Download the version's JSON manifest. Prefer the manifest URL we recorded earlier.
        String manifestUrl = versionManifestUrls.get(version.getId());
        if (manifestUrl == null) {
            // fallback: try a known piston-data path (older layout)
            manifestUrl = DOWNLOAD_BASE + "/mc/download/" + version.getId();
        }
        String manifestJson = downloadString(manifestUrl);
        JsonObject manifest = gson.fromJson(manifestJson, JsonObject.class);

        // Download the game jar
        if (manifest.has("downloads") && manifest.getAsJsonObject("downloads").has("client")) {
            JsonObject clientDownload = manifest.getAsJsonObject("downloads").getAsJsonObject("client");
            String clientUrl = clientDownload.get("url").getAsString();
            String downloadUrl = clientUrl.startsWith("http") ? clientUrl : DOWNLOAD_BASE + clientUrl;
            Path jarPath = versionDir.resolve(version.getId() + ".jar");

            if (!Files.exists(jarPath)) {
                if (callback != null) callback.onProgress("Downloading Minecraft " + version.getId() + "...");
                downloadFile(downloadUrl, jarPath, callback);
            }
        }

        // Download libraries (native jars and dependencies)
        if (manifest.has("libraries")) {
            JsonArray libraries = manifest.getAsJsonArray("libraries");
            int totalLibs = libraries.size();
            int downloaded = 0;

            for (JsonElement libEl : libraries) {
                JsonObject lib = libEl.getAsJsonObject();

                // Skip client-only rule check for simplicity
                if (lib.has("downloads")) {
                    JsonObject downloads = lib.getAsJsonObject("downloads");
                    if (downloads.has("artifact")) {
                        JsonObject artifact = downloads.getAsJsonObject("artifact");
                        String path = artifact.get("path").getAsString();
                        String artifactUrl = artifact.get("url").getAsString();
                        String url = artifactUrl.startsWith("http") ? artifactUrl : DOWNLOAD_BASE + artifactUrl;

                        Path libPath = installDir.resolve("libraries").resolve(path);
                        Path parent = libPath.getParent();
                        if (parent != null && !Files.exists(parent)) {
                            Files.createDirectories(parent);
                        }

                        if (!Files.exists(libPath) || Files.size(libPath) == 0) {
                            downloadFile(url, libPath, callback);
                        }
                    }
                }

                downloaded++;
                if (downloaded % 20 == 0 && callback != null) {
                    callback.onProgress("Downloading libraries: " + downloaded + "/" + totalLibs);
                }
            }
        }

        // Save the manifest locally for future reference
        Path manifestPath = versionDir.resolve(version.getId() + ".json");
        if (!Files.exists(manifestPath)) {
            Files.writeString(manifestPath, gson.toJson(manifest));
        }

        if (callback != null) {
            callback.onProgress("Version " + version.getId() + " ready!");
        }
        return true;
    }

    /**
     * Helper: download a file from a URL with optional progress tracking.
     */
    private void downloadFile(String urlString, Path destination, ProgressCallback callback) throws IOException, InterruptedException {
        URL url = URI.create(urlString).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(30_000);
        conn.setReadTimeout(60_000);
        conn.setRequestProperty("User-Agent", "BlenLauncher/1.0");

        long contentLength = conn.getContentLengthLong();
        String name = destination.getFileName() != null ? destination.getFileName().toString() : destination.toString();

        try (InputStream in = conn.getInputStream();
             OutputStream out = Files.newOutputStream(destination,
                 StandardOpenOption.CREATE_NEW)) {
            byte[] buffer = new byte[8192];
            int n;
            long totalRead = 0;
            long lastReport = System.currentTimeMillis();
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
                totalRead += n;
                if (callback != null && contentLength > 0) {
                    long now = System.currentTimeMillis();
                    if (now - lastReport > 500) { // report at most twice per second
                        int pct = (int) ((totalRead * 100) / contentLength);
                        callback.onProgress(String.format("Downloading %s: %d%% (%d/%d bytes)", name, pct, totalRead, contentLength));
                        lastReport = now;
                    }
                }
            }

            // final report
            if (callback != null) {
                if (contentLength > 0) {
                    callback.onProgress(String.format("Downloaded %s: 100%% (%d/%d bytes)", name, totalRead, contentLength));
                } else {
                    callback.onProgress(String.format("Downloaded %s: %d bytes", name, totalRead));
                }
            }
        } finally {
            conn.disconnect();
        }
    }

    /**
     * Helper: download a string from URL.
     */
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

    /**
     * Callback interface for download progress.
     */
    public interface ProgressCallback {
        void onProgress(String message);
    }
}
