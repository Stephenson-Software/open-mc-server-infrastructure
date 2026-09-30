package com.openmc.webapp.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("JsonRepository Tests")
class JsonRepositoryTest {

    public static class TestEntity {
        private String name;
        private Instant timestamp;

        public TestEntity() {
        }

        TestEntity(String name, Instant timestamp) {
            this.name = name;
            this.timestamp = timestamp;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public Instant getTimestamp() {
            return timestamp;
        }

        public void setTimestamp(Instant timestamp) {
            this.timestamp = timestamp;
        }
    }

    static class TestEntityRepository extends JsonRepository<TestEntity> {
        TestEntityRepository(String filePath) {
            super(filePath, TestEntity[].class);
        }

        TestEntityRepository(String filePath, Duration retentionPeriod) {
            super(filePath, TestEntity[].class, retentionPeriod);
        }

        @Override
        protected Instant getEntityTimestamp(TestEntity entity) {
            return entity.getTimestamp();
        }
    }

    @Test
    @DisplayName("Should create missing parent directories on construction")
    void shouldCreateMissingParentDirectoriesOnConstruction(@TempDir Path tempDir) {
        Path nestedDir = tempDir.resolve("a").resolve("b");
        assertFalse(Files.exists(nestedDir));

        new TestEntityRepository(nestedDir.resolve("entities.json").toString());

        assertTrue(Files.isDirectory(nestedDir));
    }

    @Test
    @DisplayName("Should return empty list when data file is corrupt")
    void shouldReturnEmptyListWhenDataFileIsCorrupt(@TempDir Path tempDir) throws Exception {
        Path dataFile = tempDir.resolve("entities.json");
        Files.writeString(dataFile, "{ not valid json");
        TestEntityRepository repository = new TestEntityRepository(dataFile.toString());

        List<TestEntity> entities = repository.findAll();

        assertNotNull(entities);
        assertTrue(entities.isEmpty());
        assertEquals("{ not valid json", Files.readString(dataFile));
    }

    @Test
    @DisplayName("Should drop expired entities from the file on save")
    void shouldDropExpiredEntitiesFromTheFileOnSave(@TempDir Path tempDir) {
        String dataFile = tempDir.resolve("entities.json").toString();
        TestEntityRepository shortRetention = new TestEntityRepository(dataFile, Duration.ofHours(1));
        List<TestEntity> entities = List.of(
                new TestEntity("recent", Instant.now().minus(Duration.ofMinutes(10))),
                new TestEntity("expired", Instant.now().minus(Duration.ofHours(2))));

        shortRetention.save(entities);

        // Read back through a repository with a longer retention period, so any
        // entity missing here was never written rather than filtered on load.
        List<TestEntity> onDisk = new TestEntityRepository(dataFile, Duration.ofDays(30)).findAll();
        assertEquals(1, onDisk.size());
        assertEquals("recent", onDisk.get(0).getName());
    }

    @Test
    @DisplayName("Should filter entities on load using the custom retention period")
    void shouldFilterEntitiesOnLoadUsingCustomRetentionPeriod(@TempDir Path tempDir) {
        String dataFile = tempDir.resolve("entities.json").toString();
        new TestEntityRepository(dataFile, Duration.ofDays(30)).save(List.of(
                new TestEntity("recent", Instant.now().minus(Duration.ofMinutes(10))),
                new TestEntity("older", Instant.now().minus(Duration.ofHours(2)))));

        List<TestEntity> loaded = new TestEntityRepository(dataFile, Duration.ofHours(1)).findAll();

        assertEquals(1, loaded.size());
        assertEquals("recent", loaded.get(0).getName());
    }

    @Test
    @DisplayName("Should default the retention period to seven days")
    void shouldDefaultRetentionPeriodToSevenDays(@TempDir Path tempDir) {
        TestEntityRepository repository = new TestEntityRepository(tempDir.resolve("entities.json").toString());

        assertEquals(Duration.ofDays(7), repository.getRetentionPeriod());
    }

    @Test
    @DisplayName("Should leave no temporary files behind after a successful save")
    void shouldLeaveNoTemporaryFilesAfterSuccessfulSave(@TempDir Path tempDir) throws Exception {
        TestEntityRepository repository = new TestEntityRepository(tempDir.resolve("entities.json").toString());

        repository.save(List.of(new TestEntity("one", Instant.now())));
        repository.save(List.of(new TestEntity("two", Instant.now())));

        try (Stream<Path> files = Files.list(tempDir)) {
            assertEquals(List.of(tempDir.resolve("entities.json")), files.toList());
        }
    }

    @Test
    @DisplayName("Should replace existing contents rather than append on save")
    void shouldReplaceExistingContentsOnSave(@TempDir Path tempDir) {
        TestEntityRepository repository = new TestEntityRepository(tempDir.resolve("entities.json").toString());
        repository.save(List.of(new TestEntity("first", Instant.now())));

        repository.save(List.of(new TestEntity("second", Instant.now())));

        List<TestEntity> loaded = repository.findAll();
        assertEquals(1, loaded.size());
        assertEquals("second", loaded.get(0).getName());
    }

    @Test
    @DisplayName("Should write an empty array when saving an empty list")
    void shouldWriteEmptyArrayWhenSavingEmptyList(@TempDir Path tempDir) throws Exception {
        Path dataFile = tempDir.resolve("entities.json");
        TestEntityRepository repository = new TestEntityRepository(dataFile.toString());

        repository.save(new ArrayList<>());

        assertTrue(Files.exists(dataFile));
        assertEquals("[ ]", Files.readString(dataFile).trim());
        assertTrue(repository.findAll().isEmpty());
    }

    @Test
    @DisplayName("Should return a mutable list from findAll")
    void shouldReturnMutableListFromFindAll(@TempDir Path tempDir) {
        TestEntityRepository repository = new TestEntityRepository(tempDir.resolve("entities.json").toString());
        repository.save(List.of(new TestEntity("one", Instant.now())));

        List<TestEntity> loaded = repository.findAll();

        assertDoesNotThrow(() -> loaded.add(new TestEntity("two", Instant.now())));
    }

    @Test
    @DisplayName("Should do nothing when clearing without a data file")
    void shouldDoNothingWhenClearingWithoutDataFile(@TempDir Path tempDir) {
        Path dataFile = tempDir.resolve("entities.json");
        TestEntityRepository repository = new TestEntityRepository(dataFile.toString());

        assertDoesNotThrow(repository::clear);
        assertFalse(Files.exists(dataFile));
    }

    @Test
    @DisplayName("Should expose the absolute data file path")
    void shouldExposeAbsoluteDataFilePath(@TempDir Path tempDir) {
        Path dataFile = tempDir.resolve("entities.json");
        TestEntityRepository repository = new TestEntityRepository(dataFile.toString());

        assertEquals(new File(dataFile.toString()).getAbsolutePath(), repository.getFilePath());
    }
}
