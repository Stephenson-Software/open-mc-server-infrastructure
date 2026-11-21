package com.openmc.minecraftwrapper.server.adapters;

import com.openmc.minecraftwrapper.server.ports.Sleeper;

import java.time.Duration;

/** Pauses with {@link Thread#sleep(long)}. */
public final class ThreadSleeper implements Sleeper {

    @Override
    public void sleep(Duration duration) throws InterruptedException {
        Thread.sleep(duration.toMillis());
    }
}
