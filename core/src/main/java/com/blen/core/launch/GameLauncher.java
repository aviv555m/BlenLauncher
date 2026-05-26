package com.blen.core.launch;

import com.blen.core.auth.Session;
import com.blen.core.version.MinecraftVersion;
import com.blen.core.version.VersionManager;
import com.google.gson.JsonObject;

import java.io.*;
import java.net.URL;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * Builds the classpath and launches Minecraft as a separate JVM process.
 * Handles JVM arguments, native libraries, and game arguments.
 */
public class GameLauncher {
    private static final String MC_MAIN_CLASS = "net.minecraft.client.main.Main";
    private static final int REQUIRED_JAVA_VERSION = 25;
    private static final String JDK_DOWNLOAD_URL = "https://api.adoptium.net/v3/binary/latest/25/ga/windows/x64/jdk/hotspot/normal/eclipse";

    private final Path installDir;
    private final VersionManager versionManager;

    public GameLauncher(Path installDir, VersionManager versionManager) {
        this.installDir = installDir;
        this.versionManager = versionManager;
    }

    /**
     * Launch Minecraft with the given session and version.
     */
    public Process launch(Session session, MinecraftVersion version, String... extraArgs)
            throws IOException {

        String javaExecutable = resolveJavaExecutable();
        Path nativesDir = prepareNatives(version);
        List<String> classpath = buildClasspath(version);
        List<String> jvmArgs = buildJvmArgs(session, version, nativesDir);
        List<String> gameArgs = buildGameArgs(session, version);

        // Full command: java [jvm args] -cp [classpath] MC_MAIN_CLASS [game args]
        List<String> command = new ArrayList<>();

        // Java executable
        command.add(javaExecutable);

        // JVM arguments
        command.addAll(jvmArgs);

        // Classpath
        command.add("-cp");
        command.add(String.join(File.pathSeparator, classpath));

        // Main class
        command.add(MC_MAIN_CLASS);

        // Game arguments
        command.addAll(Arrays.asList(extraArgs));
        command.addAll(gameArgs);

        System.out.println("Launching: " + String.join(" ", command));

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(installDir.toFile());
        pb.redirectErrorStream(true);

        return pb.start();
    }

    /**
     * Build the classpath from version libraries.
     */
    private List<String> buildClasspath(MinecraftVersion version) throws IOException {
        List<String> classpath = new ArrayList<>();

        // Add the game jar
        Path versionDir = versionManager.getVersionDir(version.getId());
        Path gameJar = versionDir.resolve(version.getId() + ".jar");
        if (Files.exists(gameJar)) {
            classpath.add(gameJar.toString());
        }

        // Add all libraries from the version manifest
        String manifestJson = Files.readString(
            versionDir.resolve(version.getId() + ".json"));
        com.google.gson.Gson gson = new com.google.gson.Gson();
        JsonObject manifest = gson.fromJson(manifestJson, JsonObject.class);

        if (manifest.has("libraries")) {
            for (com.google.gson.JsonElement libEl : manifest.getAsJsonArray("libraries")) {
                JsonObject lib = libEl.getAsJsonObject();
                if (lib.has("downloads")) {
                    JsonObject downloads = lib.getAsJsonObject("downloads");
                    if (downloads.has("artifact")) {
                        JsonObject artifact = downloads.getAsJsonObject("artifact");
                        if (artifact.has("path")) {
                            Path libPath = installDir.resolve("libraries").resolve(artifact.get("path").getAsString());
                            if (Files.exists(libPath)) {
                                classpath.add(libPath.toString());
                            }
                        }
                    }
                }
            }
        }

        return classpath;
    }

    /**
     * Build JVM arguments for the Minecraft process.
     */
    private List<String> buildJvmArgs(Session session, MinecraftVersion version, Path nativesDir) {
        List<String> args = new ArrayList<>();

        // Memory settings (default 4GB)
        args.add("-Xmx4G");
        args.add("-Xms2G");

        // Point LWJGL/JNA native loading to extracted natives.
        args.add("-Djava.library.path=" + nativesDir.toAbsolutePath());
        args.add("-Djna.tmpdir=" + nativesDir.toAbsolutePath());
        args.add("-Dorg.lwjgl.librarypath=" + nativesDir.toAbsolutePath());

        // Java 17+ module exports needed for modern MC
        args.add("--add-opens=java.base/java.nio=ALL-UNNAMED");
        args.add("--add-opens=java.base/sun.nio.ch=ALL-UNNAMED");
        args.add("--add-opens=java.desktop/com.sun.javafx.tk.quantum=ALL-UNNAMED");

        // Logging disabled for cleaner output
        args.add("-Dfml.ignoreInvalidMinecraftCertificates=true");

        return args;
    }

