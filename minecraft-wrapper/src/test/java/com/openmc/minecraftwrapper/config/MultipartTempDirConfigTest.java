package com.openmc.minecraftwrapper.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("MultipartTempDirConfig Tests")
class MultipartTempDirConfigTest {

    @TempDir
    Path tempDir;

    private MultipartTempDirConfig configWithLocation(String location) {
        MultipartTempDirConfig config = new MultipartTempDirConfig();
        ReflectionTestUtils.setField(config, "multipartLocation", location);
        return config;
    }

    @Test
    @DisplayName("creates the configured directory, including missing parents")
    void createsConfiguredDirectory() {
        Path target = tempDir.resolve("nested/upload-tmp");
        ReflectionTestUtils.invokeMethod(configWithLocation(target.toString()), "createMultipartTempDir");

        assertTrue(Files.isDirectory(target));
    }

    @Test
    @DisplayName("does nothing when no location is configured")
    void skipsWhenUnset() {
        ReflectionTestUtils.invokeMethod(configWithLocation(""), "createMultipartTempDir");
        ReflectionTestUtils.invokeMethod(configWithLocation(null), "createMultipartTempDir");

        assertEquals(0, tempDir.toFile().list().length);
    }

    @Test
    @DisplayName("an uncreatable path is logged rather than aborting startup")
    void doesNotFailStartupWhenDirectoryCannotBeCreated() throws Exception {
        // Parent is a regular file, so createDirectories cannot succeed.
        Path blocker = Files.writeString(tempDir.resolve("blocker"), "x");
        Path unreachable = blocker.resolve("upload-tmp");

        assertDoesNotThrow(() ->
                ReflectionTestUtils.invokeMethod(configWithLocation(unreachable.toString()),
                        "createMultipartTempDir"));

        assertFalse(Files.isDirectory(unreachable));
    }

    @Test
    @DisplayName("an existing directory is left alone rather than failing startup")
    void toleratesExistingDirectory() throws Exception {
        Path target = Files.createDirectory(tempDir.resolve("already-there"));
        Files.writeString(target.resolve("keep.txt"), "x");

        ReflectionTestUtils.invokeMethod(configWithLocation(target.toString()), "createMultipartTempDir");

        assertTrue(Files.exists(target.resolve("keep.txt")));
    }
}
