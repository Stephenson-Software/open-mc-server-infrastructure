package com.openmc.minecraftwrapper.server.adapters;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ProcfsMemoryProbe")
class ProcfsMemoryProbeTest {

    @TempDir
    Path proc;

    private ProcfsMemoryProbe probeWithStatus(long pid, String status) throws IOException {
        Path dir = Files.createDirectories(proc.resolve(String.valueOf(pid)));
        Files.writeString(dir.resolve("status"), status);
        return new ProcfsMemoryProbe(proc);
    }

    @Test
    void readsVmRssInMib() throws IOException {
        assertEquals(12L, probeWithStatus(7, "Name:\tjava\nVmRSS:\t   12800 kB\nThreads:\t40\n").residentMb(7));
    }

    @Test
    void missingProcessIsNull() {
        assertNull(new ProcfsMemoryProbe(proc).residentMb(99));
    }

    @Test
    void noVmRssLineIsNull() throws IOException {
        assertNull(probeWithStatus(8, "Name:\tzombie\nState:\tZ\n").residentMb(8));
    }

    @Test
    void malformedVmRssIsNull() throws IOException {
        assertNull(probeWithStatus(9, "VmRSS:\tlots kB\n").residentMb(9));
        assertNull(probeWithStatus(10, "VmRSS:\n").residentMb(10));
    }
}
