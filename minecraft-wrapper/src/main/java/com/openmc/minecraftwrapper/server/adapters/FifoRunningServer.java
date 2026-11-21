package com.openmc.minecraftwrapper.server.adapters;

import com.openmc.minecraftwrapper.server.ports.RunningServer;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * A server process whose stdin is a named pipe. A daemon "keeper" thread holds the pipe open for
 * writing so the server never sees EOF between commands. Built by {@link FifoServerLauncher}.
 */
@Slf4j
public final class FifoRunningServer implements RunningServer {

    private final File serverDir;
    private final Path inputFifo;
    private Thread fifoKeeperThread;
    private volatile Process serverProcess;

    FifoRunningServer(File serverDir, Path inputFifo) {
        this.serverDir = serverDir;
        this.inputFifo = inputFifo;
    }

    void startFifoKeeper() {
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

    @Override
    public void start(List<String> command) throws IOException {
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.directory(serverDir);
        processBuilder.redirectInput(inputFifo.toFile());
        processBuilder.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        processBuilder.redirectError(ProcessBuilder.Redirect.INHERIT);

        serverProcess = processBuilder.start();
    }

    @Override
    public boolean isAlive() {
        Process process = serverProcess;
        return process != null && process.isAlive();
    }

    @Override
    public long pid() {
        return serverProcess.pid();
    }

    @Override
    public Optional<Instant> startedAt() {
        return serverProcess.info().startInstant();
    }

    @Override
    public void send(String line) throws IOException {
        try (FileWriter writer = new FileWriter(inputFifo.toFile(), true)) {
            writer.write(line + "\n");
            writer.flush();
        }
    }

    @Override
    public int awaitExit() throws InterruptedException {
        return serverProcess.waitFor();
    }

    @Override
    public boolean awaitExit(Duration timeout) throws InterruptedException {
        return serverProcess.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public void destroy() {
        serverProcess.destroy();
    }

    @Override
    public void destroyForcibly() {
        serverProcess.destroyForcibly();
    }

    @Override
    public void release() {
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
