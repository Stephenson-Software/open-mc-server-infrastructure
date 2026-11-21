package com.openmc.minecraftwrapper.server.core;

import com.openmc.minecraftwrapper.model.ServerMetrics;
import com.openmc.minecraftwrapper.server.fakes.FakeMemoryProbe;
import com.openmc.minecraftwrapper.server.fakes.FakeRunningServer;
import com.openmc.minecraftwrapper.server.fakes.FakeServerLog;
import com.openmc.minecraftwrapper.server.fakes.MutableClock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ServerTelemetry (fakes)")
class ServerTelemetryTest {

    private static final String TPS = "TPS from last 1m, 5m, 15m: 20.0, 20.0, 20.0";

    private final FakeServerLog log = new FakeServerLog();
    private final FakeMemoryProbe memory = new FakeMemoryProbe();
    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-20T00:10:00Z"));
    private final ServerTelemetry telemetry = new ServerTelemetry(log, memory, clock);

    private FakeRunningServer running() throws Exception {
        FakeRunningServer s = new FakeRunningServer(42, Instant.parse("2026-11-20T00:00:00Z"));
        s.start(List.of("java"));
        return s;
    }

    @Test
    @DisplayName("the TPS cache expires after 30 s")
    void tpsCacheExpires() {
        log.lines = List.of("[x] " + TPS);
        assertEquals(TPS, telemetry.latestTps());

        log.lines = List.of("[y] TPS from last 1m, 5m, 15m: 5.0, 5.0, 5.0");
        clock.advance(Duration.ofSeconds(29));
        assertEquals(TPS, telemetry.latestTps());

        clock.advance(Duration.ofSeconds(1));
        assertEquals("TPS from last 1m, 5m, 15m: 5.0, 5.0, 5.0", telemetry.latestTps());
        assertEquals(2, log.reads);
    }

    @Test
    @DisplayName("metrics for a running server carry uptime, memory and TPS")
    void runningMetrics() throws Exception {
        log.lines = List.of(TPS);
        memory.residentMb = 3072L;

        ServerMetrics m = telemetry.getServerMetrics(running());

        assertEquals(600L, m.getServerUptimeSeconds());
        assertEquals(3072L, m.getServerMemoryMb());
        assertEquals(TPS, m.getTps());
        assertNull(m.getTpsNote());
    }

    @Test
    @DisplayName("metrics without a TPS line explain why")
    void noTpsNote() throws Exception {
        ServerMetrics m = telemetry.getServerMetrics(running());

        assertNull(m.getTps());
        assertEquals("No TPS data found in logs. TPS logging requires a Paper or Spigot server.", m.getTpsNote());
    }

    @Test
    @DisplayName("metrics with no server, or a dead one, say it is not running and skip the log")
    void notRunning() {
        assertEquals("Server is not running.", telemetry.getServerMetrics(null).getTpsNote());
        FakeRunningServer dead = new FakeRunningServer(1, Instant.EPOCH);
        assertEquals("Server is not running.", telemetry.getServerMetrics(dead).getTpsNote());
        assertEquals(0, log.reads);
    }
}
