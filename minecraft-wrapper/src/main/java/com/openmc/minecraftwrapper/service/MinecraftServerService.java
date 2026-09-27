package com.openmc.minecraftwrapper.service;

import com.openmc.minecraftwrapper.model.ServerMetrics;
import com.openmc.minecraftwrapper.model.ServerStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
public class MinecraftServerService {

    @Value("${minecraft.server.jar}")
    private String serverJar;

    @Value("${minecraft.server.directory}")
    private String serverDirectory;

    @Value("${minecraft.java.opts:-Xmx2G -Xms1G}")
    private String javaOpts;

    // The java the server runs on. Older Minecraft versions refuse newer JDKs (Spigot 1.19.4
    // exits with "Only up to Java 20 is supported"), so the image points this at the JDK its
    // MINECRAFT_VERSION needs; plain `java` from PATH otherwise.
    @Value("${minecraft.java.bin:java}")
    private String javaBin;

    @Value("${minecraft.auto.start:false}")
    private boolean autoStart;

    @Value("${minecraft.auto.restart:false}")
    private boolean autoRestart;

    private final AlertService alertService;
    private final ShutdownService shutdownService;

    private Process serverProcess;
    private Path inputFifo;
    private Thread fifoKeeperThread;
    private final AtomicBoolean shutdownInProgress = new AtomicBoolean(false);
    private final AtomicBoolean manualStop = new AtomicBoolean(false);

    private record TpsCache(String tps, Instant fetchedAt) {}
    private final AtomicReference<TpsCache> tpsCacheRef =
            new AtomicReference<>(new TpsCache(null, Instant.EPOCH));
    private static final Duration TPS_CACHE_TTL = Duration.ofSeconds(30);

    /** Number of trailing log lines scanned for a Paper/Spigot TPS reading. */
    private static final int TPS_SCAN_LINES = 500;

    public MinecraftServerService(AlertService alertService, ShutdownService shutdownService) {
        this.alertService = alertService;
        this.shutdownService = shutdownService;
    }

