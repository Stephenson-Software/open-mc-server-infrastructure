package com.openmc.minecraftwrapper.server.ports;

import java.util.List;

/** The Minecraft server's current log. Thread-safe; reads may block on disk. */
public interface ServerLog {

    /**
     * The last {@code maxLines} lines, oldest first. Empty when the log is missing or unreadable
     * (both are expected, not errors), and when {@code maxLines} is not positive. Undecodable
     * bytes come back as U+FFFD instead of failing the read.
     */
    List<String> tail(int maxLines);
}
