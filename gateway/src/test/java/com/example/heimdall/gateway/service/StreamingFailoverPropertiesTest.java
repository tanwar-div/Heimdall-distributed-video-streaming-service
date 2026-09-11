package com.example.heimdall.gateway.service;

import com.example.heimdall.common.dto.ObjectMetadataDto;
import com.example.heimdall.common.ring.ConsistentHashRing;
import com.example.heimdall.gateway.config.GatewayProperties;
import com.example.heimdall.gateway.model.PrimaryNode;
import com.example.heimdall.gateway.model.ReplicaNode;
import com.example.heimdall.gateway.service.StreamingOrchestratorService.PreparedStream;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What the read path guarantees when nodes are failing underneath it.
 *
 * <p>The existing orchestrator test proves failover works for one hand-built
 * scenario: one replica down, one replica up. The interesting failures are the
 * combinatorial ones - several nodes down at once, different nodes down for
 * different chunks, a node that is reachable but has not been replicated to
 * yet - and there are too many combinations to enumerate by hand. So instead of
 * choosing scenarios, this generates them, and asserts the two things that must
 * hold across all of them:
 *
 * <ol>
 *   <li><b>Availability.</b> As long as at least one node in the candidate list
 *       holds a chunk, and the attempt budget is large enough to reach it, the
 *       read succeeds. Failing a read that could have been served is a real
 *       outage caused by the gateway rather than by the failure.
 *   <li><b>Integrity.</b> When a read does succeed, the bytes are exactly right.
 *       Failover reorders which node answers each chunk, so a mistake in the
 *       retry logic could plausibly return the wrong chunk's bytes rather than
 *       an error - which is far worse, because nothing reports it.
 * </ol>
 */
class StreamingFailoverPropertiesTest {

    private static final String OBJECT_ID = "clip.mp4";
    private static final int CHUNK_SIZE = 16;

    /**
     * A fake cluster where each node independently either holds the object,
     * is missing it (not yet replicated), or is unreachable.
     */
    private static final class FakeCluster {
        enum NodeState { HEALTHY, MISSING_OBJECT, UNREACHABLE }

        private final byte[] object;
        private final List<String> nodeUrls;
        private final List<NodeState> states;
        final AtomicInteger chunkFetches = new AtomicInteger();

        FakeCluster(byte[] object, List<String> nodeUrls, List<NodeState> states) {
            this.object = object;
            this.nodeUrls = nodeUrls;
            this.states = states;
        }

        NodeState stateOf(String baseUrl) {
            return states.get(nodeUrls.indexOf(baseUrl));
        }

        boolean anyHealthy() {
            return states.contains(NodeState.HEALTHY);
        }

        NodeClient asNodeClient() {
            return new NodeClient(null, null) {
                @Override
                public ObjectMetadataDto fetchMeta(String baseUrl, String objectId) {
                    switch (stateOf(baseUrl)) {
                        case UNREACHABLE -> throw new NodeUnavailableException(baseUrl, new RuntimeException("down"));
                        case MISSING_OBJECT -> throw new NoSuchElementException("not replicated yet");
                        default -> { }
                    }
                    int chunkCount = Math.max(1, (object.length + CHUNK_SIZE - 1) / CHUNK_SIZE);
                    return new ObjectMetadataDto(objectId, "video/mp4", "clip.mp4",
                            object.length, CHUNK_SIZE, chunkCount, Instant.EPOCH);
                }

                @Override
                public byte[] fetchChunk(String baseUrl, String objectId, int index) {
                    chunkFetches.incrementAndGet();
                    switch (stateOf(baseUrl)) {
                        case UNREACHABLE -> throw new NodeUnavailableException(baseUrl, new RuntimeException("down"));
                        case MISSING_OBJECT -> throw new NoSuchElementException("not replicated yet");
                        default -> { }
                    }
                    int from = index * CHUNK_SIZE;
                    if (from >= object.length) {
                        return new byte[0];
                    }
                    return Arrays.copyOfRange(object, from, Math.min(object.length, from + CHUNK_SIZE));
                }
            };
        }
    }

