package com.example.heimdall.node.service;

import com.example.heimdall.common.dto.ObjectMetadataDto;
import com.example.heimdall.node.config.NodeProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.io.InputStream;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * Fans a freshly-stored object out to this primary's replicas over real HTTP
 * calls. Each replica gets the object's bytes re-streamed straight out of
 * MinIO (via {@link ChunkedObjectInputStream}), not held anywhere in between.
 *
 * <p>Replication is asynchronous by default: an upload is durable (in MinIO,
 * on the primary) and acknowledged to the client before replicas necessarily
 * have their copy, which is what "eventually consistent" replication means in
 * practice. Set {@code heimdall.node.synchronous-replication: true} to trade
 * upload latency for a stronger guarantee (the client's ack now means every
 * replica has the data too).
 */
@Service
public class ReplicationService {

    private static final Logger log = LoggerFactory.getLogger(ReplicationService.class);

    private final ChunkStorageService chunkStorageService;
    private final NodeProperties properties;
    private final RestClient restClient;
    private final ExecutorService replicationExecutor;

    public ReplicationService(ChunkStorageService chunkStorageService, NodeProperties properties,
                               RestClient nodeRestClient, ExecutorService replicationExecutor) {
        this.chunkStorageService = chunkStorageService;
        this.properties = properties;
        this.restClient = nodeRestClient;
        this.replicationExecutor = replicationExecutor;
    }

    public void replicate(ObjectMetadataDto meta) {
        List<NodeProperties.ReplicaTarget> replicas = properties.getReplicas();
        if (replicas.isEmpty()) {
            return;
        }

        List<CompletableFuture<Void>> futures = replicas.stream()
                .map(target -> CompletableFuture.runAsync(() -> replicateToOne(target, meta), replicationExecutor))
                .toList();

        if (properties.isSynchronousReplication()) {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        }
    }

    /** Best-effort cascade of a delete to every replica; one replica failing doesn't fail the others. */
    public void delete(String objectId) {
        for (NodeProperties.ReplicaTarget target : properties.getReplicas()) {
            try {
                restClient.delete()
                        .uri(target.getBaseUrl() + "/internal/objects/{objectId}", objectId)
                        .retrieve()
                        .toBodilessEntity();
            } catch (Exception e) {
                log.warn("[{}] failed to delete '{}' on replica {}: {}",
                        properties.getId(), objectId, target.getId(), e.toString());
            }
        }
    }

    private void replicateToOne(NodeProperties.ReplicaTarget target, ObjectMetadataDto meta) {
        int maxAttempts = properties.getReplicationMaxRetries() + 1;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try (InputStream body = new ChunkedObjectInputStream(chunkStorageService, meta.objectId(), meta.chunkCount())) {
                restClient.put()
                        .uri(target.getBaseUrl() + "/internal/objects/{objectId}", meta.objectId())
                        .header(HttpHeaders.CONTENT_TYPE, meta.contentType())
                        .header("X-Original-Filename", meta.originalFilename() == null ? "" : meta.originalFilename())
                        .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(meta.totalSize()))
                        .body(new InputStreamResource(body))
                        .retrieve()
                        .toBodilessEntity();
                log.info("[{}] replicated '{}' to {} (attempt {})", properties.getId(), meta.objectId(), target.getId(), attempt);
                return;
            } catch (Exception e) {
                if (attempt == maxAttempts) {
                    log.error("[{}] giving up replicating '{}' to {} after {} attempts: {}",
                            properties.getId(), meta.objectId(), target.getId(), attempt, e.toString());
                } else {
                    log.warn("[{}] replication attempt {} of '{}' to {} failed, retrying: {}",
                            properties.getId(), attempt, meta.objectId(), target.getId(), e.toString());
                }
            }
        }
    }
}
