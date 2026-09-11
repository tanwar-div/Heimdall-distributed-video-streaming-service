package com.example.heimdall.gateway.service;

import com.example.heimdall.gateway.dto.MetricsSummaryDto;
import com.example.heimdall.gateway.dto.NodeHealthDto;
import com.example.heimdall.gateway.model.PrimaryNode;
import com.example.heimdall.gateway.model.ReplicaNode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.ArrayList;
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
 */
@Service
public class MetricsService {

    public static final String UPLOADS = "heimdall.gateway.uploads";
    public static final String DELETES = "heimdall.gateway.deletes";
    public static final String READS = "heimdall.gateway.reads";
    public static final String FAILOVERS = "heimdall.gateway.failovers";
    public static final String CHUNK_FETCH_LATENCY = "heimdall.gateway.chunk.fetch.latency";

    public static final String TAG_OUTCOME = "outcome";
    public static final String TAG_PRIMARY = "primary";
    public static final String OUTCOME_SUCCESS = "success";
    public static final String OUTCOME_FAILURE = "failure";

    private final MeterRegistry registry;
    private final RestClient nodeRestClient;
    private final List<PrimaryNode> primaryNodes;

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
        double failoverCount = registry.find(FAILOVERS).counters().stream().mapToDouble(Counter::count).sum();

        Timer chunkFetchTimer = registry.find(CHUNK_FETCH_LATENCY).timer();
        double avgLatencyMs = chunkFetchTimer == null ? 0.0 : chunkFetchTimer.mean(TimeUnit.MILLISECONDS);

        Map<String, Double> uploadsByPrimary = byTag(UPLOADS, TAG_OUTCOME, OUTCOME_SUCCESS, TAG_PRIMARY);
        Map<String, Double> readsByPrimary = byTag(READS, null, null, TAG_PRIMARY);

        return new MetricsSummaryDto(totalUploads, totalUploadFailures, totalDeletes, totalDeleteFailures,
                totalDownloads, failoverCount, avgLatencyMs, uploadsByPrimary, readsByPrimary);
    }

    public List<NodeHealthDto> health() {
        List<NodeHealthDto> statuses = new ArrayList<>();
        for (PrimaryNode primary : primaryNodes) {
            statuses.add(new NodeHealthDto(primary.id(), primary.baseUrl(), "PRIMARY", checkHealth(primary.baseUrl())));
            for (ReplicaNode replica : primary.replicas()) {
                statuses.add(new NodeHealthDto(replica.id(), replica.baseUrl(), "REPLICA", checkHealth(replica.baseUrl())));
            }
        }
        return statuses;
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