    private record Harness(StreamingOrchestratorService service, PrimaryNode primary,
                           FakeCluster cluster, ExecutorService executor) implements AutoCloseable {
        @Override
        public void close() {
            executor.shutdownNow();
        }
    }

    private static Harness harness(byte[] object, List<FakeCluster.NodeState> states, int maxFetchAttempts) {
        List<ReplicaNode> replicas = new ArrayList<>();
        List<String> urls = new ArrayList<>();
        // states[0..n-2] are the replicas; the last is the primary, matching
        // the candidate order the orchestrator builds.
        for (int i = 0; i < states.size() - 1; i++) {
            String url = "http://replica-" + i;
            replicas.add(new ReplicaNode("primary-0-replica-" + i, url));
            urls.add(url);
        }
        urls.add("http://primary-0");
        PrimaryNode primary = new PrimaryNode("primary-0", "http://primary-0", replicas);

        FakeCluster cluster = new FakeCluster(object, urls, states);

        GatewayProperties properties = new GatewayProperties();
        properties.setMaxFetchAttempts(maxFetchAttempts);
        properties.setDefaultReadPercent(100);

        ConsistentHashRing<PrimaryNode> ring = new ConsistentHashRing<>(50);
        ring.addMember(primary.id(), primary);

        ExecutorService executor = Executors.newFixedThreadPool(4);
        StreamingOrchestratorService service = new StreamingOrchestratorService(
                new LoadBalancerService(ring, properties), cluster.asNodeClient(),
                properties, executor, new SimpleMeterRegistry());

        return new Harness(service, primary, cluster, executor);
    }

    private static List<FakeCluster.NodeState> statesFrom(int bitmap, int nodeCount) {
        // Two bits per node: 00/01 healthy, 10 missing, 11 unreachable.
        List<FakeCluster.NodeState> states = new ArrayList<>(nodeCount);
        for (int i = 0; i < nodeCount; i++) {
            int bits = (bitmap >> (i * 2)) & 0b11;
            states.add(switch (bits) {
                case 0b10 -> FakeCluster.NodeState.MISSING_OBJECT;
                case 0b11 -> FakeCluster.NodeState.UNREACHABLE;
                default -> FakeCluster.NodeState.HEALTHY;
            });
        }
        return states;
    }

    private static byte[] payload(int size) {
        byte[] data = new byte[size];
        new Random(size * 7919L).nextBytes(data);
        return data;
    }

    @Property(tries = 400)
    void aReadSucceedsWithExactBytesWheneverAnyNodeStillHasTheObject(
            @ForAll @IntRange(min = 2, max = 5) int nodeCount,
            @ForAll @IntRange(min = 0, max = 1023) int failureBitmap,
            @ForAll @IntRange(min = 1, max = 200) int objectSize) throws IOException {

        List<FakeCluster.NodeState> states = statesFrom(failureBitmap, nodeCount);
        byte[] object = payload(objectSize);

        // An attempt budget of nodeCount means "try every candidate before
        // giving up", which is the configuration under which the availability
        // guarantee is unconditional.
        try (Harness h = harness(object, states, nodeCount)) {
            if (!h.cluster().anyHealthy()) {
                assertThatThrownBy(() -> h.service().prepare(OBJECT_ID, 100))
                        .isInstanceOfAny(NoSuchElementException.class, NodeUnavailableException.class);
                return;
            }

            PreparedStream prepared = h.service().prepare(OBJECT_ID, 100);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            h.service().stream(prepared, 0, object.length - 1, out);

            assertThat(out.toByteArray())
                    .as("states=%s", states)
                    .isEqualTo(object);
        }
    }

