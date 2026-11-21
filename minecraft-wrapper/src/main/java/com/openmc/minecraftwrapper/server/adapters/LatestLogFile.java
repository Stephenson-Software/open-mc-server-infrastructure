package com.openmc.minecraftwrapper.server.adapters;

import com.openmc.minecraftwrapper.server.ports.ServerLog;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Reads {@code <serverDirectory>/logs/latest.log}.
 *
 * <p>Uses a streaming bounded deque so the whole file is never held in memory, and decodes
 * leniently: {@link InputStreamReader} constructed with a {@link java.nio.charset.Charset}
 * replaces undecodable bytes with U+FFFD instead of throwing. Server logs regularly carry
 * bytes that are not valid UTF-8 (plugin output, player chat), and a strict decoder would
 * abort the read mid-stream with an {@link java.io.UncheckedIOException} and lose the entire
 * tail (issue #245).
 */
@Slf4j
public final class LatestLogFile implements ServerLog {

    private final String serverDirectory;

    public LatestLogFile(String serverDirectory) {
        this.serverDirectory = serverDirectory;
    }

    @Override
    public List<String> tail(int maxLines) {
        Path logFile = Path.of(serverDirectory, "logs", "latest.log");
        if (!Files.exists(logFile)) {
            log.debug("Server log file not found at {}", logFile);
            return List.of();
        }
        // Math.max only keeps the initial capacity legal; a non-positive maxLines still yields an
        // empty list, because every line added below is evicted again on the same iteration.
        Deque<String> tail = new ArrayDeque<>(Math.max(1, maxLines));
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(Files.newInputStream(logFile), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                tail.addLast(line);
                if (tail.size() > maxLines) {
                    tail.pollFirst();
                }
            }
            return new ArrayList<>(tail);
        } catch (IOException e) {
            log.error("Failed to read server log file {}: {}", logFile, e.getMessage());
            return List.of();
        }
    }
}
