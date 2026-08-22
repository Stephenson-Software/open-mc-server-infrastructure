package com.openmc.minecraftwrapper.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("WorldUploadService Tests")
class WorldUploadServiceTest {

    @TempDir
    Path tempDir;

    @Mock
    private MinecraftServerService minecraftServerService;

    private WorldUploadService worldUploadService;
    private Path worldDir;

    @BeforeEach
    void setUp() {
        worldUploadService = new WorldUploadService(minecraftServerService);
        worldDir = tempDir.resolve("world");
        ReflectionTestUtils.setField(worldUploadService, "worldDirectory", worldDir.toString());
    }

    // ── ZIP helpers ──────────────────────────────────────────────────────────

    /** Build a ZIP containing world contents at the root (flat structure). */
    private byte[] buildFlatWorldZip() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            addEntry(zos, "level.dat", new byte[]{1, 2, 3});
            addEntry(zos, "region/r.0.0.mca", new byte[]{4, 5, 6});
        }
        return baos.toByteArray();
    }

    /** Build a ZIP with a single top-level directory containing world contents (single-dir structure). */
    private byte[] buildSingleDirWorldZip(String dirName) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            addEntry(zos, dirName + "/level.dat", new byte[]{1, 2, 3});
            addEntry(zos, dirName + "/region/r.0.0.mca", new byte[]{4, 5, 6});
        }
        return baos.toByteArray();
    }

    /** Build a ZIP with no level.dat. */
    private byte[] buildNoLevelDatZip() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            addEntry(zos, "region/r.0.0.mca", new byte[]{4, 5, 6});
        }
        return baos.toByteArray();
    }

    /** Build a ZIP with a path-traversal entry. */
    private byte[] buildPathTraversalZip() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            ZipEntry entry = new ZipEntry("../../outside/evil.txt");
            zos.putNextEntry(entry);
            zos.write(new byte[]{1});
            zos.closeEntry();
        }
        return baos.toByteArray();
    }


    private void addEntry(ZipOutputStream zos, String name, byte[] content) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        zos.putNextEntry(entry);
        zos.write(content);
        zos.closeEntry();
    }

    private MockMultipartFile zipFile(byte[] content) {
        return new MockMultipartFile("file", "world.zip", "application/zip", content);
    }

    // ── validateFile ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("replaceWorld rejects null file")
    void rejectsNullFile() {
        assertThrows(IllegalArgumentException.class,
                () -> worldUploadService.replaceWorld(null));
    }

    @Test
    @DisplayName("replaceWorld rejects empty file")
    void rejectsEmptyFile() {
        MockMultipartFile empty = new MockMultipartFile("file", "world.zip", "application/zip", new byte[0]);
        assertThrows(IllegalArgumentException.class,
                () -> worldUploadService.replaceWorld(empty));
    }

    @Test
    @DisplayName("replaceWorld rejects file exceeding the default 2 GB limit")
    void rejectsOversizedFile() {
        MultipartFile bigFile = mock(MultipartFile.class);
        when(bigFile.isEmpty()).thenReturn(false);
        when(bigFile.getSize()).thenReturn(3L * 1024 * 1024 * 1024);

        assertThrows(IllegalArgumentException.class,
                () -> worldUploadService.replaceWorld(bigFile));
    }

    @Test
    @DisplayName("replaceWorld accepts a file over 2 GB once the limit is raised")
    void acceptsOversizedFileWhenLimitRaised() throws IOException {
        ReflectionTestUtils.setField(worldUploadService, "maxFileSizeMb", 5120L);

        MultipartFile bigFile = mock(MultipartFile.class);
        when(bigFile.isEmpty()).thenReturn(false);
        when(bigFile.getSize()).thenReturn(3L * 1024 * 1024 * 1024);
        when(bigFile.getInputStream()).thenReturn(new java.io.ByteArrayInputStream(buildFlatWorldZip()));

        worldUploadService.replaceWorld(bigFile);

        assertTrue(Files.exists(worldDir.resolve("level.dat")));
    }

    @Test
    @DisplayName("oversized-file message names the limit and how to raise it")
    void oversizedFileMessageGuidesRecovery() {
        MultipartFile bigFile = mock(MultipartFile.class);
        when(bigFile.isEmpty()).thenReturn(false);
        when(bigFile.getSize()).thenReturn(3L * 1024 * 1024 * 1024);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> worldUploadService.replaceWorld(bigFile));

        assertTrue(e.getMessage().contains("2048 MB"), e.getMessage());
        assertTrue(e.getMessage().contains("WORLD_UPLOAD_MAX_FILE_SIZE_MB"), e.getMessage());
    }

    @Test
    @DisplayName("replaceWorld rejects an archive that expands past the extracted-size limit")
    void rejectsArchiveOverExtractedLimit() throws IOException {
        ReflectionTestUtils.setField(worldUploadService, "maxExtractedMb", 0L);

        assertThrows(IllegalArgumentException.class,
                () -> worldUploadService.replaceWorld(zipFile(buildFlatWorldZip())));
    }

    @Test
    @DisplayName("replaceWorld rejects an archive with more entries than allowed")
    void rejectsArchiveOverEntryLimit() throws IOException {
        ReflectionTestUtils.setField(worldUploadService, "maxZipEntries", 1);

        assertThrows(IllegalArgumentException.class,
                () -> worldUploadService.replaceWorld(zipFile(buildFlatWorldZip())));
    }

    // ── extractZip — security guards ─────────────────────────────────────────

    @Test
    @DisplayName("replaceWorld rejects ZIP with path-traversal entry")
    void rejectsPathTraversalEntry() throws IOException {
        assertThrows(IllegalArgumentException.class,
                () -> worldUploadService.replaceWorld(zipFile(buildPathTraversalZip())));
    }


    @Test
    @DisplayName("replaceWorld rejects archive without level.dat")
    void rejectsArchiveWithoutLevelDat() throws IOException {
        assertThrows(IllegalArgumentException.class,
                () -> worldUploadService.replaceWorld(zipFile(buildNoLevelDatZip())));
    }

    // ── detectWorldRoot ───────────────────────────────────────────────────────

    @Test
    @DisplayName("detectWorldRoot returns single top-level dir containing level.dat")
    void detectWorldRootReturnsSingleDir() throws IOException {
        Path extractDir = tempDir.resolve("extract");
        Files.createDirectory(extractDir);
        Path worldSubDir = extractDir.resolve("myworld");
        Files.createDirectory(worldSubDir);
        Files.createFile(worldSubDir.resolve("level.dat"));

        Path result = worldUploadService.detectWorldRoot(extractDir);

        assertEquals(worldSubDir, result);
    }

    @Test
    @DisplayName("detectWorldRoot returns extract dir when level.dat is at root")
    void detectWorldRootReturnsFlatDir() throws IOException {
        Path extractDir = tempDir.resolve("extract");
        Files.createDirectory(extractDir);
        Files.createFile(extractDir.resolve("level.dat"));

        Path result = worldUploadService.detectWorldRoot(extractDir);

        assertEquals(extractDir, result);
    }

    @Test
    @DisplayName("detectWorldRoot returns extract dir when multiple top-level entries exist")
    void detectWorldRootReturnsExtractDirForMultipleEntries() throws IOException {
        Path extractDir = tempDir.resolve("extract");
        Files.createDirectory(extractDir);
        Files.createFile(extractDir.resolve("level.dat"));
        Files.createDirectory(extractDir.resolve("region"));

        Path result = worldUploadService.detectWorldRoot(extractDir);

        assertEquals(extractDir, result);
    }

    @Test
    @DisplayName("detectWorldRoot ignores __MACOSX directory")
    void detectWorldRootIgnoresMacOsxDir() throws IOException {
        Path extractDir = tempDir.resolve("extract");
        Files.createDirectory(extractDir);
        Path worldSubDir = extractDir.resolve("myworld");
        Files.createDirectory(worldSubDir);
        Files.createFile(worldSubDir.resolve("level.dat"));
        Files.createDirectory(extractDir.resolve("__MACOSX"));

        Path result = worldUploadService.detectWorldRoot(extractDir);

        assertEquals(worldSubDir, result);
    }

    // ── replaceWorld — success paths ─────────────────────────────────────────

    @Test
    @DisplayName("replaceWorld installs flat-structure archive")
    void installsFlatStructureArchive() throws IOException {
        worldUploadService.replaceWorld(zipFile(buildFlatWorldZip()));

        assertTrue(Files.exists(worldDir), "World directory should exist after install");
        assertTrue(Files.exists(worldDir.resolve("level.dat")), "level.dat should be present");
        assertTrue(Files.exists(worldDir.resolve("region/r.0.0.mca")), "Region file should be present");
    }

    @Test
    @DisplayName("replaceWorld installs single-dir archive")
    void installsSingleDirArchive() throws IOException {
        worldUploadService.replaceWorld(zipFile(buildSingleDirWorldZip("myworld")));

        assertTrue(Files.exists(worldDir), "World directory should exist after install");
        assertTrue(Files.exists(worldDir.resolve("level.dat")), "level.dat should be present");
    }

    @Test
    @DisplayName("replaceWorld replaces existing world directory")
    void replacesExistingWorld() throws IOException {
        Files.createDirectory(worldDir);
        Files.createFile(worldDir.resolve("old_file.dat"));

        worldUploadService.replaceWorld(zipFile(buildFlatWorldZip()));

        assertTrue(Files.exists(worldDir.resolve("level.dat")), "New level.dat should be present");
        assertFalse(Files.exists(worldDir.resolve("old_file.dat")), "Old file should be gone");
    }

    @Test
    @DisplayName("replaceWorld cleans up the .old backup after successful install")
    void cleansUpBackupAfterSuccess() throws IOException {
        Files.createDirectory(worldDir);
        Files.createFile(worldDir.resolve("level.dat"));

        worldUploadService.replaceWorld(zipFile(buildFlatWorldZip()));

        Path backup = worldDir.getParent().resolve(worldDir.getFileName() + ".old");
        assertFalse(Files.exists(backup), "Backup directory should be cleaned up after success");
    }

    // ── createStagingDirectory ───────────────────────────────────────────────
    //
    // The staging directory must live on the same filesystem as the world directory:
    // installWorld finishes with Files.move, which cannot rename a non-empty directory
    // across filesystems. On both deployment targets /tmp and the world volume are
    // separate filesystems, so staging under java.io.tmpdir breaks every real upload.

    @Test
    @DisplayName("createStagingDirectory creates the staging dir alongside the world directory")
    void stagingDirectoryIsSiblingOfWorldDirectory() throws IOException {
        Path staging = worldUploadService.createStagingDirectory(worldDir);

        assertEquals(worldDir.getParent(), staging.getParent(),
                "Staging dir must share a parent (and therefore a filesystem) with the world dir");
        assertTrue(Files.isDirectory(staging), "Staging dir should have been created");
    }

    @Test
    @DisplayName("createStagingDirectory does not stage under java.io.tmpdir")
    void stagingDirectoryIsNotUnderSystemTempDir() throws IOException {
        Path systemTmp = Paths.get(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
        Path isolatedWorldDir = Files.createDirectory(tempDir.resolve("mcserver")).resolve("world");
        ReflectionTestUtils.setField(worldUploadService, "worldDirectory", isolatedWorldDir.toString());

        Path staging = worldUploadService.createStagingDirectory(isolatedWorldDir).toAbsolutePath().normalize();

        assertNotEquals(systemTmp, staging.getParent(),
                "Staging dir must not be created directly under java.io.tmpdir");
        assertEquals(isolatedWorldDir.getParent(), staging.getParent());
    }

    @Test
    @DisplayName("createStagingDirectory creates the world's parent directory when it is missing")
    void stagingDirectoryCreatesMissingParent() throws IOException {
        Path missingParentWorld = tempDir.resolve("not-yet-created").resolve("world");

        Path staging = worldUploadService.createStagingDirectory(missingParentWorld);

        assertTrue(Files.isDirectory(missingParentWorld.getParent()), "Parent should have been created");
        assertEquals(missingParentWorld.getParent(), staging.getParent());
    }

    @Test
    @DisplayName("replaceWorld leaves no staging directory behind on success")
    void leavesNoStagingDirectoryOnSuccess() throws IOException {
        worldUploadService.replaceWorld(zipFile(buildSingleDirWorldZip("myworld")));

        assertEquals(List.of("world"), listDirNames(tempDir),
                "Only the installed world should remain next to it");
    }

    @Test
    @DisplayName("replaceWorld leaves no staging directory behind on failure")
    void leavesNoStagingDirectoryOnFailure() throws IOException {
        assertThrows(IllegalArgumentException.class,
                () -> worldUploadService.replaceWorld(zipFile(buildNoLevelDatZip())));

        assertEquals(List.of(), listDirNames(tempDir),
                "A failed upload should not leave a staging directory behind");
    }

    /** Sorted names of the directories directly under {@code dir}. */
    private List<String> listDirNames(Path dir) throws IOException {
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .sorted()
                    .toList();
        }
    }

    // ── replaceWorld — server lifecycle ──────────────────────────────────────

    @Test
    @DisplayName("replaceWorld stops and restarts server around installation")
    void stopsAndRestartsServer() throws IOException {
        worldUploadService.replaceWorld(zipFile(buildFlatWorldZip()));

        verify(minecraftServerService, times(1)).stop();
        verify(minecraftServerService, times(1)).start();
    }

    @Test
    @DisplayName("replaceWorld restarts server even when archive is invalid")
    void restartsServerEvenOnFailure() throws IOException {
        assertThrows(IllegalArgumentException.class,
                () -> worldUploadService.replaceWorld(zipFile(buildNoLevelDatZip())));

        verify(minecraftServerService, times(1)).stop();
        verify(minecraftServerService, times(1)).start();
    }

    @Test
    @DisplayName("replaceWorld proceeds without stopping when server is already stopped")
    void proceedsWhenServerAlreadyStopped() throws IOException {
        doThrow(new IllegalStateException("Server not running")).when(minecraftServerService).stop();

        // Should not throw — server-not-running is a handled case
        assertDoesNotThrow(() -> worldUploadService.replaceWorld(zipFile(buildFlatWorldZip())));

        // start() should NOT be called if stop() indicated server wasn't running
        verify(minecraftServerService, never()).start();
    }
}
