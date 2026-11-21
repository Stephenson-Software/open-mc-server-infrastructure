package com.openmc.minecraftwrapper.server.model;

/**
 * How the wrapper launches the Minecraft server.
 *
 * @param serverJar       the server jar, relative to {@code serverDirectory}
 * @param serverDirectory the directory the server runs in (holds {@code logs/latest.log})
 * @param javaOpts        JVM options, whitespace-separated
 * @param javaBin         the java the server runs on; older Minecraft versions refuse newer
 *                        JDKs, so the image points this at the JDK its version needs
 * @param autoStart       start the server when the wrapper starts
 * @param autoRestart     start the server again after it exits on its own
 */
public record ServerSettings(
        String serverJar,
        String serverDirectory,
        String javaOpts,
        String javaBin,
        boolean autoStart,
        boolean autoRestart) {
}
