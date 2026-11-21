package com.openmc.minecraftwrapper.server.ports;

import java.io.IOException;

/**
 * Prepares a Minecraft server to run in a directory: checks the directory and attaches a console
 * the wrapper can write commands to. The returned server is not started yet.
 *
 * <p>Blocks briefly (creating the console). Called only from the lifecycle's synchronized
 * {@code start()}.
 */
public interface ServerLauncher {

    /**
     * @param serverDirectory the configured server directory, exactly as configured
     * @return a prepared, not-yet-started server bound to that directory
     * @throws IllegalStateException if the directory does not exist (misconfiguration)
     * @throws IOException           if the console cannot be created
     * @throws InterruptedException  if interrupted while creating the console
     */
    RunningServer prepare(String serverDirectory) throws IOException, InterruptedException;
}
