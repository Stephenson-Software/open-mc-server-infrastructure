package com.openmc.minecraftwrapper.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Slf4j
@Service
public class WorldUploadService {

    private static final long BYTES_PER_MB = 1024L * 1024L;

    @Value("${world.directory:/mcserver/world}")
    private String worldDirectory;

    /**
     * Largest accepted archive, in MB. Must be raised together with the multipart limits in
     * front of it — {@code spring.servlet.multipart.max-file-size} here, the web app's own
     * multipart limit, and nginx's {@code client_max_body_size} — since the smallest of those
     * is what a client actually hits first.
     */
    @Value("${world.upload.max-file-size-mb:2048}")
    private long maxFileSizeMb = 2048;

    /**
     * Cap on the total extracted size, in MB — zip bomb protection. A world archive expands
     * well past its compressed size (region files are dense NBT), so this needs headroom of
     * several times {@link #maxFileSizeMb}, and the volume needs room for the result.
     */
    @Value("${world.upload.max-extracted-mb:10240}")
    private long maxExtractedMb = 10240;

    /** Cap on the number of entries in the archive — zip bomb protection. */
    @Value("${world.upload.max-entries:100000}")
    private int maxZipEntries = 100_000;

    private final MinecraftServerService minecraftServerService;

    public WorldUploadService(MinecraftServerService minecraftServerService) {
        this.minecraftServerService = minecraftServerService;
    }

    /**
     * Stop the server (if running), replace the world directory with the contents of the
     * uploaded ZIP archive, then restart the server.
     *
     * <p>The ZIP may contain either the world folder itself (containing {@code level.dat})
     * as a single top-level directory, or the world contents directly at the root of the archive.
     * Both structures are handled automatically.
     *
     * <p>The existing world is renamed aside before installation. If the move fails, the old
     * world is restored so the server is never left without a world directory.
     *
     * @param file the ZIP archive to upload
     * @throws IllegalArgumentException if the file or archive contents fail validation
     * @throws IOException              if extraction or directory manipulation fails
     */
    public void replaceWorld(MultipartFile file) throws IOException {
        validateFile(file);

        Path worldDir = Paths.get(worldDirectory).toAbsolutePath().normalize();

        boolean wasRunning = false;
        try {
            minecraftServerService.stop();
            wasRunning = true;
            log.info("Server stopped for world replacement");
        } catch (IllegalStateException e) {
            log.info("Server was not running before world upload; proceeding without stop");
        }

        Path stagingDir = null;
        try {
            stagingDir = createStagingDirectory(worldDir);

            // Single stream open — ZipInputStream validates the ZIP format itself;
            // a non-ZIP payload will throw ZipException from getNextEntry().
            try (InputStream is = file.getInputStream();
                 ZipInputStream zis = new ZipInputStream(is)) {
                extractZip(zis, stagingDir);
            }

            Path worldRoot = detectWorldRoot(stagingDir);

            if (!Files.exists(worldRoot.resolve("level.dat"))) {
                throw new IllegalArgumentException(
                        "Archive does not contain a valid Minecraft world (level.dat not found)");
            }

            installWorld(worldRoot, worldDir);
            log.info("World replaced successfully at {}", worldDir);
        } finally {
            if (stagingDir != null) {
                try {
                    if (Files.exists(stagingDir)) {
                        deleteDirectory(stagingDir);
                    }
                } catch (IOException e) {
                    log.debug("Could not clean up staging directory {}: {}", stagingDir, e.getMessage());
                }
            }
            if (wasRunning) {
                try {
                    minecraftServerService.start();
                    log.info("Server restarted after world upload");
                } catch (Exception e) {
                    log.error("Failed to restart server after world upload", e);
                }
            }
        }
    }

    /**
     * Create the directory the uploaded archive is extracted into.
     *
     * <p>The staging directory is deliberately a sibling of the world directory rather than a
     * {@code java.io.tmpdir} temp directory. {@link #installWorld} finishes by renaming the
     * extracted world into place, and {@link Files#move} cannot rename a non-empty directory
     * across filesystems — it fails instead of falling back to copy-and-delete. On both
     * deployment targets {@code /tmp} and the world volume are separate filesystems (an
     * {@code emptyDir} vs. the {@code mcserver} PVC on Kubernetes; the container's writable
     * layer vs. the {@code mcserver} named volume under Docker Compose), so staging under
     * {@code /tmp} would make every upload of a real world fail. Staging on the world volume
     * also keeps up to {@link #MAX_UNCOMPRESSED_BYTES} of extracted data off the node's local disk.
     *
     * <p>The name is dot-prefixed so the transient directory is visually distinguishable from a
     * real world; it exists only for the duration of a single upload, during which the server is
     * stopped.
     *
     * @param worldDir absolute, normalized path of the world directory
     * @return a newly created, empty staging directory on the same filesystem as {@code worldDir}
     * @throws IOException if the parent directory cannot be created or the staging directory cannot be made
     */
    Path createStagingDirectory(Path worldDir) throws IOException {
        Path parent = worldDir.getParent();
        if (parent == null) {
            throw new IOException("World directory '" + worldDir
                    + "' has no parent directory to stage the upload in");
        }
        Files.createDirectories(parent);
        return Files.createTempDirectory(parent, ".world-upload-");
    }

