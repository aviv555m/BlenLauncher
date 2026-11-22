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
                    JsonElement rt = v.get("releaseTime");
                    if (rt.isJsonPrimitive()) {
                        if (rt.getAsJsonPrimitive().isNumber()) {
                            time = rt.getAsLong();
                        } else {
                            time = java.time.Instant.parse(rt.getAsString()).toEpochMilli();
                        }
                    }
                } catch (Exception ex) {
                    time = 0;
                }
            } else if (v.has("time")) {
                try {
                    JsonElement t = v.get("time");
                    if (t.isJsonPrimitive()) {
                        if (t.getAsJsonPrimitive().isNumber()) {
                            time = t.getAsLong();
                        } else {
                            time = java.time.Instant.parse(t.getAsString()).toEpochMilli();
                        }
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

        List<MinecraftVersion> others = versions.stream()
            .filter(v -> !"release".equals(v.getType()) && !"snapshot".equals(v.getType()))
            .sorted(Comparator.comparingLong(MinecraftVersion::getTime).reversed())
            .collect(Collectors.toList());

        availableVersions = new ArrayList<>();
        availableVersions.addAll(releases);
        availableVersions.addAll(snapshots);
        availableVersions.addAll(others);

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

        Path manifestPath = versionDir.resolve(version.getId() + ".json");
        String manifestJson;

        if (Files.exists(manifestPath)) {
            manifestJson = Files.readString(manifestPath);
        } else {
            String manifestUrl = versionManifestUrls.get(version.getId());
            if (manifestUrl == null) {
                manifestUrl = DOWNLOAD_BASE + "/mc/download/" + version.getId();
            }
            manifestJson = downloadString(manifestUrl);
            Files.writeString(manifestPath, manifestJson);
        }

        JsonObject manifest = gson.fromJson(manifestJson, JsonObject.class);

        // Handle version inheritance (e.g. Fabric profiles inherit from vanilla version)
        if (manifest.has("inheritsFrom")) {
            String parentId = manifest.get("inheritsFrom").getAsString();
            if (callback != null) callback.onProgress("Downloading base game version " + parentId + "...");
            downloadVersion(new MinecraftVersion(parentId, "release", 0), callback);
        }

        // Download the game jar if specified in downloads.client
        if (manifest.has("downloads") && manifest.getAsJsonObject("downloads").has("client")) {
            JsonObject clientDownload = manifest.getAsJsonObject("downloads").getAsJsonObject("client");
            String clientUrl = clientDownload.get("url").getAsString();
            String downloadUrl = clientUrl.startsWith("http") ? clientUrl : DOWNLOAD_BASE + clientUrl;
            Path jarPath = versionDir.resolve(version.getId() + ".jar");

            if (!Files.exists(jarPath) || Files.size(jarPath) == 0) {
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

                // Check OS rules to only download Windows libraries
                if (!isLibraryAllowed(lib)) {
                    continue;
                }

                if (lib.has("downloads")) {
                    JsonObject downloads = lib.getAsJsonObject("downloads");

                    // Standard artifact
                    if (downloads.has("artifact")) {
                        JsonObject artifact = downloads.getAsJsonObject("artifact");
                        if (artifact.has("path") && artifact.has("url")) {
                            Path libPath = installDir.resolve("libraries").resolve(artifact.get("path").getAsString());
                            downloadArtifactIfMissing(artifact.get("url").getAsString(), libPath, callback);
                        }
                    }

                    // Classifiers (Windows natives for older Minecraft versions)
                    if (downloads.has("classifiers")) {
                        JsonObject classifiers = downloads.getAsJsonObject("classifiers");
                        String[] nativeKeys = {"natives-windows", "natives-windows-64", "natives-windows-x86_64"};
                        for (String key : nativeKeys) {
                            if (classifiers.has(key)) {
                                JsonObject nativeArtifact = classifiers.getAsJsonObject(key);
                                if (nativeArtifact.has("path") && nativeArtifact.has("url")) {
                                    Path nativePath = installDir.resolve("libraries").resolve(nativeArtifact.get("path").getAsString());
                                    downloadArtifactIfMissing(nativeArtifact.get("url").getAsString(), nativePath, callback);
                                }
                            }
                        }
                    }
                } else if (lib.has("name") && lib.has("url")) {
                    // Maven style library (e.g. Fabric libraries)
                    String name = lib.get("name").getAsString();
                    String repoUrl = lib.get("url").getAsString();
                    String relPath = mavenNameToPath(name);
                    if (relPath != null) {
                        String fullUrl = repoUrl.endsWith("/") ? repoUrl + relPath : repoUrl + "/" + relPath;
                        Path libPath = installDir.resolve("libraries").resolve(relPath);
                        downloadArtifactIfMissing(fullUrl, libPath, callback);
                    }
                }

                downloaded++;
                if (downloaded % 20 == 0 && callback != null) {
                    callback.onProgress("Downloading libraries: " + downloaded + "/" + totalLibs);
                }
            }
        }

        if (callback != null) {
            callback.onProgress("Version " + version.getId() + " ready!");
        }
        return true;
    }

    private void downloadArtifactIfMissing(String urlString, Path libPath, ProgressCallback callback) {
        try {
            if (Files.exists(libPath) && Files.size(libPath) > 0) {
                return;
            }
            Path parent = libPath.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }
            String url = urlString.startsWith("http") ? urlString : DOWNLOAD_BASE + urlString;
            downloadFile(url, libPath, callback);
        } catch (Exception ex) {
            System.err.println("Failed to download library: " + libPath + " - " + ex.getMessage());
        }
    }

    public static String mavenNameToPath(String mavenName) {
        String[] parts = mavenName.split(":");
        if (parts.length < 3) return null;
        String group = parts[0].replace('.', '/');
        String artifact = parts[1];
        String version = parts[2];
        String classifier = parts.length > 3 ? "-" + parts[3] : "";
        return group + "/" + artifact + "/" + version + "/" + artifact + "-" + version + classifier + ".jar";
    }

    public static boolean isLibraryAllowed(JsonObject lib) {
        if (!lib.has("rules")) {
            return true;
        }
        boolean allow = false;
        for (JsonElement ruleEl : lib.getAsJsonArray("rules")) {
            JsonObject rule = ruleEl.getAsJsonObject();
            String action = rule.has("action") ? rule.get("action").getAsString() : "allow";
            boolean applies = true;
            if (rule.has("os")) {
                JsonObject os = rule.getAsJsonObject("os");
                if (os.has("name")) {
                    String osName = os.get("name").getAsString();
                    applies = "windows".equalsIgnoreCase(osName);
                }
            }
            if (applies) {
                allow = "allow".equalsIgnoreCase(action);
            }
        }
        return allow;
    }

    /**
     * Helper: download a file from a URL with safe temporary file and redirect support.
     */
    private void downloadFile(String urlString, Path destination, ProgressCallback callback) throws IOException, InterruptedException {
        HttpURLConnection conn = openConnectionWithRedirects(urlString);
        long contentLength = conn.getContentLengthLong();
        String name = destination.getFileName() != null ? destination.getFileName().toString() : destination.toString();

        Path parent = destination.getParent();
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent);
        }

        Path partFile = destination.resolveSibling(destination.getFileName().toString() + ".part");

        try {
            try (InputStream in = conn.getInputStream();
                 OutputStream out = Files.newOutputStream(partFile,
                     StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                byte[] buffer = new byte[8192];
                int n;
                long totalRead = 0;
                long lastReport = System.currentTimeMillis();
                while ((n = in.read(buffer)) > 0) {
                    out.write(buffer, 0, n);
                    totalRead += n;
                    if (callback != null && contentLength > 0) {
                        long now = System.currentTimeMillis();
                        if (now - lastReport > 500) {
                            int pct = (int) ((totalRead * 100) / contentLength);
                            callback.onProgress(String.format("Downloading %s: %d%% (%d/%d bytes)", name, pct, totalRead, contentLength));
                            lastReport = now;
                        }
                    }
                }
            }
            Files.move(partFile, destination, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            try { Files.deleteIfExists(partFile); } catch (Exception ignored) {}
            conn.disconnect();
        }
    }

    /**
     * Open an HTTP connection, automatically following HTTP/HTTPS redirects up to 5 times.
     */
    private HttpURLConnection openConnectionWithRedirects(String urlString) throws IOException {
        String currentUrl = urlString;
        for (int redirects = 0; redirects < 5; redirects++) {
            URL url = URI.create(currentUrl).toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(30_000);
            conn.setReadTimeout(60_000);
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
            return conn;
        }
        throw new IOException("Too many redirects for URL: " + urlString);
    }

    /**
     * Helper: download a string from URL.
     */
    private String downloadString(String urlString) throws IOException, InterruptedException {
        HttpURLConnection conn = openConnectionWithRedirects(urlString);
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


    /**
     * Callback interface for download progress.
     */
    public interface ProgressCallback {
        void onProgress(String message);
    }
}
