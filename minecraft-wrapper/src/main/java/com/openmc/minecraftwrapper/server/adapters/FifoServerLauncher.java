package com.openmc.minecraftwrapper.server.adapters;

import com.openmc.minecraftwrapper.server.ports.RunningServer;
import com.openmc.minecraftwrapper.server.ports.ServerLauncher;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Prepares the server on a named pipe ({@code <dir>/server_input}, made with {@code mkfifo}) that
 * becomes its stdin, so the wrapper can type console commands. POSIX only.
 */
public final class FifoServerLauncher implements ServerLauncher {

    @Override
    public RunningServer prepare(String serverDirectory) throws IOException, InterruptedException {
        File serverDir = new File(serverDirectory);
        if (!serverDir.exists() || !serverDir.isDirectory()) {
            throw new IllegalStateException("Server directory does not exist: " + serverDirectory);
        }

        // Create named pipe (FIFO) for passing commands to the server
        Path inputFifo = Path.of(serverDirectory, "server_input");
        if (Files.exists(inputFifo)) {
            Files.delete(inputFifo);
        }

        // Create FIFO using mkfifo command
        ProcessBuilder fifoBuilder = new ProcessBuilder("mkfifo", inputFifo.toString());
        Process fifoProcess = fifoBuilder.start();
        if (!fifoProcess.waitFor(5, TimeUnit.SECONDS) || fifoProcess.exitValue() != 0) {
            throw new IOException("Failed to create FIFO");
        }

        FifoRunningServer server = new FifoRunningServer(serverDir, inputFifo);
        // Start FIFO keeper thread to keep the pipe open
        server.startFifoKeeper();
        return server;
    }
}
