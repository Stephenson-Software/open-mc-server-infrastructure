package com.openmc.minecraftwrapper.server.adapters;

import com.openmc.minecraftwrapper.server.fakes.FakeServerLog;
import com.openmc.minecraftwrapper.server.ports.ServerLog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The {@link ServerLog} contract, run against the production adapter and the fake. */
@DisplayName("ServerLog contract")
class ServerLogContractTest {

    abstract static class Contract {
        /** A log holding {@code lines}, or no log at all when {@code lines} is null. */
        abstract ServerLog logWith(List<String> lines) throws IOException;

        @Test
        void missingLogIsEmpty() throws IOException {
            assertEquals(List.of(), logWith(null).tail(10));
        }

        @Test
        void shortLogComesBackWhole() throws IOException {
            assertEquals(List.of("a", "b"), logWith(List.of("a", "b")).tail(10));
        }

        @Test
        void tailKeepsTheLastLinesOldestFirst() throws IOException {
            assertEquals(List.of("c", "d"), logWith(List.of("a", "b", "c", "d")).tail(2));
        }

        @Test
        void nonPositiveMaxLinesIsEmpty() throws IOException {
            ServerLog log = logWith(List.of("a"));
            assertEquals(List.of(), log.tail(0));
            assertEquals(List.of(), log.tail(-3));
        }
    }

    @Nested
    @DisplayName("LatestLogFile")
    class LatestLogFileContract extends Contract {
        @TempDir
        Path dir;

        @Override
        ServerLog logWith(List<String> lines) throws IOException {
            if (lines != null) {
                Path file = Files.createDirectories(dir.resolve("logs")).resolve("latest.log");
                Files.write(file, lines);
            }
            return new LatestLogFile(dir.toString());
        }
    }

    @Nested
    @DisplayName("FakeServerLog")
    class FakeServerLogContract extends Contract {
        @Override
        ServerLog logWith(List<String> lines) {
            FakeServerLog log = new FakeServerLog();
            log.lines = lines == null ? List.of() : lines;
            return log;
        }
    }
}
