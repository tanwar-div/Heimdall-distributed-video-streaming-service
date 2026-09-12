package com.example.heimdall.gateway.dto;

import java.time.Instant;
import java.util.List;

/**
 * What the live showcase polls: how traffic is actually spreading across the
 * cluster right now, rather than totals that only ever go up.
 *
 * <p>Counts are cumulative since the gateway started. Latency percentiles are
 * <em>windowed</em> to roughly the last minute, so they describe current
 * behaviour - a latency spike five minutes ago does not linger in the p99. A
 * percentile is {@code null} when there has been no traffic in that window,
 * rather than a misleading {@code 0}.
 */
public record LiveMetricsDto(
        Instant generatedAt,
        long uptimeSeconds,
        Totals totals,
        Latency chunkFetchLatency,
        Latency readPrepareLatency,
        List<NodeTraffic> nodes) {

    public record Totals(double reads, double chunksServed, double bytesServed, double failovers, double uploads) {
    }

    public record Latency(long samples, Double meanMs, Double p50Ms, Double p95Ms, Double p99Ms, Double maxMs) {
        public static final Latency NONE = new Latency(0, null, null, null, null, null);
    }

    /** One storage node's share of the read traffic - the evidence that load balancing is happening. */
    public record NodeTraffic(String id, String role, String primaryId, String status,
                              double chunksServed, double bytesServed, double chunkFailures, Latency latency) {
    }
}
