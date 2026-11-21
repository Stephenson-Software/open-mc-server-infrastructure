package com.openmc.minecraftwrapper.server.ports;

/** Reads how much memory an OS process uses. Thread-safe; may read from disk. */
public interface ProcessMemoryProbe {

    /** Resident set size of {@code pid} in MiB, or {@code null} when unavailable (any platform/error). */
    Long residentMb(long pid);
}
