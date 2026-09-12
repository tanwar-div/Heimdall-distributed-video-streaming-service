package com.example.heimdall.gateway.service;

import com.example.heimdall.gateway.dto.LiveMetricsDto;
import com.example.heimdall.gateway.dto.MetricsSummaryDto;
import com.example.heimdall.gateway.dto.NodeHealthDto;
import com.example.heimdall.gateway.model.PrimaryNode;
import com.example.heimdall.gateway.model.ReplicaNode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.HistogramSnapshot;
import io.micrometer.core.instrument.distribution.ValueAtPercentile;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

/**
 * Reads back the counters/timers {@link ObjectGatewayService} and
 * {@link StreamingOrchestratorService} record as they serve real traffic,
 * and aggregates them into the shapes the dashboard frontend wants. This
 * class never increments anything itself - it's read-only over whatever the
 * {@link MeterRegistry} already holds, plus a live reachability check
 * against every known node for the cluster health panel.
 *
 * <p>Both health and the live view are cached briefly. The live showcase is a
 * public page that every open browser tab polls, and an uncached health check
 * costs one HTTP call per node per poll per viewer.
 */
@Service
public class MetricsService {

    public static final String UPLOADS = "heimdall.gateway.uploads";
    public static final String DELETES = "heimdall.gateway.deletes";
    public static final String READS = "heimdall.gateway.reads";
    public static final String FAILOVERS = "heimdall.gateway.failovers";
    public static final String CHUNK_FETCH_LATENCY = "heimdall.gateway.chunk.fetch.latency";
    public static final String CHUNK_FETCH_LATENCY_OVERALL = "heimdall.gateway.chunk.fetch.latency.overall";
    public static final String CHUNKS_SERVED = "heimdall.gateway.chunks.served";
    public static final String BYTES_SERVED = "heimdall.gateway.bytes.served";
    public static final String CHUNK_FETCH_FAILURES = "heimdall.gateway.chunk.fetch.failures";
    public static final String READ_PREPARE_LATENCY = "heimdall.gateway.read.prepare.latency";

    public static final String TAG_OUTCOME = "outcome";
    public static final String TAG_PRIMARY = "primary";
    public static final String TAG_NODE = "node";
    public static final String TAG_REASON = "reason";
    public static final String OUTCOME_SUCCESS = "success";
    public static final String OUTCOME_FAILURE = "failure";

    private static final Duration HEALTH_CACHE_TTL = Duration.ofSeconds(3);
    private static final Duration LIVE_CACHE_TTL = Duration.ofSeconds(1);

    private record Cached<T>(T value, long takenAtNanos) {
        boolean isFresh(Duration ttl) {
            return System.nanoTime() - takenAtNanos < ttl.toNanos();
        }
    }

    private final MeterRegistry registry;
    private final RestClient nodeRestClient;
    private final List<PrimaryNode> primaryNodes;

    private volatile Cached<List<NodeHealthDto>> healthCache;
    private volatile Cached<LiveMetricsDto> liveCache;

    public MetricsService(MeterRegistry registry, RestClient nodeRestClient, List<PrimaryNode> primaryNodes) {
        this.registry = registry;
        this.nodeRestClient = nodeRestClient;
        this.primaryNodes = primaryNodes;
    }

    public MetricsSummaryDto summary() {
        double totalUploads = sum(UPLOADS, TAG_OUTCOME, OUTCOME_SUCCESS);
        double totalUploadFailures = sum(UPLOADS, TAG_OUTCOME, OUTCOME_FAILURE);
        double totalDeletes = sum(DELETES, TAG_OUTCOME, OUTCOME_SUCCESS);
        double totalDeleteFailures = sum(DELETES, TAG_OUTCOME, OUTCOME_FAILURE);
        double totalDownloads = sum(READS, null, null);
        double failoverCount = sum(FAILOVERS, null, null);

        Timer overall = registry.find(CHUNK_FETCH_LATENCY_OVERALL).timer();
        double avgLatencyMs = overall == null ? 0.0 : overall.mean(TimeUnit.MILLISECONDS);

        Map<String, Double> uploadsByPrimary = byTag(UPLOADS, TAG_OUTCOME, OUTCOME_SUCCESS, TAG_PRIMARY);
        Map<String, Double> readsByPrimary = byTag(READS, null, null, TAG_PRIMARY);

        return new MetricsSummaryDto(totalUploads, totalUploadFailures, totalDeletes, totalDeleteFailures,
                totalDownloads, failoverCount, avgLatencyMs, uploadsByPrimary, readsByPrimary);
    }

    public List<NodeHealthDto> health() {
        Cached<List<NodeHealthDto>> cached = healthCache;
        if (cached != null && cached.isFresh(HEALTH_CACHE_TTL)) {
            return cached.value();
        }
        List<NodeHealthDto> statuses = new ArrayList<>();
        for (PrimaryNode primary : primaryNodes) {
            statuses.add(new NodeHealthDto(primary.id(), primary.baseUrl(), "PRIMARY", checkHealth(primary.baseUrl())));
            for (ReplicaNode replica : primary.replicas()) {
                statuses.add(new NodeHealthDto(replica.id(), replica.baseUrl(), "REPLICA", checkHealth(replica.baseUrl())));
            }
        }
        List<NodeHealthDto> snapshot = List.copyOf(statuses);
        healthCache = new Cached<>(snapshot, System.nanoTime());
        return snapshot;
    }

