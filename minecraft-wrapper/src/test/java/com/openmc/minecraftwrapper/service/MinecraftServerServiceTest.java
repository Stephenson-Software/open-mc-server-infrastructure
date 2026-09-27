package com.openmc.minecraftwrapper.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("MinecraftServerService Tests")
class MinecraftServerServiceTest {

    @TempDir
    Path tempDir;

    @Mock
    private AlertService alertService;

    @Mock
    private ShutdownService shutdownService;

    private MinecraftServerService service;
    private Path logFile;

    @BeforeEach
    void setUp() {
        service = new MinecraftServerService(alertService, shutdownService);
        ReflectionTestUtils.setField(service, "serverDirectory", tempDir.toString());
        logFile = tempDir.resolve("logs").resolve("latest.log");
    }

    // ── Log helpers ──────────────────────────────────────────────────────────

    /** Write the supplied lines to {@code logs/latest.log} as UTF-8. */
    private void writeLog(String... lines) throws IOException {
        Files.createDirectories(logFile.getParent());
        Files.writeString(logFile, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
    }

    /** Write raw bytes to {@code logs/latest.log}, bypassing any charset encoding. */
    private void writeLogBytes(byte[] bytes) throws IOException {
        Files.createDirectories(logFile.getParent());
        try (OutputStream out = Files.newOutputStream(logFile)) {
            out.write(bytes);
        }
    }

    /** Invoke the private TPS parser directly — the public path requires a live server process. */
    private String parseTps() {
        return ReflectionTestUtils.invokeMethod(service, "parseTpsFromLogs");
    }

    @Nested
    @DisplayName("getRecentLogLines")
    class GetRecentLogLines {

        @Test
        @DisplayName("returns an empty list when the log file does not exist")
        void returnsEmptyListWhenLogMissing() {
            assertTrue(service.getRecentLogLines(10).isEmpty());
        }

        @Test
        @DisplayName("returns every line, oldest first, when the file is shorter than maxLines")
        void returnsAllLinesWhenFileIsShort() throws IOException {
            writeLog("first", "second", "third");

            assertEquals(List.of("first", "second", "third"), service.getRecentLogLines(10));
        }

        @Test
        @DisplayName("returns only the trailing maxLines lines, oldest first")
        void returnsTailOnly() throws IOException {
            writeLog("one", "two", "three", "four", "five");

            assertEquals(List.of("four", "five"), service.getRecentLogLines(2));
        }

        @Test
        @DisplayName("returns an empty list when maxLines is zero")
        void returnsEmptyListForZeroMaxLines() throws IOException {
            writeLog("one", "two");

            assertTrue(service.getRecentLogLines(0).isEmpty());
        }

        @Test
        @DisplayName("replaces undecodable bytes instead of throwing (issue #245)")
        void survivesNonUtf8Bytes() throws IOException {
            // 0xFF is not valid UTF-8; a strict decoder throws MalformedInputException here
            writeLogBytes(new byte[]{'o', 'k', '\n', (byte) 0xFF, '\n', 'l', 'a', 's', 't', '\n'});

            List<String> lines = assertDoesNotThrow(() -> service.getRecentLogLines(10));

            assertEquals(3, lines.size());
            assertEquals("ok", lines.get(0));
            assertEquals("�", lines.get(1));
            assertEquals("last", lines.get(2));
        }
    }

    @Nested
    @DisplayName("parseTpsFromLogs")
    class ParseTpsFromLogs {

        @Test
        @DisplayName("returns null when the log file does not exist")
        void returnsNullWhenLogMissing() {
            assertNull(parseTps());
        }

        @Test
        @DisplayName("returns null when no TPS line is present")
        void returnsNullWhenNoTpsLine() throws IOException {
            writeLog("[12:00:00 INFO]: Done (1.234s)!", "[12:00:01 INFO]: Player joined");

            assertNull(parseTps());
        }

        @Test
        @DisplayName("returns the TPS segment from the most recent matching line")
        void returnsMostRecentTpsSegment() throws IOException {
            writeLog(
                    "[12:00:00 INFO]: TPS from last 1m, 5m, 15m: 15.00, 15.00, 15.00",
                    "[12:00:01 INFO]: Player joined",
                    "[12:00:02 INFO]: TPS from last 1m, 5m, 15m: 20.00, 19.50, 19.00");

            assertEquals("TPS from last 1m, 5m, 15m: 20.00, 19.50, 19.00", parseTps());
        }

        @Test
        @DisplayName("finds a TPS line that is undecodable elsewhere in the log (issue #245)")
        void survivesNonUtf8Bytes() throws IOException {
            byte[] prefix = "[12:00:00 INFO]: ".getBytes(StandardCharsets.UTF_8);
            byte[] suffix = "\n[12:00:01 INFO]: TPS from last 1m, 5m, 15m: 20.00, 19.50, 19.00\n"
                    .getBytes(StandardCharsets.UTF_8);
            byte[] bytes = new byte[prefix.length + 1 + suffix.length];
            System.arraycopy(prefix, 0, bytes, 0, prefix.length);
            bytes[prefix.length] = (byte) 0xFF;
            System.arraycopy(suffix, 0, bytes, prefix.length + 1, suffix.length);
            writeLogBytes(bytes);

            assertEquals("TPS from last 1m, 5m, 15m: 20.00, 19.50, 19.00",
                    assertDoesNotThrow(MinecraftServerServiceTest.this::parseTps));
        }

        @Test
        @DisplayName("serves the cached reading rather than re-scanning within the TTL")
        void cachesTheParsedReading() throws IOException {
            writeLog("[12:00:00 INFO]: TPS from last 1m, 5m, 15m: 20.00, 19.50, 19.00");
            String first = parseTps();

            writeLog("[12:01:00 INFO]: TPS from last 1m, 5m, 15m: 5.00, 6.00, 7.00");

            assertEquals(first, parseTps());
        }

        @Test
        @DisplayName("caches a missing reading so the log is not re-scanned within the TTL")
        void cachesTheAbsenceOfAReading() throws IOException {
            writeLog("[12:00:00 INFO]: Done (1.234s)!");
            assertNull(parseTps());

            writeLog("[12:01:00 INFO]: TPS from last 1m, 5m, 15m: 20.00, 19.50, 19.00");

            assertNull(parseTps());
        }
    }

    // ── Server command ───────────────────────────────────────────────────────

    @Test
    void serverCommandUsesTheConfiguredJavaAndSplitsTheOptions() {
        ReflectionTestUtils.setField(service, "javaBin", "/usr/lib/jvm/java-17-openjdk-amd64/bin/java");
        ReflectionTestUtils.setField(service, "javaOpts", " -Xmx2G  -Xms1G ");
        ReflectionTestUtils.setField(service, "serverJar", "spigot-1.19.4.jar");

        assertEquals(
                List.of("/usr/lib/jvm/java-17-openjdk-amd64/bin/java", "-Xmx2G", "-Xms1G", "-jar", "spigot-1.19.4.jar", "nogui"),
                service.buildServerCommand());
    }
}
