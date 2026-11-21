package com.openmc.minecraftwrapper.service;

import com.openmc.minecraftwrapper.model.ServerMetrics;
import com.openmc.minecraftwrapper.model.ServerStatus;
import com.openmc.minecraftwrapper.server.adapters.AlertManagerServerAlerts;
import com.openmc.minecraftwrapper.server.adapters.BroadcastGracefulShutdown;
import com.openmc.minecraftwrapper.server.adapters.FifoServerLauncher;
import com.openmc.minecraftwrapper.server.adapters.LatestLogFile;
import com.openmc.minecraftwrapper.server.adapters.ProcfsMemoryProbe;
import com.openmc.minecraftwrapper.server.adapters.ThreadSleeper;
import com.openmc.minecraftwrapper.server.core.ServerLifecycle;
import com.openmc.minecraftwrapper.server.core.ServerTelemetry;
import com.openmc.minecraftwrapper.server.model.ServerSettings;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.Clock;
import java.util.List;

/**
 * Composition root for the Minecraft server subsystem ({@code com.openmc.minecraftwrapper.server}).
 *
 * <p>Binds {@link ServerSettings} from configuration, wires the production adapters (named pipe
 * console, {@code /proc} memory probe, {@code logs/latest.log}, the alert-manager, the shutdown
 * countdown, the system clock) into the core, and exposes the API the controllers use. See the
 * {@code com.openmc.minecraftwrapper.server} package doc for the layer diagram.
 */
@Service
public class MinecraftServerService {

    private final ServerLifecycle lifecycle;
    private final ServerTelemetry telemetry;

    @Autowired
    public MinecraftServerService(
            AlertService alertService,
            ShutdownService shutdownService,
            @Value("${minecraft.server.jar}") String serverJar,
            @Value("${minecraft.server.directory}") String serverDirectory,
            @Value("${minecraft.java.opts:-Xmx2G -Xms1G}") String javaOpts,
            // The java the server runs on. Older Minecraft versions refuse newer JDKs (Spigot 1.19.4
            // exits with "Only up to Java 20 is supported"), so the image points this at the JDK its
            // MINECRAFT_VERSION needs; plain `java` from PATH otherwise.
            @Value("${minecraft.java.bin:java}") String javaBin,
            @Value("${minecraft.auto.start:false}") boolean autoStart,
            @Value("${minecraft.auto.restart:false}") boolean autoRestart) {
        this(new ServerSettings(serverJar, serverDirectory, javaOpts, javaBin, autoStart, autoRestart),
                alertService, shutdownService);
    }

    /** Production adapters for {@code settings}. */
    public MinecraftServerService(ServerSettings settings, AlertService alertService, ShutdownService shutdownService) {
        this(new ServerLifecycle(settings,
                        new AlertManagerServerAlerts(alertService),
                        new BroadcastGracefulShutdown(shutdownService),
                        new FifoServerLauncher(),
                        new ThreadSleeper(),
                        Clock.systemUTC()),
                new ServerTelemetry(
                        new LatestLogFile(settings.serverDirectory()),
                        new ProcfsMemoryProbe(),
                        Clock.systemUTC()));
    }

    /** Any core, e.g. one built on fakes. */
    public MinecraftServerService(ServerLifecycle lifecycle, ServerTelemetry telemetry) {
        this.lifecycle = lifecycle;
        this.telemetry = telemetry;
    }

    @PostConstruct
    public void initialize() {
        lifecycle.initialize();
    }

    /**
     * Start the Minecraft server.
     * @throws IllegalStateException if server is already running
     */
    public void start() {
        lifecycle.start();
    }

    /** Stop the Minecraft server gracefully; a manual stop never auto-restarts. */
    public void stop() {
        lifecycle.stop();
    }

    /** Stop the server if running, then start it again. */
    public void restart() {
        lifecycle.restart();
    }

    @PreDestroy
    public void shutdown() {
        lifecycle.shutdown();
    }

    public void sendCommand(String command) throws IOException {
        lifecycle.sendCommand(command);
    }

    public ServerStatus getStatus() {
        return lifecycle.getStatus();
    }

    /**
     * Read the last {@code maxLines} lines from the server's {@code logs/latest.log} file, oldest
     * first; empty if the file does not exist or cannot be read.
     *
     * @param maxLines maximum number of lines to return (clamped to 1..logsDiagnosticMaxLines by the caller)
     */
    public List<String> getRecentLogLines(int maxLines) {
        return telemetry.getRecentLogLines(maxLines);
    }

    /** Performance metrics snapshot; see {@link ServerTelemetry#getServerMetrics}. */
    public ServerMetrics getServerMetrics() {
        return telemetry.getServerMetrics(lifecycle.current());
    }
}
