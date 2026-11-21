package com.openmc.minecraftwrapper.server.ports;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * One Minecraft server process and the console attached to it, from {@link ServerLauncher}.
 *
 * <p>{@link #isAlive()}, {@link #pid()} and {@link #startedAt()} are safe from any thread. The
 * await methods block. {@link #release()} may be called more than once and from the exit-monitor
 * thread; it must not throw.
 */
public interface RunningServer {

    /** Start the process with {@code command}. @throws IOException if it cannot be started. */
    void start(List<String> command) throws IOException;

    /** Whether the process has been started and has not exited. */
    boolean isAlive();

    /** The OS process id. Only meaningful once started. */
    long pid();

    /** When the process started, if the platform reports it. */
    Optional<Instant> startedAt();

    /** Write one console line to the server's stdin. @throws IOException if the write fails. */
    void send(String line) throws IOException;

    /** Block until the process exits. @return its exit code */
    int awaitExit() throws InterruptedException;

    /** Block up to {@code timeout}. @return {@code true} if the process exited in time */
    boolean awaitExit(Duration timeout) throws InterruptedException;

    /** Ask the process to terminate. */
    void destroy();

    /** Kill the process. */
    void destroyForcibly();

    /** Close and remove the console. Never throws. */
    void release();
}
