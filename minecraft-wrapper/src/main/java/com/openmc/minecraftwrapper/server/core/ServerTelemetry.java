package com.openmc.minecraftwrapper.server.core;

import com.openmc.minecraftwrapper.model.ServerMetrics;
import com.openmc.minecraftwrapper.server.ports.ProcessMemoryProbe;
import com.openmc.minecraftwrapper.server.ports.RunningServer;
import com.openmc.minecraftwrapper.server.ports.ServerLog;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** Recent log lines and the metrics snapshot (heap, uptime, RSS, TPS with a 30 s cache). */
public class ServerTelemetry {

    private record TpsCache(String tps, Instant fetchedAt) {}

    static final Duration TPS_CACHE_TTL = Duration.ofSeconds(30);

    /** Number of trailing log lines scanned for a Paper/Spigot TPS reading. */
    private static final int TPS_SCAN_LINES = 500;

    private final ServerLog serverLog;
    private final ProcessMemoryProbe memoryProbe;
    private final Clock clock;
    private final AtomicReference<TpsCache> tpsCacheRef =
            new AtomicReference<>(new TpsCache(null, Instant.EPOCH));

    public ServerTelemetry(ServerLog serverLog, ProcessMemoryProbe memoryProbe, Clock clock) {
        this.serverLog = serverLog;
        this.memoryProbe = memoryProbe;
        this.clock = clock;
    }

    /**
     * The last {@code maxLines} log lines, oldest first; empty when the log is missing or
     * unreadable. Clamping {@code maxLines} is the caller's job.
     */
    public List<String> getRecentLogLines(int maxLines) {
        return serverLog.tail(maxLines);
    }

    /**
     * Return a performance metrics snapshot for the Minecraft server and its JVM.
     *
     * <ul>
     *   <li><b>Wrapper JVM heap</b> — always available via {@link Runtime}.</li>
     *   <li><b>Server process memory</b> — from the {@link ProcessMemoryProbe};
     *       {@code null} when unavailable or when the server is not running.</li>
     *   <li><b>Server uptime</b> — from {@link RunningServer#startedAt()}.</li>
     *   <li><b>TPS</b> — the most recent {@code TPS from last 1m, 5m, 15m: …} line in the
     *       server log (Paper / Spigot servers only). {@code null} otherwise.</li>
     * </ul>
     *
     * @param server the current server, or {@code null} if none was ever started
     * @return metrics snapshot; individual fields may be {@code null} when unavailable
     */
    public ServerMetrics getServerMetrics(RunningServer server) {
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
        if (server != null && server.isAlive()) {
            long pid = server.pid();

            // Uptime from the process start time
            server.startedAt().ifPresent(start ->
                    builder.serverUptimeSeconds(Duration.between(start, clock.instant()).getSeconds()));

            // Resident memory
            builder.serverMemoryMb(memoryProbe.residentMb(pid));

            // TPS from log file
            String tps = latestTps();
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
     * Scan the tail of the server log for a Paper/Spigot TPS line.
     *
     * <p>A missing TPS line and an unreadable log are both cached for {@link #TPS_CACHE_TTL} so
     * the log is not re-scanned on every metrics request.
     *
     * @return the TPS segment starting from "TPS from last …", or {@code null} if not found
     */
    public String latestTps() {
        // Return cached value if it is still fresh (atomic read — both fields are in one record)
        TpsCache cached = tpsCacheRef.get();
        if (Duration.between(cached.fetchedAt(), clock.instant()).compareTo(TPS_CACHE_TTL) < 0) {
            return cached.tps();
        }
        // Collect the last 500 lines to avoid loading the full file
        List<String> tail = serverLog.tail(TPS_SCAN_LINES);
        // Scan backwards through the tail for the most recent TPS entry
        for (int i = tail.size() - 1; i >= 0; i--) {
            int idx = tail.get(i).indexOf("TPS from last");
            if (idx >= 0) {
                String result = tail.get(i).substring(idx);
                tpsCacheRef.set(new TpsCache(result, clock.instant()));
                return result;
            }
        }
        // No TPS line found — cache the null result to avoid re-scanning within the TTL window
        tpsCacheRef.set(new TpsCache(null, clock.instant()));
        return null;
    }
}
