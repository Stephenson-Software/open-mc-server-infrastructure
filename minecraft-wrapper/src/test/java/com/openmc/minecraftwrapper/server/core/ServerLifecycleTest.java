package com.openmc.minecraftwrapper.server.core;

import com.openmc.minecraftwrapper.model.ServerStatus;
import com.openmc.minecraftwrapper.server.fakes.FakeGracefulShutdown;
import com.openmc.minecraftwrapper.server.fakes.FakeRunningServer;
import com.openmc.minecraftwrapper.server.fakes.FakeServerAlerts;
import com.openmc.minecraftwrapper.server.fakes.FakeServerLauncher;
import com.openmc.minecraftwrapper.server.fakes.FakeSleeper;
import com.openmc.minecraftwrapper.server.fakes.MutableClock;
import com.openmc.minecraftwrapper.server.model.ServerSettings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/** The lifecycle policy on fakes: no processes, no pipes, no real sleeps. */
@DisplayName("ServerLifecycle (fakes)")
class ServerLifecycleTest {

    private final FakeServerLauncher launcher = new FakeServerLauncher();
    private final FakeServerAlerts alerts = new FakeServerAlerts();
    private final FakeGracefulShutdown graceful = new FakeGracefulShutdown();
    private final FakeSleeper sleeper = new FakeSleeper();
    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-20T00:01:30Z"));
    private ServerLifecycle lifecycle;

    private ServerLifecycle build(boolean autoStart, boolean autoRestart) {
        return new ServerLifecycle(
                new ServerSettings("paper.jar", "/mc", " -Xmx4G ", "/jdk/bin/java", autoStart, autoRestart),
                alerts, graceful, launcher, sleeper, clock);
    }

    @BeforeEach
    void setUp() {
        lifecycle = build(false, false);
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() < deadline, "timed out");
            Thread.sleep(5);
        }
    }

    @Test
    @DisplayName("start prepares the configured directory and runs the built command")
    void startRunsCommand() {
        lifecycle.start();

        assertEquals(List.of("/mc"), launcher.directories);
        assertEquals(List.of("/jdk/bin/java", "-Xmx4G", "-jar", "paper.jar", "nogui"), launcher.last().command);
        assertEquals(List.of("started"), alerts.sent);
    }

    @Test
    @DisplayName("status reports pid, uptime from the clock and the ISO start time")
    void statusUsesClock() {
        lifecycle.start();

        ServerStatus status = lifecycle.getStatus();

        assertTrue(status.isRunning());
        assertEquals(1000L, status.getPid());
        assertEquals(90L, status.getUptimeSeconds());
        assertEquals("2026-11-20T00:00:00Z", status.getStartedAt());
    }

    @Test
    @DisplayName("a missing directory surfaces wrapped, with no alert and no launch")
    void missingDirectory() {
        launcher.directoryMissing = true;

        RuntimeException e = assertThrows(RuntimeException.class, lifecycle::start);

        assertEquals("Failed to start Minecraft server", e.getMessage());
        assertInstanceOf(IllegalStateException.class, e.getCause());
        assertTrue(alerts.sent.isEmpty());
    }

    @Test
    @DisplayName("a process that fails to start surfaces wrapped and leaves the server stopped")
    void startFailure() {
        launcher.startFailure = new IOException("no java");

        RuntimeException e = assertThrows(RuntimeException.class, lifecycle::start);

        assertEquals("Failed to start Minecraft server", e.getMessage());
        assertInstanceOf(IOException.class, e.getCause());
        assertFalse(lifecycle.getStatus().isRunning());
        assertTrue(alerts.sent.isEmpty());
    }

    @Test
    @DisplayName("stop sends 'stop', waits, alerts once and releases the console")
    void stop() throws Exception {
        lifecycle.start();
        FakeRunningServer server = launcher.last();

        lifecycle.stop();

        assertEquals(List.of("stop"), server.sent);
        assertFalse(lifecycle.getStatus().isRunning());
        assertTrue(server.releases >= 1);
        Thread.sleep(50); // the exit monitor must not add a second alert
        assertEquals(List.of("started", "stopped"), alerts.sent);
    }

    @Test
    @DisplayName("stop forces termination when the server ignores 'stop' for 30 s")
    void stopTimeoutForces() {
        lifecycle.start();
        FakeRunningServer server = launcher.last();
        server.ignoreStop = true;

        lifecycle.stop();

        assertTrue(server.destroyedForcibly);
        assertTrue(alerts.sent.contains("stopped"));
    }

    @Test
    @DisplayName("stop destroys the process when the console write fails, and sends no stop alert")
    void stopSendFailureDestroys() throws Exception {
        lifecycle.start();
        FakeRunningServer server = launcher.last();
        server.sendFailure = new IOException("broken pipe");

        lifecycle.stop();

        assertTrue(server.destroyed);
        Thread.sleep(50);
        assertEquals(List.of("started"), alerts.sent);
    }

    @Test
    @DisplayName("an unrequested exit 0 alerts stopped; non-zero alerts crashed with the code")
    void monitorAlerts() throws Exception {
        lifecycle.start();
        launcher.last().exit(0);
        await(() -> alerts.sent.size() == 2);
        assertEquals("stopped", alerts.sent.get(1));

        lifecycle.start();
        launcher.last().exit(7);
        await(() -> alerts.sent.size() == 4);
        assertEquals("crashed:7", alerts.sent.get(3));
        assertTrue(sleeper.sleeps.isEmpty(), "no auto-restart when disabled");
    }

    @Test
    @DisplayName("auto-restart waits 5 s then starts again after a crash")
    void autoRestart() throws Exception {
        lifecycle = build(false, true);
        lifecycle.start();

        launcher.last().exit(1);

        await(() -> launcher.launched.size() == 2 && lifecycle.getStatus().isRunning());
        assertEquals(List.of(Duration.ofSeconds(5)), sleeper.sleeps);
        assertEquals(List.of("started", "crashed:1", "started"), alerts.sent);
    }

    @Test
    @DisplayName("a manual stop never auto-restarts")
    void manualStopNoAutoRestart() throws Exception {
        lifecycle = build(false, true);
        lifecycle.start();

        lifecycle.stop();

        Thread.sleep(50);
        assertEquals(1, launcher.launched.size());
        assertTrue(sleeper.sleeps.isEmpty());
    }

    @Test
    @DisplayName("restart stops via the console, pauses 2 s and starts a new server")
    void restart() {
        lifecycle.start();
        FakeRunningServer first = launcher.last();

        lifecycle.restart();

        assertEquals(List.of("stop"), first.sent);
        assertEquals(List.of(Duration.ofSeconds(2)), sleeper.sleeps);
        assertEquals(2, launcher.launched.size());
        assertEquals(1001L, lifecycle.getStatus().getPid());
    }

    @Test
    @DisplayName("shutdown goes through the graceful countdown only when running")
    void shutdown() {
        lifecycle.shutdown();
        assertEquals(0, graceful.performed);

        lifecycle.start();
        lifecycle.shutdown();

        assertEquals(1, graceful.performed);
        assertEquals(List.of("stop"), launcher.last().sent);
        assertEquals(List.of("started", "stopped"), alerts.sent);
    }

    @Test
    @DisplayName("auto-start starts on initialize and swallows a failure")
    void autoStart() {
        lifecycle = build(true, false);
        lifecycle.initialize();
        assertTrue(lifecycle.getStatus().isRunning());

        launcher.directoryMissing = true;
        ServerLifecycle failing = build(true, false);
        assertDoesNotThrow(failing::initialize);
    }
}
