package com.openmc.minecraftwrapper.server.fakes;

import com.openmc.minecraftwrapper.server.ports.ServerAlerts;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Records alerts as strings: "started", "stopped", "crashed:N". */
public final class FakeServerAlerts implements ServerAlerts {

    public final List<String> sent = new CopyOnWriteArrayList<>();

    @Override
    public void serverStarted() {
        sent.add("started");
    }

    @Override
    public void serverStopped() {
        sent.add("stopped");
    }

    @Override
    public void serverCrashed(int exitCode) {
        sent.add("crashed:" + exitCode);
    }
}
