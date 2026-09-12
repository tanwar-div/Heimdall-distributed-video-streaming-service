package com.example.heimdall.gateway.service;

import com.example.heimdall.common.dto.ObjectMetadataDto;
import com.example.heimdall.common.stream.ChunkPlan;
import com.example.heimdall.gateway.config.GatewayProperties;
import com.example.heimdall.gateway.model.PrimaryNode;
import com.example.heimdall.gateway.model.ReplicaNode;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;

import static com.example.heimdall.gateway.service.MetricsService.FAILOVERS;
import static com.example.heimdall.gateway.service.MetricsService.READS;
import static com.example.heimdall.gateway.service.MetricsService.TAG_PRIMARY;

/**
 * The read path. Resolves the owning primary, picks a percentage of its
 * replicas to read from, then - for whichever byte range was requested -
 * fetches only the chunks that overlap it, concurrently and with automatic
 * failover to other candidate nodes, and writes them to the client's output
 * stream in the original order.
 */
@Service
public class StreamingOrchestratorService {

    private final LoadBalancerService loadBalancerService;
    private final NodeClient nodeClient;
    private final GatewayProperties properties;
    private final ExecutorService streamingExecutor;
    private final MeterRegistry meterRegistry;

    public StreamingOrchestratorService(LoadBalancerService loadBalancerService, NodeClient nodeClient,
                                         GatewayProperties properties, ExecutorService streamingExecutor,
                                         MeterRegistry meterRegistry) {
        this.loadBalancerService = loadBalancerService;
        this.nodeClient = nodeClient;
        this.properties = properties;
        this.streamingExecutor = streamingExecutor;
        this.meterRegistry = meterRegistry;
    }

    /** A resolved read plan: which nodes to try, in what order, and the object's metadata. */
    public record PreparedStream(String objectId, ObjectMetadataDto meta, List<String> candidates, int selectedReplicaCount) {
    }

    public PreparedStream prepare(String objectId, Integer readPercent) {
        long startNanos = System.nanoTime();
        PrimaryNode primary = loadBalancerService.resolvePrimary(objectId);
        List<ReplicaNode> selected = loadBalancerService.selectReadReplicas(primary, readPercent);
        List<String> candidates = buildCandidateOrder(primary, selected);
        ObjectMetadataDto meta = fetchMetaWithFailover(candidates, objectId);
        ReadPathMeters.readPrepared(meterRegistry, System.nanoTime() - startNanos);
        meterRegistry.counter(READS, TAG_PRIMARY, primary.id()).increment();
        return new PreparedStream(objectId, meta, candidates, selected.size());
    }

    /**
     * Selected replicas first (in their randomly-shuffled order), then this
     * primary's other replicas, then the primary itself as a last resort.
     *
     * <p>A {@link LinkedHashSet} rather than a list-with-contains-check: this
     * runs once per read, and the list form was quadratic in replica count.
     * That is invisible at two replicas and is not at fifty.
     */
    public static List<String> buildCandidateOrder(PrimaryNode primary, List<ReplicaNode> selected) {
        LinkedHashSet<String> order = new LinkedHashSet<>(
                (selected.size() + primary.replicas().size() + 1) * 2);
        for (ReplicaNode r : selected) {
            order.add(r.baseUrl());
        }
        for (ReplicaNode r : primary.replicas()) {
            order.add(r.baseUrl());
        }
        order.add(primary.baseUrl());
        return List.copyOf(order);
    }

    private ObjectMetadataDto fetchMetaWithFailover(List<String> candidates, String objectId) {
        RuntimeException last = null;
        for (String baseUrl : candidates) {
            try {
                return nodeClient.fetchMeta(baseUrl, objectId);
            } catch (NoSuchElementException | NodeUnavailableException e) {
                last = e;
            }
        }
        throw last != null ? last : new NoSuchElementException("Object not found: " + objectId);
    }

    /** Streams the inclusive byte range [start, end] of the object to {@code out}. */
    public void stream(PreparedStream prepared, long start, long end, OutputStream out) throws IOException {
        if (end < start) {
            return;
        }
        ChunkPlan plan = ChunkPlan.forRange(start, end, prepared.meta().chunkSize());
        int firstChunk = plan.firstChunk();
        int selectedCount = prepared.selectedReplicaCount();

        List<CompletableFuture<byte[]>> futures = new ArrayList<>(plan.chunkCount());
        for (int chunkIndex = firstChunk; chunkIndex <= plan.lastChunk(); chunkIndex++) {
            int startCandidate = selectedCount > 0 ? (chunkIndex % selectedCount) : 0;
            int idx = chunkIndex;
            futures.add(CompletableFuture.supplyAsync(
                    () -> fetchChunkWithFailover(prepared.candidates(), startCandidate, idx, prepared.objectId()),
                    streamingExecutor));
        }

        try {
            for (int i = 0; i < futures.size(); i++) {
                int chunkIndex = firstChunk + i;
                byte[] chunk = futures.get(i).join();
                int from = plan.offsetWithin(chunkIndex);
                int to = plan.endWithin(chunkIndex, chunk.length);
                if (from < to) {
                    out.write(chunk, from, to - from);
                }
            }
        } catch (CompletionException e) {
            if (e.getCause() instanceof NoSuchElementException nse) {
                throw nse;
            }
            if (e.getCause() instanceof NodeUnavailableException nue) {
                throw nue;
            }
            throw e;
        }
    }

    private byte[] fetchChunkWithFailover(List<String> candidates, int startCandidateIndex, int chunkIndex, String objectId) {
        int attempts = Math.min(properties.getMaxFetchAttempts(), candidates.size());
        RuntimeException last = null;
        for (int a = 0; a < attempts; a++) {
            String baseUrl = candidates.get((startCandidateIndex + a) % candidates.size());
            long startNanos = System.nanoTime();
            try {
                byte[] chunk = nodeClient.fetchChunk(baseUrl, objectId, chunkIndex);
                ReadPathMeters.chunkServed(meterRegistry, baseUrl, chunk.length, System.nanoTime() - startNanos);
                if (a > 0) {
                    meterRegistry.counter(FAILOVERS).increment();
                }
                return chunk;
            } catch (NoSuchElementException e) {
                ReadPathMeters.chunkFailed(meterRegistry, baseUrl, "missing");
                last = e;
            } catch (NodeUnavailableException e) {
                ReadPathMeters.chunkFailed(meterRegistry, baseUrl, "unavailable");
                last = e;
            }
        }
        throw last != null ? last : new NoSuchElementException("Chunk " + chunkIndex + " of " + objectId + " not found");
    }
}
