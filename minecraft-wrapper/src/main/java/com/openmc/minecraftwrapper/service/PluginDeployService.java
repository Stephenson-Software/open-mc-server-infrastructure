package com.openmc.minecraftwrapper.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Slf4j
@Service
public class PluginDeployService {

    // Maximum file size for plugin upload (100MB) to prevent memory issues
    private static final long MAX_FILE_SIZE = 100 * 1024 * 1024;

    // Maximum number of ZIP entries to inspect before giving up (zip-bomb mitigation)
    private static final int MAX_ZIP_ENTRIES = 500;

    // ZIP magic bytes: PK\x03\x04
    private static final byte[] ZIP_MAGIC = {0x50, 0x4B, 0x03, 0x04};

    // Written by reconcile_plugin_selection in resources/post-create.sh at container start.
    static final String APPLIED_REPORT_FILENAME = ".omcsi-applied.json";

    @Value("${plugins.directory:/mcserver/plugins}")
    private String pluginsDirectory;

    /**
     * The plugin selection report from the last container start, as the JSON the
     * boot script wrote, or empty when no selection has been reconciled.
     *
     * @throws IOException if the report exists but cannot be read
     */
    public Optional<String> readAppliedReport() throws IOException {
        Path report = Paths.get(pluginsDirectory).resolve(APPLIED_REPORT_FILENAME);
        if (!Files.isRegularFile(report)) {
            return Optional.empty();
        }
        return Optional.of(Files.readString(report));
    }

    /**
     * Deploy a plugin JAR with the provided file, creating it if it does not exist or
     * replacing it if it does.
     *
     * @param pluginName the filename of the plugin JAR to deploy (e.g. {@code MyPlugin.jar})
     * @param file       the JAR file to deploy
     * @throws IllegalArgumentException if {@code pluginName} or {@code file} fails validation
     * @throws IOException              if the file cannot be written to the plugins directory
     */
    public void replacePlugin(String pluginName, MultipartFile file) throws IOException {
        validatePluginName(pluginName);
        validateFile(file);

        // Sanitize filename to prevent directory traversal
        String sanitizedName = new File(pluginName).getName();

        Path pluginsDirPath = Paths.get(pluginsDirectory).toAbsolutePath().normalize();
        Path targetPath = pluginsDirPath.resolve(sanitizedName).normalize();

        // Ensure the resolved target stays within the plugins directory
        if (!targetPath.getParent().equals(pluginsDirPath)) {
            log.warn("Rejected plugin deploy with out-of-bounds target path: {}", targetPath);
            throw new IllegalArgumentException("Invalid plugin name");
        }

        if (!Files.exists(pluginsDirPath)) {
            throw new IOException("Plugins directory does not exist: " + pluginsDirectory);
        }

        // Validate JAR by streaming (avoids loading entire file into heap)
        try (InputStream is = file.getInputStream()) {
            if (!isValidJarStream(is)) {
                throw new IllegalArgumentException("Uploaded file is not a valid JAR");
            }
        }

        // Write to a unique temp file first, then move into place.
        // Using a unique name avoids races between concurrent deploys of the same plugin.
        Path tempPath = Files.createTempFile(pluginsDirPath, sanitizedName, ".tmp");
        try {
            try (InputStream is = file.getInputStream()) {
                Files.copy(is, tempPath, StandardCopyOption.REPLACE_EXISTING);
            }
            moveToTarget(tempPath, targetPath);
            log.info("Plugin deployed successfully: {}", sanitizedName);
        } catch (IOException e) {
            // Clean up temp file if the move failed
            try {
                Files.deleteIfExists(tempPath);
            } catch (IOException ignored) {
                log.debug("Could not delete temp file: {}", tempPath);
            }
            throw e;
        }
    }

    /**
     * Move {@code source} to {@code target}, attempting an atomic move first and
     * falling back to a plain {@code REPLACE_EXISTING} move if the filesystem does
     * not support atomic moves (common in Docker volume mounts).
     */
    private void moveToTarget(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            log.debug("Atomic move not supported, falling back to regular move: {}", e.getMessage());
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void validatePluginName(String pluginName) {
        if (pluginName == null || pluginName.trim().isEmpty()) {
            throw new IllegalArgumentException("Plugin name must not be empty");
        }
        if (!pluginName.endsWith(".jar")) {
            throw new IllegalArgumentException("Plugin name must end with .jar");
        }
        if (pluginName.contains("/") || pluginName.contains("\\") || pluginName.contains("..")) {
            throw new IllegalArgumentException("Plugin name must not contain path separators or '..'");
        }
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("File must not be empty");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            log.warn("Plugin deploy rejected: file too large ({} bytes)", file.getSize());
            throw new IllegalArgumentException("File size exceeds the 100 MB limit");
        }
    }

    /**
     * Validate that the stream is a JAR file by:
     * <ol>
     *   <li>Checking the ZIP magic bytes first (fast reject).</li>
     *   <li>Scanning ZIP entries for {@code META-INF/MANIFEST.MF}, up to
     *       {@value #MAX_ZIP_ENTRIES} entries (zip-bomb mitigation).</li>
     * </ol>
     *
     * @param inputStream an open, positioned-at-start stream for the uploaded file
     */
    private boolean isValidJarStream(InputStream inputStream) {
        try {
            // Read first 4 bytes to check ZIP magic
            byte[] magic = inputStream.readNBytes(4);
            if (magic.length < 4) {
                return false;
            }
            for (int i = 0; i < ZIP_MAGIC.length; i++) {
                if (magic[i] != ZIP_MAGIC[i]) {
                    return false;
                }
            }

            // Wrap in a ZipInputStream that continues reading from the same stream.
            // Since readNBytes consumed 4 bytes we use a combined stream.
            InputStream combined = new java.io.SequenceInputStream(
                    new java.io.ByteArrayInputStream(magic), inputStream);
            try (ZipInputStream zis = new ZipInputStream(combined)) {
                ZipEntry entry;
                int entryCount = 0;
                while ((entry = zis.getNextEntry()) != null) {
                    if (++entryCount > MAX_ZIP_ENTRIES) {
                        log.warn("JAR validation aborted: too many ZIP entries");
                        return false;
                    }
                    if ("META-INF/MANIFEST.MF".equals(entry.getName())) {
                        return true;
                    }
                }
            }
            return false;
        } catch (Exception e) {
            log.warn("JAR file validation failed: {}", e.getMessage());
            return false;
        }
    }
}