    /**
     * Find a Java executable suitable for launching Minecraft.
     */
    private String resolveJavaExecutable() throws IOException {
        String candidate = findJavaExecutable();
        if (candidate != null && isJavaVersionAtLeast(candidate, REQUIRED_JAVA_VERSION)) {
            return candidate;
        }

        Path bundledRuntime = installDir.getParent().resolve("runtime");
        if (!hasValidBundledRuntime(bundledRuntime)) {
            downloadBundledRuntime(bundledRuntime);
        }

        String bundledJava = findJavaInRuntime(bundledRuntime);
        if (bundledJava != null && isJavaVersionAtLeast(bundledJava, REQUIRED_JAVA_VERSION)) {
            return bundledJava;
        }

        throw new IOException("A Java " + REQUIRED_JAVA_VERSION + "+ runtime is required to launch Minecraft. " +
                "Please install a newer JDK or set BLEN_JAVA_HOME/JAVA_HOME to a Java " + REQUIRED_JAVA_VERSION + "+ runtime.");
    }

    private boolean isJavaVersionAtLeast(String javaExecutable, int requiredVersion) throws IOException {
        String versionLine = getJavaVersionLine(javaExecutable);
        String parsed = parseJavaVersion(versionLine);
        if (parsed == null) {
            return false;
        }
        try {
            return Integer.parseInt(parsed) >= requiredVersion;
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    private String getJavaVersionLine(String javaExecutable) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(javaExecutable, "-version");
        builder.redirectErrorStream(true);
        Process process = builder.start();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            return reader.readLine();
        }
    }

    private boolean hasValidBundledRuntime(Path runtimeDir) {
        if (!Files.exists(runtimeDir)) {
            return false;
        }
        try {
            String java = findJavaInRuntime(runtimeDir);
            return java != null && isJavaVersionAtLeast(java, REQUIRED_JAVA_VERSION);
        } catch (IOException ex) {
            return false;
        }
    }

