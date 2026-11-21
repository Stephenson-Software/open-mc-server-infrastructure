package com.openmc.minecraftwrapper.server.fakes;

import com.openmc.minecraftwrapper.server.ports.RunningServer;
import com.openmc.minecraftwrapper.server.ports.ServerLauncher;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Hands out a fresh {@link FakeRunningServer} per prepare, with pids 1000, 1001, … */
public final class FakeServerLauncher implements ServerLauncher {

    public final List<FakeRunningServer> launched = new CopyOnWriteArrayList<>();
    public final List<String> directories = new CopyOnWriteArrayList<>();
    public volatile Instant startedAt = Instant.parse("2026-11-20T00:00:00Z");
    public volatile boolean directoryMissing;
    public volatile java.io.IOException startFailure;

    @Override
    public RunningServer prepare(String serverDirectory) {
        if (directoryMissing) {
            throw new IllegalStateException("Server directory does not exist: " + serverDirectory);
        }
        directories.add(serverDirectory);
        FakeRunningServer server = new FakeRunningServer(1000 + launched.size(), startedAt);
        server.startFailure = startFailure;
        launched.add(server);
        return server;
    }

    public FakeRunningServer last() {
        return launched.get(launched.size() - 1);
    }
}
