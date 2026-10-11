package com.openmc.minecraftwrapper.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("PluginDeployService Tests")
class PluginDeployServiceTest {

    @TempDir
    Path tempDir;

    private PluginDeployService pluginDeployService;

    @BeforeEach
    void setUp() {
        pluginDeployService = new PluginDeployService();
        ReflectionTestUtils.setField(pluginDeployService, "pluginsDirectory", tempDir.toString());
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Build a minimal valid JAR (ZIP with META-INF/MANIFEST.MF). */
    private byte[] buildValidJar() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            zos.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
            zos.write("Manifest-Version: 1.0\n".getBytes());
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("com/example/Plugin.class"));
            zos.write(new byte[]{(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE});
            zos.closeEntry();
        }
        return baos.toByteArray();
    }

    private MockMultipartFile validJarFile(String filename) throws IOException {
        return new MockMultipartFile("file", filename, "application/java-archive", buildValidJar());
    }

    // ── replacePlugin – happy path ────────────────────────────────────────────

    @Test
    @DisplayName("Should replace an existing plugin JAR successfully")
    void shouldReplaceExistingPluginJar() throws Exception {
        // Arrange: create a pre-existing plugin file
        Path existingPlugin = tempDir.resolve("MyPlugin.jar");
        Files.write(existingPlugin, "old content".getBytes());

        MockMultipartFile newJar = validJarFile("MyPlugin.jar");

        // Act
        pluginDeployService.replacePlugin("MyPlugin.jar", newJar);

        // Assert: file was replaced with new content
        assertTrue(Files.exists(existingPlugin));
        assertArrayEquals(newJar.getBytes(), Files.readAllBytes(existingPlugin));
    }

    @Test
    @DisplayName("Should deploy a plugin JAR that does not yet exist")
    void shouldDeployNewPlugin() throws Exception {
        MockMultipartFile newJar = validJarFile("NewPlugin.jar");

        pluginDeployService.replacePlugin("NewPlugin.jar", newJar);

        Path deployed = tempDir.resolve("NewPlugin.jar");
        assertTrue(Files.exists(deployed));
        assertArrayEquals(newJar.getBytes(), Files.readAllBytes(deployed));
    }

    // ── replacePlugin – validation errors ────────────────────────────────────

    @Test
    @DisplayName("Should reject null plugin name")
    void shouldRejectNullPluginName() {
        MockMultipartFile file = new MockMultipartFile("file", "plugin.jar",
                "application/java-archive", new byte[1]);

        assertThrows(IllegalArgumentException.class,
                () -> pluginDeployService.replacePlugin(null, file));
    }

    @Test
    @DisplayName("Should reject empty plugin name")
    void shouldRejectEmptyPluginName() {
        MockMultipartFile file = new MockMultipartFile("file", "plugin.jar",
                "application/java-archive", new byte[1]);

        assertThrows(IllegalArgumentException.class,
                () -> pluginDeployService.replacePlugin("  ", file));
    }

    @Test
    @DisplayName("Should reject plugin name without .jar extension")
    void shouldRejectNonJarExtension() {
        MockMultipartFile file = new MockMultipartFile("file", "plugin.zip",
                "application/octet-stream", new byte[1]);

        assertThrows(IllegalArgumentException.class,
                () -> pluginDeployService.replacePlugin("plugin.zip", file));
    }

    @Test
    @DisplayName("Should reject plugin name with path separator")
    void shouldRejectPluginNameWithPathSeparator() throws IOException {
        MockMultipartFile file = validJarFile("evil.jar");

        assertThrows(IllegalArgumentException.class,
                () -> pluginDeployService.replacePlugin("sub/evil.jar", file));
    }

    @Test
    @DisplayName("Should reject plugin name containing '..'")
    void shouldRejectPluginNameWithDotDot() throws IOException {
        MockMultipartFile file = validJarFile("evil.jar");

        assertThrows(IllegalArgumentException.class,
                () -> pluginDeployService.replacePlugin("..evil.jar", file));
    }

    @Test
    @DisplayName("Should reject empty file")
    void shouldRejectEmptyFile() {
        MockMultipartFile emptyFile = new MockMultipartFile("file", "plugin.jar",
                "application/java-archive", new byte[0]);

        assertThrows(IllegalArgumentException.class,
                () -> pluginDeployService.replacePlugin("plugin.jar", emptyFile));
    }

    @Test
    @DisplayName("Should reject file that is not a valid JAR")
    void shouldRejectInvalidJar() {
        MockMultipartFile notAJar = new MockMultipartFile("file", "plugin.jar",
                "application/java-archive", "not a jar".getBytes());

        assertThrows(IllegalArgumentException.class,
                () -> pluginDeployService.replacePlugin("plugin.jar", notAJar));
    }

    @Test
    @DisplayName("Should throw IOException when plugins directory does not exist")
    void shouldThrowWhenPluginsDirMissing() throws IOException {
        ReflectionTestUtils.setField(pluginDeployService, "pluginsDirectory",
                tempDir.resolve("nonexistent").toString());

        MockMultipartFile newJar = validJarFile("MyPlugin.jar");

        assertThrows(IOException.class,
                () -> pluginDeployService.replacePlugin("MyPlugin.jar", newJar));
    }

    // ── readAppliedReport ─────────────────────────────────────────────────────

    @Test
    @DisplayName("Should return the plugin selection report written at boot")
    void shouldReturnAppliedReport() throws Exception {
        String report = "{\"selection_sha256\":\"abc\",\"plugins\":{\"fiefs\":\"installed\"}}";
        Files.writeString(tempDir.resolve(".omcsi-applied.json"), report);

        assertEquals(Optional.of(report), pluginDeployService.readAppliedReport());
    }

    @Test
    @DisplayName("Should return empty when no plugin selection has been reconciled")
    void shouldReturnEmptyWithoutAppliedReport() throws Exception {
        assertEquals(Optional.empty(), pluginDeployService.readAppliedReport());
    }
}