    @Property(tries = 300)
    void anyByteRangeIsServedCorrectlyEvenWhileNodesAreFailing(
            @ForAll @IntRange(min = 2, max = 5) int nodeCount,
            @ForAll @IntRange(min = 0, max = 1023) int failureBitmap,
            @ForAll @IntRange(min = 1, max = 200) int objectSize,
            @ForAll @IntRange(min = 0, max = 199) int rawStart,
            @ForAll @IntRange(min = 0, max = 199) int rawLength) throws IOException {

        List<FakeCluster.NodeState> states = statesFrom(failureBitmap, nodeCount);
        byte[] object = payload(objectSize);

        try (Harness h = harness(object, states, nodeCount)) {
            if (!h.cluster().anyHealthy()) {
                return; // availability under total failure is covered above
            }

            long start = Math.min(rawStart, objectSize - 1);
            long end = Math.min(start + rawLength, objectSize - 1);

            PreparedStream prepared = h.service().prepare(OBJECT_ID, 100);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            h.service().stream(prepared, start, end, out);

            assertThat(out.toByteArray())
                    .as("range [%d,%d] of %d bytes, states=%s", start, end, objectSize, states)
                    .isEqualTo(Arrays.copyOfRange(object, (int) start, (int) end + 1));
        }
    }

    @Test
    void readsSpreadAcrossReplicasRatherThanHammeringOne() throws IOException {
        // Load *balancing* is the point of the replica set: an object big
        // enough to span many chunks must not have every chunk served by the
        // same node, or the replicas are pure redundancy and buy no capacity.
        List<FakeCluster.NodeState> allHealthy = List.of(
                FakeCluster.NodeState.HEALTHY, FakeCluster.NodeState.HEALTHY,
                FakeCluster.NodeState.HEALTHY, FakeCluster.NodeState.HEALTHY);
        byte[] object = payload(CHUNK_SIZE * 24);

        Set<String> nodesUsed = new HashSet<>();
        try (Harness h = harness(object, allHealthy, 4)) {
            NodeClient recording = new NodeClient(null, null) {
                private final NodeClient delegate = h.cluster().asNodeClient();

                @Override
                public ObjectMetadataDto fetchMeta(String baseUrl, String objectId) {
                    return delegate.fetchMeta(baseUrl, objectId);
                }

                @Override
                public byte[] fetchChunk(String baseUrl, String objectId, int index) {
                    synchronized (nodesUsed) {
                        nodesUsed.add(baseUrl);
                    }
                    return delegate.fetchChunk(baseUrl, objectId, index);
                }
            };

            GatewayProperties properties = new GatewayProperties();
            properties.setMaxFetchAttempts(4);
            properties.setDefaultReadPercent(100);
            ConsistentHashRing<PrimaryNode> ring = new ConsistentHashRing<>(50);
            ring.addMember(h.primary().id(), h.primary());
            ExecutorService executor = Executors.newFixedThreadPool(4);
            try {
                StreamingOrchestratorService service = new StreamingOrchestratorService(
                        new LoadBalancerService(ring, properties), recording, properties,
                        executor, new SimpleMeterRegistry());
                PreparedStream prepared = service.prepare(OBJECT_ID, 100);
                service.stream(prepared, 0, object.length - 1, new ByteArrayOutputStream());
            } finally {
                executor.shutdownNow();
            }
        }

        assertThat(nodesUsed)
                .as("24 chunks across 3 selected replicas should touch more than one node")
                .hasSizeGreaterThan(1);
    }

    @Test
    void fallsBackToThePrimaryWhenNoReplicaHasBeenReplicatedToYet() throws IOException {
        // The eventual-consistency window: the upload is acknowledged, replicas
        // have not caught up, and a read arriving in that window must still work.
        List<FakeCluster.NodeState> replicasBehind = List.of(
                FakeCluster.NodeState.MISSING_OBJECT,
                FakeCluster.NodeState.MISSING_OBJECT,
                FakeCluster.NodeState.HEALTHY); // the primary, last in candidate order
        byte[] object = payload(100);

        try (Harness h = harness(object, replicasBehind, 3)) {
            PreparedStream prepared = h.service().prepare(OBJECT_ID, 100);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            h.service().stream(prepared, 0, object.length - 1, out);
            assertThat(out.toByteArray()).isEqualTo(object);
        }
    }
}
