package com.blen.core;

import com.blen.core.auth.AuthManager;
import com.blen.core.auth.Session;
import com.blen.core.performance.SmoothEngine;
import com.blen.core.version.MinecraftVersion;
import com.blen.core.version.VersionManager;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

public class BlenCoreTest {

    @Test
    void testOfflineUuidMatchesStandardMinecraft() {
        String username = "Player123";
        String expected = UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8)).toString();
        String generated = AuthManager.generateOfflineUuid(username);
        assertEquals(expected, generated, "Offline UUID must match Minecraft's OfflinePlayer UUID algorithm exactly");
    }

    @Test
    void testAuthManagerLoginAndSession(@TempDir Path tempDir) throws IOException {
        AuthManager authManager = new AuthManager(tempDir);
        assertNull(authManager.getCurrentSession());
        assertFalse(authManager.isLoggedIn());

        Session session = authManager.loginCracked("Steve");
        assertNotNull(session);
        assertEquals("Steve", session.getUsername());
        assertTrue(session.isValid());
        assertTrue(authManager.isLoggedIn());

        // Verify persistence
        AuthManager reloadManager = new AuthManager(tempDir);
        Session reloaded = reloadManager.getCurrentSession();
        assertNotNull(reloaded);
        assertEquals("Steve", reloaded.getUsername());

        // Test logout
        reloadManager.logout();
        assertNull(reloadManager.getCurrentSession());
        assertFalse(reloadManager.isLoggedIn());
    }

    @Test
    void testVersionManagerMavenPathResolution() {
        String maven = "net.fabricmc:fabric-loader:0.16.10";
        String expected = "net/fabricmc/fabric-loader/0.16.10/fabric-loader-0.16.10.jar";
        assertEquals(expected, VersionManager.mavenNameToPath(maven));

        String classifierMaven = "org.lwjgl:lwjgl-jemalloc:3.3.3:natives-windows";
        String expectedClassifier = "org/lwjgl/lwjgl-jemalloc/3.3.3/lwjgl-jemalloc-3.3.3-natives-windows.jar";
        assertEquals(expectedClassifier, VersionManager.mavenNameToPath(classifierMaven));
    }

    @Test
    void testVersionManagerLibraryRuleAllowed() {
        // Rule: allow on windows
        String allowJson = """
            {
                "rules": [
                    { "action": "allow", "os": { "name": "windows" } }
                ]
            }
            """;
        JsonObject allowLib = JsonParser.parseString(allowJson).getAsJsonObject();
        assertTrue(VersionManager.isLibraryAllowed(allowLib));

        // Rule: disallow on windows
        String disallowJson = """
            {
                "rules": [
                    { "action": "disallow", "os": { "name": "windows" } }
                ]
            }
            """;
        JsonObject disallowLib = JsonParser.parseString(disallowJson).getAsJsonObject();
        assertFalse(VersionManager.isLibraryAllowed(disallowLib));

        // No rules: default allowed
        JsonObject noRules = new JsonObject();
        assertTrue(VersionManager.isLibraryAllowed(noRules));
    }

    @Test
    void testSmoothEngineJvmArgs(@TempDir Path tempDir) {
        SmoothEngine engine = new SmoothEngine(tempDir);
        List<String> args = engine.getOptimizedJvmArgs(6);

        assertTrue(args.contains("-Xmx6G"), "Should allocate 6GB max heap");
        assertTrue(args.contains("-XX:+UseG1GC"), "Should enable G1GC");
        assertTrue(args.contains("-XX:+AlwaysPreTouch"), "Should enable pre-touch to eliminate page fault lag");
        assertTrue(args.contains("-XX:+ParallelRefProcEnabled"), "Should enable parallel reference processing");
        assertTrue(args.contains("-Dsun.java2d.opengl=true"), "Should enable accelerated OpenGL pipeline");
    }

    @Test
    void testSmoothEngineOptionsOptimization(@TempDir Path tempDir) throws IOException {
        SmoothEngine engine = new SmoothEngine(tempDir);
        engine.optimizeGameOptions();

        Path options = tempDir.resolve("options.txt");
        assertTrue(Files.exists(options));

        String content = Files.readString(options);
        assertTrue(content.contains("enableVsync:false"), "Vsync should be disabled to remove input lag");
        assertTrue(content.contains("maxFps:260"), "Max FPS should be set high for smooth gameplay");
        assertTrue(content.contains("ao:2"), "Smooth lighting should be enabled");
    }

    @Test
    void testSmoothEngineModdableVersions(@TempDir Path tempDir) {
        SmoothEngine engine = new SmoothEngine(tempDir);
        assertTrue(engine.supportsSmoothMods("1.21.4"));
        assertTrue(engine.supportsSmoothMods("1.20.4"));
        assertTrue(engine.supportsSmoothMods("1.16.5"));
        assertTrue(engine.supportsSmoothMods("1.14.4"));

        assertFalse(engine.supportsSmoothMods("1.12.2"));
        assertFalse(engine.supportsSmoothMods("1.8.9"));
        assertFalse(engine.supportsSmoothMods("rd-161348"));
    }

    @Test
    void testFallbackVersionsLoading(@TempDir Path tempDir) throws Exception {
        VersionManager vm = new VersionManager(tempDir);
        List<MinecraftVersion> versions = vm.getAvailableVersions();
        assertNotNull(versions);
        assertFalse(versions.isEmpty());

        // Verify releases are present and sorted
        List<MinecraftVersion> releases = vm.getReleaseVersions();
        assertFalse(releases.isEmpty());
        assertEquals("release", releases.get(0).getType());
    }
}
