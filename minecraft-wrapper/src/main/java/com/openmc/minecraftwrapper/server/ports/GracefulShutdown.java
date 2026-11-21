package com.openmc.minecraftwrapper.server.ports;

/**
 * Warns players before the wrapper shuts the server down, then runs {@code stopAction}.
 * Blocks for the whole countdown; called once, from the wrapper's shutdown hook.
 */
public interface GracefulShutdown {

    void perform(Runnable stopAction);
}
