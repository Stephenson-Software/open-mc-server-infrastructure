package com.openmc.minecraftwrapper.server.ports;

import java.time.Duration;

/** Pauses the calling thread. Blocks; thread-safe. */
public interface Sleeper {

    /** @throws InterruptedException if interrupted while paused */
    void sleep(Duration duration) throws InterruptedException;
}