    /** Per-node traffic, windowed latency percentiles and health, for the live showcase. */
    public LiveMetricsDto live() {
        Cached<LiveMetricsDto> cached = liveCache;
        if (cached != null && cached.isFresh(LIVE_CACHE_TTL)) {
            return cached.value();
        }

        Map<String, NodeHealthDto> healthByUrl = new HashMap<>();
        for (NodeHealthDto node : health()) {
            healthByUrl.put(node.baseUrl(), node);
        }

        List<LiveMetricsDto.NodeTraffic> nodes = new ArrayList<>();
        for (PrimaryNode primary : primaryNodes) {
            nodes.add(traffic(primary.id(), primary.baseUrl(), "PRIMARY", primary.id(), healthByUrl));
            for (ReplicaNode replica : primary.replicas()) {
                nodes.add(traffic(replica.id(), replica.baseUrl(), "REPLICA", primary.id(), healthByUrl));
            }
        }

        LiveMetricsDto.Totals totals = new LiveMetricsDto.Totals(
                sum(READS, null, null),
                nodes.stream().mapToDouble(LiveMetricsDto.NodeTraffic::chunksServed).sum(),
                nodes.stream().mapToDouble(LiveMetricsDto.NodeTraffic::bytesServed).sum(),
                sum(FAILOVERS, null, null),
                sum(UPLOADS, TAG_OUTCOME, OUTCOME_SUCCESS));

        LiveMetricsDto live = new LiveMetricsDto(
                Instant.now(),
                ManagementFactory.getRuntimeMXBean().getUptime() / 1000,
                totals,
                latency(registry.find(CHUNK_FETCH_LATENCY_OVERALL).timer()),
                latency(registry.find(READ_PREPARE_LATENCY).timer()),
                List.copyOf(nodes));
        liveCache = new Cached<>(live, System.nanoTime());
        return live;
    }

    private LiveMetricsDto.NodeTraffic traffic(String id, String baseUrl, String role, String primaryId,
                                                Map<String, NodeHealthDto> healthByUrl) {
        NodeHealthDto health = healthByUrl.get(baseUrl);
        return new LiveMetricsDto.NodeTraffic(
                id, role, primaryId,
                health == null ? "UNKNOWN" : health.status(),
                sum(CHUNKS_SERVED, TAG_NODE, baseUrl),
                sum(BYTES_SERVED, TAG_NODE, baseUrl),
                sum(CHUNK_FETCH_FAILURES, TAG_NODE, baseUrl),
                latency(registry.find(CHUNK_FETCH_LATENCY).tag(TAG_NODE, baseUrl).timer()));
    }

    private static LiveMetricsDto.Latency latency(Timer timer) {
        if (timer == null) {
            return LiveMetricsDto.Latency.NONE;
        }
        HistogramSnapshot snapshot = timer.takeSnapshot();
        double p50 = 0;
        double p95 = 0;
        double p99 = 0;
        for (ValueAtPercentile value : snapshot.percentileValues()) {
            double ms = value.value(TimeUnit.MILLISECONDS);
            if (value.percentile() == 0.5) {
                p50 = ms;
            } else if (value.percentile() == 0.95) {
                p95 = ms;
            } else if (value.percentile() == 0.99) {
                p99 = ms;
            }
        }
        // A network fetch never takes exactly 0 ms; all-zero percentiles mean
        // the window is empty, which the page should show as "no recent data".
        boolean windowEmpty = p50 == 0 && p95 == 0 && p99 == 0;
        long samples = snapshot.count();
        return new LiveMetricsDto.Latency(
                samples,
                samples == 0 ? null : snapshot.mean(TimeUnit.MILLISECONDS),
                windowEmpty ? null : p50,
                windowEmpty ? null : p95,
                windowEmpty ? null : p99,
                windowEmpty ? null : snapshot.max(TimeUnit.MILLISECONDS));
    }

    private String checkHealth(String baseUrl) {
        try {
            nodeRestClient.get().uri(baseUrl + "/actuator/health").retrieve().toBodilessEntity();
            return "UP";
        } catch (RestClientException e) {
            return "DOWN";
        }
    }

    private double sum(String name, String tagKey, String tagValue) {
        var search = registry.find(name);
        if (tagKey != null) {
            search = search.tag(tagKey, tagValue);
        }
        return search.counters().stream().mapToDouble(Counter::count).sum();
    }

    /** Sums a counter's value grouped by one tag, optionally filtered to a fixed value of another tag. */
    private Map<String, Double> byTag(String name, String filterKey, String filterValue, String groupByTag) {
        var search = registry.find(name);
        if (filterKey != null) {
            search = search.tag(filterKey, filterValue);
        }
        Map<String, Double> result = new TreeMap<>();
        for (Counter counter : search.counters()) {
            String tagValue = counter.getId().getTag(groupByTag);
            if (tagValue != null) {
                result.merge(tagValue, counter.count(), Double::sum);
            }
        }
        return result;
    }
}
