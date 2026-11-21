package com.openmc.minecraftwrapper.service;

import com.openmc.minecraftwrapper.model.ServerMetrics;
import com.openmc.minecraftwrapper.model.ServerStatus;
import com.openmc.minecraftwrapper.server.model.ServerSettings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Characterization tests for the server lifecycle: start, stop, restart, shutdown, the exit
 * monitor and auto-restart. They pin today's behaviour end to end with a real named pipe and a
 * real child process — a stub {@code java} shell script that logs each console line it reads and
 * exits on {@code stop} (0), {@code quit} (0) or {@code crash} (3).
 */
@EnabledOnOs({OS.LINUX, OS.MAC})
@ExtendWith(MockitoExtension.class)
@DisplayName("MinecraftServerService lifecycle (characterization)")
class MinecraftServerServiceLifecycleTest {

    private static final Duration WAIT = Duration.ofSeconds(15);

    @TempDir
    Path tempDir;

    @Mock
    private AlertService alertService;

    @Mock
    private ShutdownService shutdownService;

    private Path serverDir;
    private Path stdinLog;
    private MinecraftServerService service;

    @BeforeEach
    void setUp() throws IOException {
        serverDir = Files.createDirectories(tempDir.resolve("server"));
        stdinLog = serverDir.resolve("stub-stdin.log");
        Path stubJava = tempDir.resolve("stub-java.sh");
        Files.writeString(stubJava, String.join("\n",
                "#!/bin/sh",
                "while IFS= read -r line; do",
                "  echo \"$line\" >> stub-stdin.log",
                "  case \"$line\" in",
                "    stop|quit) exit 0 ;;",
                "    crash) exit 3 ;;",
                "  esac",
                "done",
                "exit 0",
                ""));
        Files.setPosixFilePermissions(stubJava, PosixFilePermissions.fromString("rwxr-xr-x"));
        service = newService(serverDir, stubJava, false);
    }

    /** The only place that knows how the service is configured. */
    private MinecraftServerService newService(Path dir, Path javaBin, boolean autoRestart) {
        return new MinecraftServerService(
                new ServerSettings("server.jar", dir.toString(), "-Xmx1G", javaBin.toString(), false, autoRestart),
                alertService, shutdownService);
    }

    private void enableAutoRestart() {
        service = newService(serverDir, tempDir.resolve("stub-java.sh"), true);
    }

    @AfterEach
    void tearDown() {
        try {
            service.stop();
        } catch (IllegalStateException notRunning) {
            // already stopped
        }
    }