    private String findJavaInRuntime(Path runtimeDir) throws IOException {
        String exe = isWindows() ? "java.exe" : "java";
        Path candidate = runtimeDir.resolve("bin").resolve(exe);
        if (Files.exists(candidate) && Files.isRegularFile(candidate)) {
            return candidate.toString();
        }

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(runtimeDir)) {
            for (Path child : stream) {
                if (Files.isDirectory(child)) {
                    Path nested = child.resolve("bin").resolve(exe);
                    if (Files.exists(nested) && Files.isRegularFile(nested)) {
                        return nested.toString();
                    }
                }
            }
        }
        return null;
    }

    private void downloadBundledRuntime(Path runtimeDir) throws IOException {
        if (!Files.exists(runtimeDir)) {
            Files.createDirectories(runtimeDir);
        }

        Path zipPath = runtimeDir.resolveSibling("jdk25.zip");
        try (InputStream in = new URL(JDK_DOWNLOAD_URL).openStream()) {
            Files.copy(in, zipPath, StandardCopyOption.REPLACE_EXISTING);
        }

        unzip(zipPath, runtimeDir);
        Files.deleteIfExists(zipPath);
    }

    private void unzip(Path zipFile, Path targetDir) throws IOException {
        try (ZipInputStream zipStream = new ZipInputStream(Files.newInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = zipStream.getNextEntry()) != null) {
                Path outPath = targetDir.resolve(entry.getName());
                if (entry.isDirectory()) {
                    Files.createDirectories(outPath);
                } else {
                    Files.createDirectories(outPath.getParent());
                    try (OutputStream out = Files.newOutputStream(outPath)) {
                        byte[] buffer = new byte[8192];
                        int len;
                        while ((len = zipStream.read(buffer)) > 0) {
                            out.write(buffer, 0, len);
                        }
                    }
                }
                zipStream.closeEntry();
            }
        }
    }

    private String findJavaExecutable() {
        List<String> candidates = new ArrayList<>();
        String exe = isWindows() ? "java.exe" : "java";
        String blenJavaHome = System.getenv("BLEN_JAVA_HOME");
        if (blenJavaHome != null && !blenJavaHome.isBlank()) {
            candidates.add(Paths.get(blenJavaHome, "bin", exe).toString());
        }
        String envJavaHome = System.getenv("JAVA_HOME");
        if (envJavaHome != null && !envJavaHome.isBlank()) {
            candidates.add(Paths.get(envJavaHome, "bin", exe).toString());
        }
        Path runtimeDir = installDir.getParent().resolve("runtime");
        try {
            String runtimeJava = findJavaInRuntime(runtimeDir);
            if (runtimeJava != null) {
                candidates.add(runtimeJava);
            }
        } catch (IOException ignored) {
        }
        String systemJavaHome = System.getProperty("java.home");
        if (systemJavaHome != null && !systemJavaHome.isBlank()) {
            candidates.add(Paths.get(systemJavaHome, "bin", exe).toString());
        }
        candidates.add(exe);

        for (String candidate : candidates) {
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            Path path = Paths.get(candidate);
            if (Files.exists(path) && Files.isExecutable(path)) {
                return path.toString();
            }
        }
        return null;
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private String parseJavaVersion(String versionLine) {
        if (versionLine == null) {
            return null;
        }
        // Example: openjdk version "25.0.1" 2025-09-17
        int firstQuote = versionLine.indexOf('"');
        int secondQuote = firstQuote >= 0 ? versionLine.indexOf('"', firstQuote + 1) : -1;
        if (firstQuote >= 0 && secondQuote > firstQuote) {
            String v = versionLine.substring(firstQuote + 1, secondQuote);
            if (v.startsWith("1.")) {
                String[] parts = v.split("\\.");
                if (parts.length >= 2) {
                    return parts[1];
                }
            } else {
                String[] parts = v.split("\\.");
                if (parts.length > 0) {
                    return parts[0];
                }
            }
        }
        return null;
    }

    /**
     * Build game arguments (username, version, etc.).
     */
    private List<String> buildGameArgs(Session session, MinecraftVersion version) {
        List<String> args = new ArrayList<>();

        // Required by net.minecraft.client.main.Main
        args.add("--username");
        args.add(session.getUsername());

        args.add("--version");
        args.add(version.getId());

        args.add("--versionType");
        args.add("blen");

        args.add("--uuid");
        args.add(session.getUuid());

        args.add("--accessToken");
        args.add("0");

        args.add("--userType");
        args.add("legacy");

        args.add("--gameDir");
        args.add(installDir.toAbsolutePath().toString());

        // Optional: add --assetIndex (use manifest asset index id, e.g. "26")
        String assetIndexId = resolveAssetIndexId(version);
        if (assetIndexId != null) {
            args.add("--assetIndex");
            args.add(assetIndexId);
        }

        args.add("--assetsDir");
        args.add(installDir.resolve("assets").toAbsolutePath().toString());

        return args;
    }

    private String resolveAssetIndexId(MinecraftVersion version) {
        try {
            Path versionDir = versionManager.getVersionDir(version.getId());
            Path manifestPath = versionDir.resolve(version.getId() + ".json");
            if (!Files.exists(manifestPath)) {
                return null;
            }
            JsonObject manifest = new com.google.gson.Gson().fromJson(Files.readString(manifestPath), JsonObject.class);
            if (!manifest.has("assetIndex")) {
                return null;
            }
            JsonObject assetIndex = manifest.getAsJsonObject("assetIndex");
            return assetIndex.has("id") ? assetIndex.get("id").getAsString() : null;
        } catch (Exception ex) {
            return null;
        }
    }

    private Path prepareNatives(MinecraftVersion version) throws IOException {
        Path versionDir = versionManager.getVersionDir(version.getId());
        String manifestJson = Files.readString(versionDir.resolve(version.getId() + ".json"));
        com.google.gson.Gson gson = new com.google.gson.Gson();
        JsonObject manifest = gson.fromJson(manifestJson, JsonObject.class);

        Path nativesDir = installDir.resolve("natives").resolve(version.getId());
        if (Files.exists(nativesDir)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(nativesDir)) {
                if (stream.iterator().hasNext()) {
                    return nativesDir;
                }
            }
        } else {
            Files.createDirectories(nativesDir);
        }

        if (!manifest.has("libraries")) {
            return nativesDir;
        }

        for (com.google.gson.JsonElement libEl : manifest.getAsJsonArray("libraries")) {
            JsonObject lib = libEl.getAsJsonObject();
            if (!lib.has("downloads")) {
                continue;
            }
            JsonObject downloads = lib.getAsJsonObject("downloads");
            if (!downloads.has("classifiers")) {
                continue;
            }
            JsonObject classifiers = downloads.getAsJsonObject("classifiers");
            String key = classifiers.has("natives-windows") ? "natives-windows"
                    : (classifiers.has("natives-windows-64") ? "natives-windows-64" : null);
            if (key == null) {
                continue;
            }
            JsonObject nativeObj = classifiers.getAsJsonObject(key);
            if (!nativeObj.has("path")) {
                continue;
            }
            Path nativeJar = installDir.resolve("libraries").resolve(nativeObj.get("path").getAsString());
            if (Files.exists(nativeJar)) {
                extractNatives(nativeJar, nativesDir);
            }
        }
        return nativesDir;
    }

    private void extractNatives(Path nativeJar, Path nativesDir) throws IOException {
        try (ZipFile zip = new ZipFile(nativeJar.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName();
                if (name.startsWith("META-INF/")) {
                    continue;
                }
                Path out = nativesDir.resolve(Paths.get(name).getFileName().toString());
                try (InputStream in = zip.getInputStream(entry)) {
                    Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }
}
