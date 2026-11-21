package com.openmc.minecraftwrapper.server.fakes;

import com.openmc.minecraftwrapper.server.ports.ServerLog;

import java.util.ArrayList;
import java.util.List;

/** An in-memory log. */
public final class FakeServerLog implements ServerLog {

    public volatile List<String> lines = List.of();
    public volatile int reads;

    @Override
    public List<String> tail(int maxLines) {
        reads++;
        if (maxLines <= 0) {
            return List.of();
        }
        List<String> all = lines;
        return new ArrayList<>(all.subList(Math.max(0, all.size() - maxLines), all.size()));
    }
}
