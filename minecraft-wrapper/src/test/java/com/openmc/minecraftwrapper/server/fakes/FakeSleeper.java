package com.openmc.minecraftwrapper.server.fakes;

import com.openmc.minecraftwrapper.server.ports.Sleeper;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Records each pause and returns at once. */
public final class FakeSleeper implements Sleeper {

    public final List<Duration> sleeps = new CopyOnWriteArrayList<>();

    @Override
    public void sleep(Duration duration) {
        sleeps.add(duration);
    }
}