    @PostConstruct
    public void initialize() {
        if (autoStart) {
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
        if (serverProcess != null && serverProcess.isAlive()) {
            throw new IllegalStateException("Server is already running");
        }
        
        try {
            log.info("Starting Minecraft server with wrapper...");
            log.info("Server JAR: {}", serverJar);
            log.info("Server Directory: {}", serverDirectory);
            log.info("Java Options: {}", javaOpts);

            // Reset flags
            shutdownInProgress.set(false);
            manualStop.set(false);

            File serverDir = new File(serverDirectory);
            if (!serverDir.exists() || !serverDir.isDirectory()) {
                throw new IllegalStateException("Server directory does not exist: " + serverDirectory);
            }

            // Create named pipe (FIFO) for passing commands to the server
            inputFifo = Path.of(serverDirectory, "server_input");
            if (Files.exists(inputFifo)) {
                Files.delete(inputFifo);
            }

            // Create FIFO using mkfifo command
            ProcessBuilder fifoBuilder = new ProcessBuilder("mkfifo", inputFifo.toString());
            Process fifoProcess = fifoBuilder.start();
            if (!fifoProcess.waitFor(5, TimeUnit.SECONDS) || fifoProcess.exitValue() != 0) {
                throw new IOException("Failed to create FIFO");
            }

            // Start FIFO keeper thread to keep the pipe open
            startFifoKeeper();

            List<String> command = buildServerCommand();

            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.directory(serverDir);
            processBuilder.redirectInput(inputFifo.toFile());
            processBuilder.redirectOutput(ProcessBuilder.Redirect.INHERIT);
            processBuilder.redirectError(ProcessBuilder.Redirect.INHERIT);

            serverProcess = processBuilder.start();
            log.info("Minecraft server started with PID: {}", serverProcess.pid());

            // Send alert that server has started
            alertService.sendServerStartAlert();

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
        if (serverProcess == null || !serverProcess.isAlive()) {
            throw new IllegalStateException("Server is not running");
        }
        
        manualStop.set(true);
        shutdownInProgress.set(true);
        
        try {
            // Send stop command via FIFO
            sendCommand("stop");
            
            // Wait for server to shutdown gracefully
            log.info("Waiting for server to stop gracefully...");
            if (!serverProcess.waitFor(30, TimeUnit.SECONDS)) {
                log.warn("Server did not stop in time, forcing termination");
                serverProcess.destroyForcibly();
            }
            
            log.info("Server stopped successfully");
            alertService.sendServerStopAlert();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted while waiting for server to stop");
            serverProcess.destroyForcibly();
        } catch (IOException e) {
            log.error("Failed to send stop command", e);
            serverProcess.destroy();
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
        
        if (serverProcess != null && serverProcess.isAlive()) {
            // Set manualStop temporarily to prevent auto-restart during stop
            manualStop.set(true);
            shutdownInProgress.set(true);
            
            try {
                // Send stop command via FIFO
                sendCommand("stop");
                
                // Wait for server to shutdown gracefully
                log.info("Waiting for server to stop before restart...");
                if (!serverProcess.waitFor(30, TimeUnit.SECONDS)) {
                    log.warn("Server did not stop in time, forcing termination");
                    serverProcess.destroyForcibly();
                }
                
                log.info("Server stopped, preparing to restart...");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("Interrupted while waiting for server to stop");
                serverProcess.destroyForcibly();
            } catch (IOException e) {
                log.error("Failed to send stop command", e);
                serverProcess.destroy();
            } finally {
                cleanupResources();
            }
        }
        
        // Wait a bit before restarting
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        // Now start the server
        start();
    }

    private void cleanupResources() {
        // Cleanup FIFO keeper thread
        if (fifoKeeperThread != null && fifoKeeperThread.isAlive()) {
            fifoKeeperThread.interrupt();
            try {
                fifoKeeperThread.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        // Cleanup FIFO - check exists to avoid race conditions
        if (inputFifo != null) {
            try {
                if (Files.exists(inputFifo)) {
                    Files.delete(inputFifo);
                    log.debug("FIFO deleted");
                }
            } catch (IOException e) {
                // Only log at debug level since file may already be deleted
                log.debug("Could not delete FIFO (may already be removed): {}", e.getMessage());
            }
        }
    }

    private void startFifoKeeper() {
        fifoKeeperThread = new Thread(() -> {
            try (FileOutputStream fos = new FileOutputStream(inputFifo.toFile())) {
                // Keep the FIFO open by holding it open for writing
                // The thread will be interrupted when we want to stop
                while (!Thread.currentThread().isInterrupted()) {
                    Thread.sleep(3600000); // Sleep for an hour at a time
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.debug("FIFO keeper thread interrupted");
            } catch (IOException e) {
                log.error("Error in FIFO keeper thread", e);
            }
        });
        fifoKeeperThread.setDaemon(true);
        fifoKeeperThread.start();
    }

    private void monitorServerProcess() {
        Thread monitorThread = new Thread(() -> {
            try {
                int exitCode = serverProcess.waitFor();
                log.info("Minecraft server process exited with code: {}", exitCode);

                // Cleanup resources after process ends
                cleanupResources();

                // Only send alerts if not in shutdown process
                if (!shutdownInProgress.get()) {
                    if (exitCode == 0) {
                        alertService.sendServerStopAlert();
                    } else {
                        alertService.sendServerCrashAlert(exitCode);
                    }
                    
                    // Auto-restart if enabled and not a manual stop
                    if (autoRestart && !manualStop.get()) {
                        log.info("Auto-restart enabled, restarting server in 5 seconds...");
                        try {
                            Thread.sleep(5000);
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

    @PreDestroy
    public void shutdown() {
        if (serverProcess != null && serverProcess.isAlive()) {
            manualStop.set(true);
            shutdownInProgress.set(true);
            
            shutdownService.performGracefulShutdown(() -> {
                try {
                    // Send stop command via FIFO
                    sendCommand("stop");
                    
                    // Wait for server to shutdown gracefully
                    log.info("Waiting for server to shutdown gracefully...");
                    if (!serverProcess.waitFor(30, TimeUnit.SECONDS)) {
                        log.warn("Server did not shutdown in time, forcing termination");
                        serverProcess.destroyForcibly();
                    }
                    
                    log.info("Server shutdown gracefully");
                    alertService.sendServerStopAlert();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.error("Interrupted while waiting for server shutdown");
                    serverProcess.destroyForcibly();
                } catch (IOException e) {
                    log.error("Failed to send stop command", e);
                    serverProcess.destroy();
                }
            });
        }

        cleanupResources();
    }

    public void sendCommand(String command) throws IOException {
        if (serverProcess == null || !serverProcess.isAlive()) {
            throw new IllegalStateException("Server is not running");
        }

        try (FileWriter writer = new FileWriter(inputFifo.toFile(), true)) {
            writer.write(command + "\n");
            writer.flush();
            log.info("Command sent to server: {}", command);
        }
    }

    public ServerStatus getStatus() {
        boolean running = serverProcess != null && serverProcess.isAlive();
        Long uptimeSeconds = null;
        String startedAt = null;
        if (running) {
            ProcessHandle.Info info = serverProcess.info();
            Optional<Instant> startOpt = info.startInstant();
            if (startOpt.isPresent()) {
                Instant start = startOpt.get();
                uptimeSeconds = Duration.between(start, Instant.now()).getSeconds();
                startedAt = start.toString();
            }
        }
        return ServerStatus.builder()
                .running(running)
                .pid(running ? serverProcess.pid() : null)
                .serverJar(serverJar)
                .serverDirectory(serverDirectory)
                .uptimeSeconds(uptimeSeconds)
                .startedAt(startedAt)
                .build();
    }

    /**
     * Read the last {@code maxLines} lines from the server's {@code logs/latest.log} file.
     * Returns an empty list if the log file does not exist or cannot be read.
     *
     * <p>Uses a streaming bounded-deque to avoid loading the entire log file into memory,
     * regardless of how large the file has grown. Decoding is lenient, so a line containing
     * bytes that are not valid UTF-8 is returned with those bytes replaced by U+FFFD rather
     * than costing the caller the whole tail — see {@link #readLogTail(int)}.
     *
     * @param maxLines maximum number of lines to return (clamped to 1..logsDiagnosticMaxLines by the caller)
     * @return tail of the log file, oldest line first
     */
    public List<String> getRecentLogLines(int maxLines) {
        return readLogTail(maxLines);
    }

    /**
     * Read the last {@code maxLines} lines of {@code logs/latest.log} into a list, oldest first.
     *
     * <p>Uses a streaming bounded deque so the whole file is never held in memory, and decodes
     * leniently: {@link InputStreamReader} constructed with a {@link java.nio.charset.Charset}
     * replaces undecodable bytes with U+FFFD instead of throwing. Server logs regularly carry
     * bytes that are not valid UTF-8 (plugin output, player chat), and a strict decoder would
     * abort the read mid-stream with an {@link UncheckedIOException} and lose the entire tail.
     *
     * @param maxLines maximum number of lines to return
     * @return tail of the log file, oldest line first; empty when the file is missing or unreadable
     */
    private List<String> readLogTail(int maxLines) {
        Path logFile = Path.of(serverDirectory, "logs", "latest.log");
        if (!Files.exists(logFile)) {
            log.debug("Server log file not found at {}", logFile);
            return List.of();
        }
        // Math.max only keeps the initial capacity legal; a non-positive maxLines still yields an
        // empty list, because every line added below is evicted again on the same iteration.
        Deque<String> tail = new ArrayDeque<>(Math.max(1, maxLines));
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(Files.newInputStream(logFile), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                tail.addLast(line);
                if (tail.size() > maxLines) {
                    tail.pollFirst();
                }
            }
            return new ArrayList<>(tail);
        } catch (IOException e) {
            log.error("Failed to read server log file {}: {}", logFile, e.getMessage());
            return List.of();
        }
    }

    /**
     * Return a performance metrics snapshot for the Minecraft server and its JVM.
     *
     * <ul>
     *   <li><b>Wrapper JVM heap</b> — always available via {@link Runtime}.</li>
     *   <li><b>Server process memory</b> — read from {@code /proc/{pid}/status}
     *       on Linux; {@code null} on other platforms or when the server is not
     *       running.</li>
     *   <li><b>Server uptime</b> — derived from {@link ProcessHandle.Info#startInstant()}.</li>
     *   <li><b>TPS</b> — extracted from the most recent
     *       {@code TPS from last 1m, 5m, 15m: …} line in {@code logs/latest.log}
     *       (Paper / Spigot servers only).  {@code null} otherwise.</li>
     * </ul>
     *
     * @return metrics snapshot; individual fields may be {@code null} when unavailable
     */
    public ServerMetrics getServerMetrics() {
        ServerMetrics.ServerMetricsBuilder builder = ServerMetrics.builder();

        // ── Wrapper JVM heap ─────────────────────────────────────────────────
        Runtime runtime = Runtime.getRuntime();
        long usedBytes = runtime.totalMemory() - runtime.freeMemory();
        long maxBytes = runtime.maxMemory();
        builder.wrapperHeapUsedMb(usedBytes / (1024 * 1024));
        builder.wrapperHeapMaxMb(maxBytes / (1024 * 1024));
        builder.wrapperHeapUsedPercent(
                Math.round((double) usedBytes / maxBytes * 1000.0) / 10.0);

        // ── Server process metrics ────────────────────────────────────────────
        if (serverProcess != null && serverProcess.isAlive()) {
            long pid = serverProcess.pid();

            // Uptime from ProcessHandle.Info
            ProcessHandle.Info info = serverProcess.info();
            info.startInstant().ifPresent(start ->
                    builder.serverUptimeSeconds(Duration.between(start, Instant.now()).getSeconds()));

            // Resident memory from /proc/{pid}/status (Linux only)
            builder.serverMemoryMb(readProcessRssMb(pid));

            // TPS from log file
            String tps = parseTpsFromLogs();
            builder.tps(tps);
            if (tps == null) {
                builder.tpsNote("No TPS data found in logs. TPS logging requires a Paper or Spigot server.");
            }
        } else {
            builder.tpsNote("Server is not running.");
        }

        return builder.build();
    }

    /**
     * Read the Resident Set Size (RSS) of process {@code pid} from
     * {@code /proc/{pid}/status} (Linux only).
     *
     * @param pid OS process ID
     * @return RSS in MiB, or {@code null} if unavailable
     */
    private Long readProcessRssMb(long pid) {
        Path statusFile = Path.of("/proc", String.valueOf(pid), "status");
        if (!Files.exists(statusFile)) {
            return null;
        }
        try (var lines = Files.lines(statusFile)) {
            return lines
                    .filter(l -> l.startsWith("VmRSS:"))
                    .findFirst()
                    .map(line -> {
                        // Format: "VmRSS:     12345 kB"
                        String[] parts = line.trim().split("\\s+");
                        return parts.length >= 2 ? Long.parseLong(parts[1]) / 1024 : null;
                    })
                    .orElse(null);
        } catch (Exception e) {
            log.debug("Could not read process RSS for pid {}: {}", pid, e.getMessage());
            return null;
        }
    }

    /**
     * Scan the tail of {@code logs/latest.log} for a Paper/Spigot TPS line.
     *
     * <p>Reads through {@link #readLogTail(int)}, so the full file is never held in memory and an
     * unreadable log yields {@code null} rather than an exception. A missing TPS line and an
     * unreadable log are both cached for {@link #TPS_CACHE_TTL} so the file is not re-scanned on
     * every metrics request.
     *
     * @return the TPS segment starting from "TPS from last …", or {@code null} if not found
     */
    private String parseTpsFromLogs() {
        // Return cached value if it is still fresh (atomic read — both fields are in one record)
        TpsCache cached = tpsCacheRef.get();
        if (Duration.between(cached.fetchedAt(), Instant.now()).compareTo(TPS_CACHE_TTL) < 0) {
            return cached.tps();
        }
        // Collect the last 500 lines to avoid loading the full file
        List<String> tail = readLogTail(TPS_SCAN_LINES);
        // Scan backwards through the tail for the most recent TPS entry
        for (int i = tail.size() - 1; i >= 0; i--) {
            int idx = tail.get(i).indexOf("TPS from last");
            if (idx >= 0) {
                String result = tail.get(i).substring(idx);
                tpsCacheRef.set(new TpsCache(result, Instant.now()));
                return result;
            }
        }
        // No TPS line found — cache the null result to avoid re-scanning within the TTL window
        tpsCacheRef.set(new TpsCache(null, Instant.now()));
        return null;
    }

    /** The command that starts the Minecraft server: the configured java, its options, the jar. */
    List<String> buildServerCommand() {
        List<String> command = new ArrayList<>();
        command.add(javaBin);
        for (String opt : javaOpts.split("\\s+")) {
            if (!opt.isEmpty()) {
                command.add(opt);
            }
        }
        command.add("-jar");
        command.add(serverJar);
        command.add("nogui");
        return command;
    }
}
