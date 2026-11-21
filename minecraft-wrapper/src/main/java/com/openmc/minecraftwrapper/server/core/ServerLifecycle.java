package com.openmc.minecraftwrapper.server.core;

import com.openmc.minecraftwrapper.model.ServerStatus;
import com.openmc.minecraftwrapper.server.model.ServerSettings;
import com.openmc.minecraftwrapper.server.ports.GracefulShutdown;
import com.openmc.minecraftwrapper.server.ports.RunningServer;
import com.openmc.minecraftwrapper.server.ports.ServerAlerts;
import com.openmc.minecraftwrapper.server.ports.ServerLauncher;
import com.openmc.minecraftwrapper.server.ports.Sleeper;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Starts, stops and restarts the Minecraft server, watches for it exiting, alerts on each
 * transition and restarts it after a crash when auto-restart is on.
 *
 * <p>All I/O goes through ports; {@code java.time.Clock} supplies the time.
 */
@Slf4j
public class ServerLifecycle {

    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration RESTART_PAUSE = Duration.ofMillis(2000);
    private static final Duration AUTO_RESTART_DELAY = Duration.ofMillis(5000);

    private final ServerSettings settings;
    private final ServerAlerts alerts;
    private final GracefulShutdown gracefulShutdown;
    private final ServerLauncher launcher;
    private final Sleeper sleeper;
    private final Clock clock;

    /**
     * The most recently prepared server. Deliberately the "current" one rather than per-launch:
     * the exit monitor releases whatever is current when it wakes, exactly as the pre-refactor
     * fields did (see the flagged restart race in the refactor report).
     */
    private volatile RunningServer server;
    private final AtomicBoolean shutdownInProgress = new AtomicBoolean(false);
    private final AtomicBoolean manualStop = new AtomicBoolean(false);

    public ServerLifecycle(ServerSettings settings, ServerAlerts alerts, GracefulShutdown gracefulShutdown,
                           ServerLauncher launcher, Sleeper sleeper, Clock clock) {
        this.settings = settings;
        this.alerts = alerts;
        this.gracefulShutdown = gracefulShutdown;
        this.launcher = launcher;
        this.sleeper = sleeper;
        this.clock = clock;
    }

    /** The current server handle, or {@code null} before the first start. */
    public RunningServer current() {
        return server;
    }

    public void initialize() {
        if (settings.autoStart()) {
            log.info("Auto-start enabled, starting server...");
            try {
                start();
            } catch (Exception e) {
                log.error("Failed to auto-start server", e);
            }
        } else {
            log.info("Auto-start disabled, server will not start automatically");
        }
    }

    /**
     * Start the Minecraft server.
     * Can be called multiple times to restart the server after it's been stopped.
     * @throws IllegalStateException if server is already running
     */
    public synchronized void start() {
        if (server != null && server.isAlive()) {
            throw new IllegalStateException("Server is already running");
        }

        try {
            log.info("Starting Minecraft server with wrapper...");
            log.info("Server JAR: {}", settings.serverJar());
            log.info("Server Directory: {}", settings.serverDirectory());
            log.info("Java Options: {}", settings.javaOpts());

            // Reset flags
            shutdownInProgress.set(false);
            manualStop.set(false);

            server = launcher.prepare(settings.serverDirectory());

            List<String> command = ServerCommandLine.build(settings);

            server.start(command);
            log.info("Minecraft server started with PID: {}", server.pid());

            // Send alert that server has started
            alerts.serverStarted();

            // Monitor server process in a separate thread
            monitorServerProcess();

        } catch (Exception e) {
            log.error("Failed to start Minecraft server", e);
            throw new RuntimeException("Failed to start Minecraft server", e);
        }
    }

    /**
     * Stop the Minecraft server gracefully.
     * Sets the manualStop flag to prevent auto-restart.
     */
    public synchronized void stop() {
        if (server == null || !server.isAlive()) {
            throw new IllegalStateException("Server is not running");
        }

        manualStop.set(true);
        shutdownInProgress.set(true);

        try {
            // Send stop command via FIFO
            sendCommand("stop");

            // Wait for server to shutdown gracefully
            log.info("Waiting for server to stop gracefully...");
            if (!server.awaitExit(STOP_TIMEOUT)) {
                log.warn("Server did not stop in time, forcing termination");
                server.destroyForcibly();
            }

            log.info("Server stopped successfully");
            alerts.serverStopped();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted while waiting for server to stop");
            server.destroyForcibly();
        } catch (IOException e) {
            log.error("Failed to send stop command", e);
            server.destroy();
        } finally {
            cleanupResources();
        }
    }