    /**
     * Install {@code worldRoot} as the new world directory using a rename-aside strategy:
     * the existing world (if any) is moved to a backup path first. On success the backup is
     * deleted; on failure the backup is restored so the server is never left without a world.
     */
    private void installWorld(Path worldRoot, Path worldDir) throws IOException {
        Path backup = worldDir.getParent().resolve(worldDir.getFileName() + ".old");

        // Move existing world aside
        if (Files.exists(worldDir)) {
            if (Files.exists(backup)) {
                deleteDirectory(backup);
            }
            Files.move(worldDir, backup);
        }

        try {
            Files.move(worldRoot, worldDir, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException moveEx) {
            // Restore old world so the server is not left without a world directory
            if (Files.exists(backup)) {
                try {
                    Files.move(backup, worldDir, StandardCopyOption.REPLACE_EXISTING);
                    log.info("World backup restored after failed installation");
                } catch (IOException restoreEx) {
                    log.error("Failed to restore world backup from {} to {}: {}",
                            backup, worldDir, restoreEx.getMessage());
                }
            }
            throw moveEx;
        }

        // Installation succeeded — clean up backup
        if (Files.exists(backup)) {
            try {
                deleteDirectory(backup);
            } catch (IOException e) {
                log.warn("Could not delete world backup at {}; manual cleanup may be needed: {}",
                        backup, e.getMessage());
            }
        }
    }

    /**
     * If the extracted archive has a single top-level directory containing {@code level.dat},
     * treat that directory as the world root. Otherwise treat the extraction directory itself
     * as the world root (the archive contained world contents at the top level).
     */
    Path detectWorldRoot(Path extractDir) throws IOException {
        try (Stream<Path> entries = Files.list(extractDir)) {
            List<Path> topLevel = entries
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .filter(p -> !p.getFileName().toString().equals("__MACOSX"))
                    .toList();
            if (topLevel.size() == 1
                    && Files.isDirectory(topLevel.get(0))
                    && Files.exists(topLevel.get(0).resolve("level.dat"))) {
                return topLevel.get(0);
            }
        }
        return extractDir;
    }

    private void extractZip(ZipInputStream zis, Path targetDir) throws IOException {
        ZipEntry entry;
        int entryCount = 0;
        long totalBytes = 0;

        while ((entry = zis.getNextEntry()) != null) {
            if (++entryCount > maxZipEntries) {
                throw new IllegalArgumentException(String.format(
                        "Archive has more than %d entries (zip bomb protection). Raise "
                                + "WORLD_UPLOAD_MAX_ENTRIES if the world legitimately has this many files.",
                        maxZipEntries));
            }

            String name = entry.getName();
            if (name.startsWith("__MACOSX/")) {
                zis.closeEntry();
                continue;
            }

            Path target = targetDir.resolve(name).normalize();
            if (!target.startsWith(targetDir)) {
                throw new IllegalArgumentException("Archive contains a path traversal entry: " + name);
            }

            if (entry.isDirectory()) {
                Files.createDirectories(target);
            } else {
                if (target.getParent() != null) {
                    Files.createDirectories(target.getParent());
                }
                long written = Files.copy(zis, target, StandardCopyOption.REPLACE_EXISTING);
                totalBytes += written;
                if (totalBytes > maxExtractedMb * BYTES_PER_MB) {
                    throw new IllegalArgumentException(String.format(
                            "Archive expands to more than %d MB (zip bomb protection). Raise "
                                    + "WORLD_UPLOAD_MAX_EXTRACTED_MB, and check the world volume has "
                                    + "room for both the old and new world at once.",
                            maxExtractedMb));
                }
            }
            zis.closeEntry();
        }
    }

    private void deleteDirectory(Path dir) throws IOException {
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                if (exc != null) throw exc;
                Files.delete(d);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("File must not be empty");
        }
        long maxBytes = maxFileSizeMb * BYTES_PER_MB;
        if (file.getSize() > maxBytes) {
            throw new IllegalArgumentException(String.format(
                    "Archive is %d MB, over the %d MB limit. Raise WORLD_UPLOAD_MAX_FILE_SIZE_MB "
                            + "(and the multipart and nginx limits in front of it) to accept it.",
                    file.getSize() / BYTES_PER_MB, maxFileSizeMb));
        }
    }
}
