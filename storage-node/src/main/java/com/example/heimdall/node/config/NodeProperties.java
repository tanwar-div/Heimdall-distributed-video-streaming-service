package com.example.heimdall.node.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration for a single storage node. The exact same jar is deployed as
 * either role; only these properties (set via environment variables in
 * docker-compose) decide whether an instance behaves as a primary or a replica.
 */
@Validated
@ConfigurationProperties(prefix = "heimdall.node")
public class NodeProperties {

    public enum Role { PRIMARY, REPLICA }

    /** This node's id, e.g. "primary-0" or "primary-0-replica-1". Also used as its MinIO bucket name. */
    @NotBlank
    private String id;

    private Role role = Role.REPLICA;

    /** Size, in bytes, of each chunk an object is split into on ingest. */
    @Positive
    private int chunkSizeBytes = 1_048_576; // 1 MiB - a sane default for video streaming

    /** Hard cap on a single object's total size. */
    @Positive
    private long maxObjectSizeBytes = 5L * 1024 * 1024 * 1024; // 5 GiB

    /** MIME types this node will accept on ingest. */
    private List<String> allowedContentTypes = new ArrayList<>(List.of(
            "video/mp4", "video/webm", "video/ogg", "video/quicktime",
            "video/x-matroska", "video/x-msvideo", "video/mpeg",
            "application/octet-stream"
    ));

    /** If this node is a PRIMARY, the replicas it fans writes out to. Ignored for REPLICA nodes. */
    private List<ReplicaTarget> replicas = new ArrayList<>();

    /** If true, an upload doesn't return until all replicas have acknowledged the copy. */
    private boolean synchronousReplication = false;

    private int replicationTimeoutMs = 30_000;
    private int replicationMaxRetries = 2;

    public static class ReplicaTarget {
        @NotBlank
        private String id;
        @NotBlank
        private String baseUrl;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public Role getRole() {
        return role;
    }

    public void setRole(Role role) {
        this.role = role;
    }

    public boolean isPrimary() {
        return role == Role.PRIMARY;
    }

    public int getChunkSizeBytes() {
        return chunkSizeBytes;
    }

    public void setChunkSizeBytes(int chunkSizeBytes) {
        this.chunkSizeBytes = chunkSizeBytes;
    }

    public long getMaxObjectSizeBytes() {
        return maxObjectSizeBytes;
    }

    public void setMaxObjectSizeBytes(long maxObjectSizeBytes) {
        this.maxObjectSizeBytes = maxObjectSizeBytes;
    }

    public List<String> getAllowedContentTypes() {
        return allowedContentTypes;
    }

    public void setAllowedContentTypes(List<String> allowedContentTypes) {
        this.allowedContentTypes = allowedContentTypes;
    }

    public List<ReplicaTarget> getReplicas() {
        return replicas;
    }

    public void setReplicas(List<ReplicaTarget> replicas) {
        this.replicas = replicas;
    }

    public boolean isSynchronousReplication() {
        return synchronousReplication;
    }

    public void setSynchronousReplication(boolean synchronousReplication) {
        this.synchronousReplication = synchronousReplication;
    }

    public int getReplicationTimeoutMs() {
        return replicationTimeoutMs;
    }

    public void setReplicationTimeoutMs(int replicationTimeoutMs) {
        this.replicationTimeoutMs = replicationTimeoutMs;
    }

    public int getReplicationMaxRetries() {
        return replicationMaxRetries;
    }

    public void setReplicationMaxRetries(int replicationMaxRetries) {
        this.replicationMaxRetries = replicationMaxRetries;
    }
}
