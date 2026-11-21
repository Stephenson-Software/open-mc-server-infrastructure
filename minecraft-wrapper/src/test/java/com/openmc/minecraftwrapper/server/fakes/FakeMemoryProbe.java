package com.openmc.minecraftwrapper.server.fakes;

import com.openmc.minecraftwrapper.server.ports.ProcessMemoryProbe;

/** Returns a fixed reading. */
public final class FakeMemoryProbe implements ProcessMemoryProbe {

    public volatile Long residentMb;

    @Override
    public Long residentMb(long pid) {
        return residentMb;
    }
}
