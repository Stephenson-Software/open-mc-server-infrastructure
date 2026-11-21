package com.openmc.minecraftwrapper.server.fakes;

import com.openmc.minecraftwrapper.server.ports.GracefulShutdown;

/** Skips the countdown and runs the stop action at once. */
public final class FakeGracefulShutdown implements GracefulShutdown {

    public volatile int performed;

    @Override
    public void perform(Runnable stopAction) {
        performed++;
        stopAction.run();
    }
}
