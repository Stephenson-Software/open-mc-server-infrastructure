package com.openmc.minecraftwrapper.server.ports;

/**
 * Tells operators about server lifecycle events. Implementations must not throw: a failed alert
 * is logged, never allowed to break a start or stop. May block on the network.
 */
public interface ServerAlerts {

    void serverStarted();

    void serverStopped();

    void serverCrashed(int exitCode);
}
