package com.blen.core.launch;

import com.blen.core.auth.Session;
import com.blen.core.performance.SmoothEngine;
import com.blen.core.version.MinecraftVersion;
import com.blen.core.version.VersionManager;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.*;
import java.net.URI;
import java.net.URL;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * Builds the classpath and launches Minecraft as a separate JVM process.
 * Handles JVM arguments, native libraries, game arguments, and smooth optimizations.
 */
public class GameLauncher {
    private static final String DEFAULT_MC_MAIN_CLASS = "net.minecraft.client.main.Main";
    private static final String JDK_DOWNLOAD_URL = "https://api.adoptium.net/v3/binary/latest/21/ga/windows/x64/jdk/hotspot/normal/eclipse";

    private final Path installDir;
    private final VersionManager versionManager;
    private final SmoothEngine smoothEngine;
    private final Gson gson = new Gson();

    public GameLauncher(Path installDir, VersionManager versionManager) {
        this.installDir = installDir;
        this.versionManager = versionManager;
        this.smoothEngine = new SmoothEngine(installDir);
    }

    /**
     * Launch Minecraft with default 4GB RAM and smooth optimizations enabled.
     */
    public Process launch(Session session, MinecraftVersion version, String... extraArgs) throws IOException {
        return launch(session, version, 4, true, extraArgs);
    }

    /**
     * Launch Minecraft with custom RAM and performance tuning.
     */
    public Process launch(Session session, MinecraftVersion version, int ramGigabytes, boolean smoothOptimization, String... extraArgs)
            throws IOException {

        int requiredJava = getRequiredJavaVersion(version);
        String javaExecutable = resolveJavaExecutable(requiredJava);
        Path nativesDir = prepareNatives(version);
        List<String> classpath = buildClasspath(version);
        List<String> jvmArgs = buildJvmArgs(session, version, nativesDir, ramGigabytes, smoothOptimization);
        List<String> gameArgs = buildGameArgs(session, version);
        String mainClass = resolveMainClass(version);

        if (smoothOptimization) {
            smoothEngine.optimizeGameOptions();
        }

        // Full command: java [jvm args] -cp [classpath] MAIN_CLASS [game args]
        List<String> command = new ArrayList<>();
        command.add(javaExecutable);
        command.addAll(jvmArgs);
        command.add("-cp");
        command.add(String.join(File.pathSeparator, classpath));
        command.add(mainClass);
        command.addAll(Arrays.asList(extraArgs));
        command.addAll(gameArgs);

        System.out.println("Launching: " + String.join(" ", command));

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(installDir.toFile());
        pb.redirectErrorStream(true);

        return pb.start();
    }

