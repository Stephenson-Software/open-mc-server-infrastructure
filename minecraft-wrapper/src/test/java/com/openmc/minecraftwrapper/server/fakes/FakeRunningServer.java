package com.openmc.minecraftwrapper.server.fakes;

import com.openmc.minecraftwrapper.server.ports.RunningServer;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Scriptable server: {@link #exit(int)} ends it; a console line listed in {@link #exitOn} ends it
 * with that code, like a real server receiving {@code stop}.
 */
public final class FakeRunningServer implements RunningServer {

    public final long pid;
    public final Instant startedAt;
    public final List<String> sent = new CopyOnWriteArrayList<>();
    public volatile List<String> command;
    public volatile String exitOn = "stop";
    public volatile int exitOnCode = 0;
    public volatile boolean ignoreStop;
    public volatile IOException sendFailure;
    public volatile IOException startFailure;
    public volatile int releases;
    /** When set, {@link #awaitExit()} holds the exit monitor here after the exit, until released. */
    public volatile java.util.concurrent.CountDownLatch monitorGate;
    public volatile boolean destroyed;
    public volatile boolean destroyedForcibly;
    private final CompletableFuture<Integer> exit = new CompletableFuture<>();
    private volatile boolean started;

    public FakeRunningServer(long pid, Instant startedAt) {
        this.pid = pid;
        this.startedAt = startedAt;
    }

    public void exit(int code) {
        exit.complete(code);
    }

    @Override
    public void start(List<String> command) throws IOException {
        if (startFailure != null) {
            throw startFailure;
        }
        this.command = command;
        started = true;
    }

    @Override
    public boolean isAlive() {
        return started && !exit.isDone();
    }

    @Override
    public long pid() {
        return pid;
    }

    @Override
    public Optional<Instant> startedAt() {
        return Optional.of(startedAt);
    }

    @Override
    public void send(String line) throws IOException {
        if (sendFailure != null) {
            throw sendFailure;
        }
        sent.add(line);
        if (line.equals(exitOn) && !ignoreStop) {
            exit(exitOnCode);
        }
    }

    @Override
    public int awaitExit() throws InterruptedException {
        try {
            int code = exit.get();
            java.util.concurrent.CountDownLatch gate = monitorGate;
            if (gate != null) {
                gate.await();
            }
            return code;
        } catch (ExecutionException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public boolean awaitExit(Duration timeout) throws InterruptedException {
        if (ignoreStop) {
            return false; // pretend the full timeout elapsed
        }
        try {
            exit.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return true;
        } catch (TimeoutException e) {
            return false;
        } catch (ExecutionException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void destroy() {
        destroyed = true;
        exit(143);
    }

    @Override
    public void destroyForcibly() {
        destroyedForcibly = true;
        exit(137);
    }

    @Override
    public synchronized void release() {
        releases++;
    }
}
