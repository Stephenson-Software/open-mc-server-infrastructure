package com.openmc.minecraftwrapper.server.adapters;

import com.openmc.minecraftwrapper.server.ports.GracefulShutdown;
import com.openmc.minecraftwrapper.service.ShutdownService;

/** Runs {@link ShutdownService}'s in-game countdown broadcast, then the stop action. */
public final class BroadcastGracefulShutdown implements GracefulShutdown {

    private final ShutdownService shutdownService;

    public BroadcastGracefulShutdown(ShutdownService shutdownService) {
        this.shutdownService = shutdownService;
    }

    @Override
    public void perform(Runnable stopAction) {
        shutdownService.performGracefulShutdown(stopAction);
    }
}
