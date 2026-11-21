package com.openmc.minecraftwrapper.server.adapters;

import com.openmc.minecraftwrapper.server.ports.ProcessMemoryProbe;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;

/** Reads VmRSS from {@code /proc/<pid>/status} (Linux only; {@code null} elsewhere). */
@Slf4j
public final class ProcfsMemoryProbe implements ProcessMemoryProbe {

    private final Path procRoot;

    public ProcfsMemoryProbe() {
        this(Path.of("/proc"));
    }

    /** @param procRoot the procfs mount; tests point this at a temp directory */
    public ProcfsMemoryProbe(Path procRoot) {
        this.procRoot = procRoot;
    }

    @Override
    public Long residentMb(long pid) {
        Path statusFile = procRoot.resolve(String.valueOf(pid)).resolve("status");
        if (!Files.exists(statusFile)) {
            return null;
        }
        try (var lines = Files.lines(statusFile)) {
            return lines
                    .filter(l -> l.startsWith("VmRSS:"))
                    .findFirst()
                    .map(line -> {
                        // Format: "VmRSS:     12345 kB"
                        String[] parts = line.trim().split("\\s+");
                        return parts.length >= 2 ? Long.parseLong(parts[1]) / 1024 : null;
                    })
                    .orElse(null);
        } catch (Exception e) {
            log.debug("Could not read process RSS for pid {}: {}", pid, e.getMessage());
            return null;
        }
    }
}