    private static void await(BooleanSupplier condition, String what) throws InterruptedException {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                fail("Timed out waiting for " + what);
            }
            Thread.sleep(25);
        }
    }

    private List<String> stdinLines() {
        try {
            return Files.exists(stdinLog) ? Files.readAllLines(stdinLog) : List.of();
        } catch (IOException e) {
            return List.of();
        }
    }

    private Path fifo() {
        return serverDir.resolve("server_input");
    }

    // ── start ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("start launches the server on a fresh FIFO and sends a start alert")
    void startLaunchesServer() {
        service.start();

        ServerStatus status = service.getStatus();
        assertTrue(status.isRunning());
        assertNotNull(status.getPid());
        assertEquals("server.jar", status.getServerJar());
        assertEquals(serverDir.toString(), status.getServerDirectory());
        assertTrue(Files.exists(fifo()));
        verify(alertService, times(1)).sendServerStartAlert();
    }

    @Test
    @DisplayName("start replaces a stale file at the FIFO path")
    void startReplacesStaleFifoPath() throws IOException {
        Files.writeString(fifo(), "stale");

        service.start();

        assertFalse(Files.isRegularFile(fifo()));
        assertTrue(Files.exists(fifo()));
    }

    @Test
    @DisplayName("start while running throws IllegalStateException without wrapping")
    void startTwiceThrows() {
        service.start();

        IllegalStateException e = assertThrows(IllegalStateException.class, service::start);
        assertEquals("Server is already running", e.getMessage());
        verify(alertService, times(1)).sendServerStartAlert();
    }

    @Test
    @DisplayName("start with a missing server directory wraps the cause in a RuntimeException")
    void startMissingDirectory() {
        service = newService(tempDir.resolve("nope"), tempDir.resolve("stub-java.sh"), false);

        RuntimeException e = assertThrows(RuntimeException.class, service::start);
        assertEquals("Failed to start Minecraft server", e.getMessage());
        assertInstanceOf(IllegalStateException.class, e.getCause());
        assertEquals("Server directory does not exist: " + tempDir.resolve("nope"), e.getCause().getMessage());
        verifyNoInteractions(alertService);
    }

    @Test
    @DisplayName("start with a java binary that cannot run wraps the failure in a RuntimeException")
    void startUnrunnableJava() {
        service = newService(serverDir, tempDir.resolve("no-such-java"), false);

        RuntimeException e = assertThrows(RuntimeException.class, service::start);
        assertEquals("Failed to start Minecraft server", e.getMessage());
        assertInstanceOf(IOException.class, e.getCause());
        assertFalse(service.getStatus().isRunning());
        verifyNoInteractions(alertService);
    }

    // ── console ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("sendCommand writes one line to the server's stdin")
    void sendCommandReachesStdin() throws Exception {
        service.start();

        service.sendCommand("say hello");
        service.sendCommand("list");

        await(() -> stdinLines().equals(List.of("say hello", "list")), "both console lines");
    }

    @Test
    @DisplayName("sendCommand while stopped throws IllegalStateException")
    void sendCommandWhileStopped() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> service.sendCommand("list"));
        assertEquals("Server is not running", e.getMessage());
    }

    // ── stop ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("stop sends 'stop', waits for exit, alerts once and removes the FIFO")
    void stopIsGraceful() throws Exception {
        service.start();

        service.stop();

        assertFalse(service.getStatus().isRunning());
        assertEquals(List.of("stop"), stdinLines());
        assertFalse(Files.exists(fifo()));
        Thread.sleep(300); // give the exit monitor time to (not) alert
        verify(alertService, times(1)).sendServerStopAlert();
        verify(alertService, never()).sendServerCrashAlert(anyInt());
    }

    @Test
    @DisplayName("stop while stopped throws IllegalStateException")
    void stopWhileStopped() {
        IllegalStateException e = assertThrows(IllegalStateException.class, service::stop);
        assertEquals("Server is not running", e.getMessage());
    }

    @Test
    @DisplayName("a manual stop does not auto-restart")
    void manualStopDoesNotAutoRestart() throws Exception {
        enableAutoRestart();
        service.start();

        service.stop();

        Thread.sleep(6000); // longer than the 5 s auto-restart delay
        verify(alertService, times(1)).sendServerStartAlert();
        assertFalse(service.getStatus().isRunning());
    }

    // ── exit monitor ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("an unrequested clean exit sends a stop alert and removes the FIFO")
    void cleanExitAlertsStop() throws Exception {
        service.start();

        service.sendCommand("quit");

        verify(alertService, timeout(WAIT.toMillis())).sendServerStopAlert();
        await(() -> !service.getStatus().isRunning(), "the server to exit");
        await(() -> !Files.exists(fifo()), "the FIFO to be removed");
        verify(alertService, never()).sendServerCrashAlert(anyInt());
    }

    @Test
    @DisplayName("a non-zero exit sends a crash alert with the exit code")
    void crashAlertsWithExitCode() throws Exception {
        service.start();

        service.sendCommand("crash");

        verify(alertService, timeout(WAIT.toMillis())).sendServerCrashAlert(3);
        await(() -> !service.getStatus().isRunning(), "the server to exit");
        verify(alertService, never()).sendServerStopAlert();
    }

    @Test
    @DisplayName("a crash without auto-restart leaves the server stopped")
    void crashWithoutAutoRestartStaysDown() throws Exception {
        service.start();

        service.sendCommand("crash");

        verify(alertService, timeout(WAIT.toMillis())).sendServerCrashAlert(3);
        Thread.sleep(6000);
        verify(alertService, times(1)).sendServerStartAlert();
        assertFalse(service.getStatus().isRunning());
    }

    @Test
    @DisplayName("a crash with auto-restart starts the server again")
    void crashWithAutoRestartRestarts() throws Exception {
        enableAutoRestart();
        service.start();
        long firstPid = service.getStatus().getPid();

        service.sendCommand("crash");

        verify(alertService, timeout(WAIT.toMillis())).sendServerCrashAlert(3);
        verify(alertService, timeout(WAIT.toMillis()).times(2)).sendServerStartAlert();
        await(() -> service.getStatus().isRunning(), "the server to come back");
        assertNotEquals(firstPid, service.getStatus().getPid());
        assertTrue(Files.exists(fifo()));
    }

    // ── restart ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("restart stops via the console and starts a new process without a stop alert")
    void restartRunningServer() throws Exception {
        service.start();
        long firstPid = service.getStatus().getPid();

        service.restart();

        assertTrue(service.getStatus().isRunning());
        assertNotEquals(firstPid, service.getStatus().getPid());
        assertEquals(List.of("stop"), stdinLines());
        verify(alertService, times(2)).sendServerStartAlert();
        verify(alertService, never()).sendServerStopAlert();
        verify(alertService, never()).sendServerCrashAlert(anyInt());
    }

    @Test
    @DisplayName("restart while stopped just starts the server")
    void restartStoppedServer() {
        service.restart();

        assertTrue(service.getStatus().isRunning());
        assertTrue(stdinLines().isEmpty());
        verify(alertService, times(1)).sendServerStartAlert();
    }

    // ── shutdown ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("shutdown stops a running server through the graceful-shutdown countdown")
    void shutdownRunningServer() {
        doAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run();
            return null;
        }).when(shutdownService).performGracefulShutdown(any());
        service.start();

        service.shutdown();

        verify(shutdownService, times(1)).performGracefulShutdown(any());
        assertFalse(service.getStatus().isRunning());
        assertEquals(List.of("stop"), stdinLines());
        assertFalse(Files.exists(fifo()));
        verify(alertService, times(1)).sendServerStopAlert();
    }

    @Test
    @DisplayName("shutdown while stopped skips the countdown")
    void shutdownStoppedServer() {
        service.shutdown();

        verifyNoInteractions(shutdownService);
        verifyNoInteractions(alertService);
    }

    // ── status and metrics ───────────────────────────────────────────────────

    @Test
    @DisplayName("status while stopped reports no pid, uptime or start time")
    void statusWhileStopped() {
        ServerStatus status = service.getStatus();

        assertFalse(status.isRunning());
        assertNull(status.getPid());
        assertNull(status.getUptimeSeconds());
        assertNull(status.getStartedAt());
        assertEquals("server.jar", status.getServerJar());
        assertEquals(serverDir.toString(), status.getServerDirectory());
    }

    @Test
    @DisplayName("status while running reports uptime and an ISO start time")
    void statusWhileRunning() {
        service.start();

        ServerStatus status = service.getStatus();

        assertNotNull(status.getUptimeSeconds());
        assertTrue(status.getUptimeSeconds() >= 0);
        assertNotNull(status.getStartedAt());
        assertDoesNotThrow(() -> java.time.Instant.parse(status.getStartedAt()));
    }

    @Test
    @DisplayName("metrics while stopped carry only the wrapper heap and a not-running note")
    void metricsWhileStopped() {
        ServerMetrics m = service.getServerMetrics();

        assertNotNull(m.getWrapperHeapUsedMb());
        assertNotNull(m.getWrapperHeapMaxMb());
        assertNotNull(m.getWrapperHeapUsedPercent());
        assertNull(m.getServerUptimeSeconds());
        assertNull(m.getServerMemoryMb());
        assertNull(m.getTps());
        assertEquals("Server is not running.", m.getTpsNote());
    }

    @Test
    @DisplayName("metrics while running add uptime, and a no-TPS note when the log has none")
    void metricsWhileRunning() {
        service.start();

        ServerMetrics m = service.getServerMetrics();

        assertNotNull(m.getServerUptimeSeconds());
        assertNull(m.getTps());
        assertEquals("No TPS data found in logs. TPS logging requires a Paper or Spigot server.", m.getTpsNote());
    }
}
