package com.example.heimdall.gateway.service;

import com.example.heimdall.common.dto.ObjectMetadataDto;
import com.example.heimdall.common.ring.ConsistentHashRing;
import com.example.heimdall.gateway.config.GatewayProperties;
import com.example.heimdall.gateway.model.PrimaryNode;
import com.example.heimdall.gateway.model.ReplicaNode;
import com.example.heimdall.gateway.service.StreamingOrchestratorService.PreparedStream;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Uses a primary with exactly one replica everywhere a test asserts *which*
 * node was called, so replica-selection shuffling can never make the
 * candidate order (and therefore which Mockito stub fires) non-deterministic.
 */
@ExtendWith(MockitoExtension.class)
class StreamingOrchestratorServiceTest {

    private static final String OBJECT_ID = "clip.mp4";
    private static final int CHUNK_SIZE = 10;

    @Mock
    private NodeClient nodeClient;

    private ExecutorService executor;
    private StreamingOrchestratorService service;
    private ReplicaNode replica;
    private PrimaryNode primary;

    @BeforeEach
    void setUp() {
        executor = Executors.newFixedThreadPool(4);
        GatewayProperties properties = new GatewayProperties();
        properties.setMaxFetchAttempts(3);

        replica = new ReplicaNode("primary-0-replica-0", "http://replica-a");
        primary = new PrimaryNode("primary-0", "http://primary-0", List.of(replica));

        ConsistentHashRing<PrimaryNode> ring = new ConsistentHashRing<>(50);
        ring.addMember(primary.id(), primary);

        LoadBalancerService loadBalancerService = new LoadBalancerService(ring, properties);
        MeterRegistry meterRegistry = new SimpleMeterRegistry();
        service = new StreamingOrchestratorService(loadBalancerService, nodeClient, properties, executor, meterRegistry);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);
    }

    private ObjectMetadataDto meta(long totalSize, int chunkCount) {
        return new ObjectMetadataDto(OBJECT_ID, "video/mp4", "clip.mp4", totalSize, CHUNK_SIZE, chunkCount, Instant.now());
    }

    @Test
    void prepareFailsOverFromTheReplicaToThePrimaryWhenTheReplicaIsUnavailable() {
        when(nodeClient.fetchMeta(replica.baseUrl(), OBJECT_ID))
                .thenThrow(new NodeUnavailableException(replica.baseUrl(), new RuntimeException("boom")));
        when(nodeClient.fetchMeta(primary.baseUrl(), OBJECT_ID)).thenReturn(meta(25, 3));

        PreparedStream prepared = service.prepare(OBJECT_ID, 100);

        assertThat(prepared.meta().totalSize()).isEqualTo(25);
    }

    @Test
    void prepareThrowsNotFoundWhenNoCandidateHasTheObject() {
        when(nodeClient.fetchMeta(anyString(), eq(OBJECT_ID))).thenThrow(new NoSuchElementException("nope"));

        assertThatThrownBy(() -> service.prepare(OBJECT_ID, 100))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void streamReassemblesAllChunksInOrderForAFullRead() throws Exception {
        when(nodeClient.fetchMeta(anyString(), eq(OBJECT_ID))).thenReturn(meta(25, 3));
        PreparedStream prepared = service.prepare(OBJECT_ID, 100);

        stubChunk(0, "AAAAAAAAAA"); // 10 bytes
        stubChunk(1, "BBBBBBBBBB"); // 10 bytes
        stubChunk(2, "CCCCC");      // 5 bytes

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        service.stream(prepared, 0, 24, out);

        assertThat(out.toString()).isEqualTo("AAAAAAAAAABBBBBBBBBBCCCCC");
    }

    @Test
    void streamTrimsToExactlyTheRequestedByteRangeAcrossChunkBoundaries() throws Exception {
        when(nodeClient.fetchMeta(anyString(), eq(OBJECT_ID))).thenReturn(meta(30, 3));
        PreparedStream prepared = service.prepare(OBJECT_ID, 100);

        stubChunk(0, "0123456789");
        stubChunk(1, "ABCDEFGHIJ");
        // chunk 2 ("KLMNOPQRST") is never fetched: the requested range doesn't reach it

        // Request bytes 8-15 inclusive: "89" (end of chunk 0) + "ABCDEF" (start of chunk 1)
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        service.stream(prepared, 8, 15, out);

        assertThat(out.toString()).isEqualTo("89ABCDEF");
    }

    @Test
    void streamFailsOverToThePrimaryWhenTheReplicaChunkFetchFails() throws Exception {
        when(nodeClient.fetchMeta(anyString(), eq(OBJECT_ID))).thenReturn(meta(10, 1));
        PreparedStream prepared = service.prepare(OBJECT_ID, 100);

        when(nodeClient.fetchChunk(replica.baseUrl(), OBJECT_ID, 0))
                .thenThrow(new NodeUnavailableException(replica.baseUrl(), new RuntimeException("down")));
        when(nodeClient.fetchChunk(primary.baseUrl(), OBJECT_ID, 0))
                .thenReturn("HELLOHELLO".getBytes());

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        service.stream(prepared, 0, 9, out);

        assertThat(out.toString()).isEqualTo("HELLOHELLO");
    }

    private void stubChunk(int index, String content) {
        when(nodeClient.fetchChunk(anyString(), eq(OBJECT_ID), eq(index))).thenReturn(content.getBytes());
    }
}
