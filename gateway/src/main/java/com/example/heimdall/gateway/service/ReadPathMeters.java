package com.example.heimdall.gateway.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static com.example.heimdall.gateway.service.MetricsService.BYTES_SERVED;
import static com.example.heimdall.gateway.service.MetricsService.CHUNKS_SERVED;
import static com.example.heimdall.gateway.service.MetricsService.CHUNK_FETCH_FAILURES;
import static com.example.heimdall.gateway.service.MetricsService.CHUNK_FETCH_LATENCY;
import static com.example.heimdall.gateway.service.MetricsService.CHUNK_FETCH_LATENCY_OVERALL;
import static com.example.heimdall.gateway.service.MetricsService.READ_PREPARE_LATENCY;
import static com.example.heimdall.gateway.service.MetricsService.TAG_NODE;
import static com.example.heimdall.gateway.service.MetricsService.TAG_REASON;

/**
 * Records what each storage node did on the read path, tagged by node.
 *
 * <p>Per-node tagging is the point. An aggregate "average chunk latency" says
 * the cluster is working; per-node chunk counts say <em>load is spreading</em>,
 * which is the claim a load balancer has to back up. The tag value is the
 * node's base URL, the only identity available on the fetch path;
 * {@link MetricsService} maps it back to a node id when rendering. Cardinality
 * is bounded by cluster size.
 */
final class ReadPathMeters {

    private static final double[] PERCENTILES = {0.5, 0.95, 0.99};

    /**
     * Percentiles cover roughly the last minute. Cumulative percentiles would
     * make a live dashboard describe the gateway's whole lifetime, so a node
     * that recovered an hour ago would still look slow.
     */
    private static final Duration PERCENTILE_WINDOW = Duration.ofSeconds(60);

    private ReadPathMeters() {
    }

    static void chunkServed(MeterRegistry registry, String nodeBaseUrl, int bytes, long nanos) {
        windowedTimer(registry, CHUNK_FETCH_LATENCY, TAG_NODE, nodeBaseUrl).record(nanos, TimeUnit.NANOSECONDS);
        windowedTimer(registry, CHUNK_FETCH_LATENCY_OVERALL).record(nanos, TimeUnit.NANOSECONDS);
        registry.counter(CHUNKS_SERVED, TAG_NODE, nodeBaseUrl).increment();
        registry.counter(BYTES_SERVED, TAG_NODE, nodeBaseUrl).increment(bytes);
    }

    static void chunkFailed(MeterRegistry registry, String nodeBaseUrl, String reason) {
        registry.counter(CHUNK_FETCH_FAILURES, TAG_NODE, nodeBaseUrl, TAG_REASON, reason).increment();
    }

    /**
     * Time from receiving a read to having its plan: routing plus the metadata
     * round-trip. Benchmarking showed that round-trip is the largest share of
     * Heimdall's time-to-first-byte, so it gets its own timer.
     */
    static void readPrepared(MeterRegistry registry, long nanos) {
        windowedTimer(registry, READ_PREPARE_LATENCY).record(nanos, TimeUnit.NANOSECONDS);
    }

    private static Timer windowedTimer(MeterRegistry registry, String name, String... tags) {
        return Timer.builder(name)
                .tags(tags)
                .publishPercentiles(PERCENTILES)
                .distributionStatisticExpiry(PERCENTILE_WINDOW)
                .distributionStatisticBufferLength(3)
                .register(registry);
    }
}