    /**
     * Determine the required Java major version from the version manifest.
     */
    public int getRequiredJavaVersion(MinecraftVersion version) {
        try {
            Path versionDir = versionManager.getVersionDir(version.getId());
            Path manifestPath = versionDir.resolve(version.getId() + ".json");
            if (Files.exists(manifestPath)) {
                JsonObject manifest = gson.fromJson(Files.readString(manifestPath), JsonObject.class);
                if (manifest.has("javaVersion") && manifest.getAsJsonObject("javaVersion").has("majorVersion")) {
                    return manifest.getAsJsonObject("javaVersion").get("majorVersion").getAsInt();
                }
                if (manifest.has("inheritsFrom")) {
                    String parentId = manifest.get("inheritsFrom").getAsString();
                    Path parentManifest = versionManager.getVersionDir(parentId).resolve(parentId + ".json");
                    if (Files.exists(parentManifest)) {
                        JsonObject pMan = gson.fromJson(Files.readString(parentManifest), JsonObject.class);
                        if (pMan.has("javaVersion") && pMan.getAsJsonObject("javaVersion").has("majorVersion")) {
                            return pMan.getAsJsonObject("javaVersion").get("majorVersion").getAsInt();
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return 21; // Default to modern LTS
    }

    private String resolveMainClass(MinecraftVersion version) {
        try {
            Path versionDir = versionManager.getVersionDir(version.getId());
            Path manifestPath = versionDir.resolve(version.getId() + ".json");
            if (Files.exists(manifestPath)) {
                JsonObject manifest = gson.fromJson(Files.readString(manifestPath), JsonObject.class);
                if (manifest.has("mainClass")) {
                    return manifest.get("mainClass").getAsString();
                }
                if (manifest.has("inheritsFrom")) {
                    String parentId = manifest.get("inheritsFrom").getAsString();
                    Path parentManifest = versionManager.getVersionDir(parentId).resolve(parentId + ".json");
                    if (Files.exists(parentManifest)) {
                        JsonObject pMan = gson.fromJson(Files.readString(parentManifest), JsonObject.class);
                        if (pMan.has("mainClass")) {
                            return pMan.get("mainClass").getAsString();
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return DEFAULT_MC_MAIN_CLASS;
    }

    /**
     * Build the classpath from version libraries and parent version libraries if inherited.
     */
    private List<String> buildClasspath(MinecraftVersion version) throws IOException {
        List<String> classpath = new ArrayList<>();
        Path versionDir = versionManager.getVersionDir(version.getId());
        Path gameJar = versionDir.resolve(version.getId() + ".jar");
        if (Files.exists(gameJar)) {
            classpath.add(gameJar.toString());
        }

        Path manifestPath = versionDir.resolve(version.getId() + ".json");
        if (!Files.exists(manifestPath)) {
            return classpath;
        }

        JsonObject manifest = gson.fromJson(Files.readString(manifestPath), JsonObject.class);

        // If inheriting (e.g. Fabric), append parent game jar and libraries first
        if (manifest.has("inheritsFrom")) {
            String parentId = manifest.get("inheritsFrom").getAsString();
            Path parentJar = versionManager.getVersionDir(parentId).resolve(parentId + ".jar");
            if (Files.exists(parentJar) && !classpath.contains(parentJar.toString())) {
                classpath.add(parentJar.toString());
            }

            Path parentManifest = versionManager.getVersionDir(parentId).resolve(parentId + ".json");
            if (Files.exists(parentManifest)) {
                JsonObject pMan = gson.fromJson(Files.readString(parentManifest), JsonObject.class);
                appendLibrariesToClasspath(pMan, classpath);
            }
        }

        appendLibrariesToClasspath(manifest, classpath);
        return classpath;
    }

    private void appendLibrariesToClasspath(JsonObject manifest, List<String> classpath) {
        if (!manifest.has("libraries")) return;

        for (JsonElement libEl : manifest.getAsJsonArray("libraries")) {
            JsonObject lib = libEl.getAsJsonObject();

            if (!VersionManager.isLibraryAllowed(lib)) {
                continue;
            }

            if (lib.has("downloads")) {
                JsonObject downloads = lib.getAsJsonObject("downloads");
                if (downloads.has("artifact")) {
                    JsonObject artifact = downloads.getAsJsonObject("artifact");
                    if (artifact.has("path")) {
                        Path libPath = installDir.resolve("libraries").resolve(artifact.get("path").getAsString());
                        if (Files.exists(libPath) && !classpath.contains(libPath.toString())) {
                            classpath.add(libPath.toString());
                        }
                    }
                }
            } else if (lib.has("name")) {
                String name = lib.get("name").getAsString();
                String relPath = VersionManager.mavenNameToPath(name);
                if (relPath != null) {
                    Path libPath = installDir.resolve("libraries").resolve(relPath);
                    if (Files.exists(libPath) && !classpath.contains(libPath.toString())) {
                        classpath.add(libPath.toString());
                    }
                }
            }
        }
    }

    /**
     * Build JVM arguments for the Minecraft process with performance flags.
     */
    private List<String> buildJvmArgs(Session session, MinecraftVersion version, Path nativesDir, int ramGigabytes, boolean smoothOptimization) {
        List<String> args = new ArrayList<>();

        if (smoothOptimization) {
            args.addAll(smoothEngine.getOptimizedJvmArgs(ramGigabytes));
        } else {
            args.add("-Xmx" + ramGigabytes + "G");
            args.add("-Xms1G");
        }

        // Point LWJGL/JNA native loading to extracted natives
        args.add("-Djava.library.path=" + nativesDir.toAbsolutePath());
        args.add("-Djna.tmpdir=" + nativesDir.toAbsolutePath());
        args.add("-Dorg.lwjgl.librarypath=" + nativesDir.toAbsolutePath());

        // Java module opens needed for modern MC & Fabric
        args.add("--add-opens=java.base/java.nio=ALL-UNNAMED");
        args.add("--add-opens=java.base/sun.nio.ch=ALL-UNNAMED");
        args.add("--add-opens=java.base/java.lang=ALL-UNNAMED");
        args.add("--add-opens=java.base/java.lang.reflect=ALL-UNNAMED");
        args.add("--add-opens=java.base/java.util=ALL-UNNAMED");
        args.add("--add-opens=java.desktop/com.sun.javafx.tk.quantum=ALL-UNNAMED");

        // Fabric & launcher identifiers
        args.add("-DFabricMcEmu= net.minecraft.client.main.Main ");
        args.add("-Dfml.ignoreInvalidMinecraftCertificates=true");

        return args;
    }

    /**
     * Find a Java executable suitable for launching Minecraft.
     */
    private String resolveJavaExecutable(int requiredVersion) throws IOException {
        String candidate = findJavaExecutable();
        if (candidate != null && isJavaVersionAtLeast(candidate, requiredVersion)) {
            return candidate;
        }

        Path bundledRuntime = installDir.getParent().resolve("runtime");
        if (!hasValidBundledRuntime(bundledRuntime, requiredVersion)) {
            downloadBundledRuntime(bundledRuntime);
        }

        String bundledJava = findJavaInRuntime(bundledRuntime);
        if (bundledJava != null && isJavaVersionAtLeast(bundledJava, requiredVersion)) {
            return bundledJava;
        }

        // If candidate exists even if slightly different major version, use candidate rather than crashing
        if (candidate != null) {
            return candidate;
        }

        throw new IOException("A Java " + requiredVersion + "+ runtime is required to launch Minecraft. " +
                "Please install JDK 21+ or set BLEN_JAVA_HOME/JAVA_HOME.");
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

    private boolean hasValidBundledRuntime(Path runtimeDir, int requiredVersion) {
        if (!Files.exists(runtimeDir)) {
            return false;
        }
        try {
            String java = findJavaInRuntime(runtimeDir);
            return java != null && isJavaVersionAtLeast(java, requiredVersion);
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

        Path zipPath = runtimeDir.resolveSibling("jdk21.zip");
        try (InputStream in = URI.create(JDK_DOWNLOAD_URL).toURL().openStream()) {
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
                    try (OutputStream out = Files.newOutputStream(outPath, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
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

        // Check common Oracle Java path on Windows
        Path oraclePath = Paths.get("C:\\Program Files\\Common Files\\Oracle\\Java\\javapath", exe);
        if (Files.exists(oraclePath)) {
            candidates.add(oraclePath.toString());
        }

        // Check PATH directories
        String pathEnv = System.getenv("PATH");
        if (pathEnv != null) {
            for (String dir : pathEnv.split(File.pathSeparator)) {
                if (!dir.isBlank()) {
                    try {
                        Path p = Paths.get(dir, exe);
                        if (Files.exists(p) && Files.isRegularFile(p)) {
                            candidates.add(p.toString());
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
        }

        candidates.add(exe);

        for (String candidate : candidates) {
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            try {
                Path path = Paths.get(candidate);
                if (Files.exists(path) && (Files.isExecutable(path) || Files.isRegularFile(path))) {
                    return path.toString();
                }
            } catch (Exception ignored) {
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
            JsonObject manifest = gson.fromJson(Files.readString(manifestPath), JsonObject.class);
            if (manifest.has("assetIndex") && manifest.getAsJsonObject("assetIndex").has("id")) {
                return manifest.getAsJsonObject("assetIndex").get("id").getAsString();
            }
            if (manifest.has("inheritsFrom")) {
                String parentId = manifest.get("inheritsFrom").getAsString();
                Path parentManifest = versionManager.getVersionDir(parentId).resolve(parentId + ".json");
                if (Files.exists(parentManifest)) {
                    JsonObject pMan = gson.fromJson(Files.readString(parentManifest), JsonObject.class);
                    if (pMan.has("assetIndex") && pMan.getAsJsonObject("assetIndex").has("id")) {
                        return pMan.getAsJsonObject("assetIndex").get("id").getAsString();
                    }
                }
            }
        } catch (Exception ex) {
            return null;
        }
        return null;
    }

    private Path prepareNatives(MinecraftVersion version) throws IOException {
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

        Path versionDir = versionManager.getVersionDir(version.getId());
        Path manifestPath = versionDir.resolve(version.getId() + ".json");
        if (!Files.exists(manifestPath)) {
            return nativesDir;
        }

        JsonObject manifest = gson.fromJson(Files.readString(manifestPath), JsonObject.class);
        extractNativesFromManifest(manifest, nativesDir);

        if (manifest.has("inheritsFrom")) {
            String parentId = manifest.get("inheritsFrom").getAsString();
            Path parentManifest = versionManager.getVersionDir(parentId).resolve(parentId + ".json");
            if (Files.exists(parentManifest)) {
                JsonObject pMan = gson.fromJson(Files.readString(parentManifest), JsonObject.class);
                extractNativesFromManifest(pMan, nativesDir);
            }
        }

        return nativesDir;
    }

    private void extractNativesFromManifest(JsonObject manifest, Path nativesDir) {
        if (!manifest.has("libraries")) return;

        for (JsonElement libEl : manifest.getAsJsonArray("libraries")) {
            JsonObject lib = libEl.getAsJsonObject();
            if (!VersionManager.isLibraryAllowed(lib)) continue;

            if (lib.has("downloads")) {
                JsonObject downloads = lib.getAsJsonObject("downloads");

                // Classifiers (older MC natives)
                if (downloads.has("classifiers")) {
                    JsonObject classifiers = downloads.getAsJsonObject("classifiers");
                    String[] keys = {"natives-windows", "natives-windows-64", "natives-windows-x86_64"};
                    for (String key : keys) {
                        if (classifiers.has(key)) {
                            JsonObject nativeObj = classifiers.getAsJsonObject(key);
                            if (nativeObj.has("path")) {
                                Path nativeJar = installDir.resolve("libraries").resolve(nativeObj.get("path").getAsString());
                                if (Files.exists(nativeJar)) {
                                    try { extractNatives(nativeJar, nativesDir); } catch (Exception ignored) {}
                                }
                            }
                        }
                    }
                }

                // Modern LWJGL artifact natives (e.g. natives-windows.jar)
                if (downloads.has("artifact")) {
                    JsonObject artifact = downloads.getAsJsonObject("artifact");
                    if (artifact.has("path")) {
                        String path = artifact.get("path").getAsString();
                        if (path.contains("natives-windows")) {
                            Path nativeJar = installDir.resolve("libraries").resolve(path);
                            if (Files.exists(nativeJar)) {
                                try { extractNatives(nativeJar, nativesDir); } catch (Exception ignored) {}
                            }
                        }
                    }
                }
            }
        }
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
                if (name.startsWith("META-INF/") || !name.endsWith(".dll")) {
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