    /**
     * Restart the Minecraft server.
     * Stops the server if running, then starts it again.
     */
    public synchronized void restart() {
        log.info("Restarting Minecraft server...");

        if (server != null && server.isAlive()) {
            // Set manualStop temporarily to prevent auto-restart during stop
            manualStop.set(true);
            shutdownInProgress.set(true);

            try {
                // Send stop command via FIFO
                sendCommand("stop");

                // Wait for server to shutdown gracefully
                log.info("Waiting for server to stop before restart...");
                if (!server.awaitExit(STOP_TIMEOUT)) {
                    log.warn("Server did not stop in time, forcing termination");
                    server.destroyForcibly();
                }

                log.info("Server stopped, preparing to restart...");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("Interrupted while waiting for server to stop");
                server.destroyForcibly();
            } catch (IOException e) {
                log.error("Failed to send stop command", e);
                server.destroy();
            } finally {
                cleanupResources();
            }
        }

        // Wait a bit before restarting
        try {
            sleeper.sleep(RESTART_PAUSE);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Now start the server
        start();
    }

    private void cleanupResources() {
        RunningServer current = server;
        if (current != null) {
            current.release();
        }
    }

    private void monitorServerProcess() {
        Thread monitorThread = new Thread(() -> {
            try {
                int exitCode = server.awaitExit();
                log.info("Minecraft server process exited with code: {}", exitCode);

                // Cleanup resources after process ends
                cleanupResources();

                // Only send alerts if not in shutdown process
                if (!shutdownInProgress.get()) {
                    if (exitCode == 0) {
                        alerts.serverStopped();
                    } else {
                        alerts.serverCrashed(exitCode);
                    }

                    // Auto-restart if enabled and not a manual stop
                    if (settings.autoRestart() && !manualStop.get()) {
                        log.info("Auto-restart enabled, restarting server in 5 seconds...");
                        try {
                            sleeper.sleep(AUTO_RESTART_DELAY);
                            start();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            log.error("Auto-restart interrupted");
                        } catch (Exception e) {
                            log.error("Failed to auto-restart server", e);
                        }
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.debug("Server monitor thread interrupted");
            }
        });
        monitorThread.setDaemon(true);
        monitorThread.start();
    }

    /** Stop the server through the player-warning countdown, if it is running; always release the console. */
    public void shutdown() {
        if (server != null && server.isAlive()) {
            manualStop.set(true);
            shutdownInProgress.set(true);

            gracefulShutdown.perform(() -> {
                try {
                    // Send stop command via FIFO
                    sendCommand("stop");

                    // Wait for server to shutdown gracefully
                    log.info("Waiting for server to shutdown gracefully...");
                    if (!server.awaitExit(STOP_TIMEOUT)) {
                        log.warn("Server did not shutdown in time, forcing termination");
                        server.destroyForcibly();
                    }

                    log.info("Server shutdown gracefully");
                    alerts.serverStopped();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.error("Interrupted while waiting for server shutdown");
                    server.destroyForcibly();
                } catch (IOException e) {
                    log.error("Failed to send stop command", e);
                    server.destroy();
                }
            });
        }

        cleanupResources();
    }

    public void sendCommand(String command) throws IOException {
        if (server == null || !server.isAlive()) {
            throw new IllegalStateException("Server is not running");
        }

        server.send(command);
        log.info("Command sent to server: {}", command);
    }

    public ServerStatus getStatus() {
        RunningServer current = server;
        boolean running = current != null && current.isAlive();
        Long uptimeSeconds = null;
        String startedAt = null;
        if (running) {
            Optional<Instant> startOpt = current.startedAt();
            if (startOpt.isPresent()) {
                Instant start = startOpt.get();
                uptimeSeconds = Duration.between(start, clock.instant()).getSeconds();
                startedAt = start.toString();
            }
        }
        return ServerStatus.builder()
                .running(running)
                .pid(running ? current.pid() : null)
                .serverJar(settings.serverJar())
                .serverDirectory(settings.serverDirectory())
                .uptimeSeconds(uptimeSeconds)
                .startedAt(startedAt)
                .build();
    }
}
