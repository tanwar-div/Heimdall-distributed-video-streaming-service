package com.example.heimdall.gateway.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "heimdall")
public class GatewayProperties {

    /** Shared secret required in the X-API-Key header for POST/DELETE /objects/**. */
    @NotBlank
    private String apiKey = "changeme";

    /** Percentage of a primary's replicas used per read when the client doesn't specify one. */
    @Min(1)
    private int defaultReadPercent = 50;

    /** How many candidate nodes (selected replicas, other replicas, then the primary) a chunk fetch will try before giving up. */
    @Min(1)
    private int maxFetchAttempts = 4;

    /** Size of the bounded pool used to fetch chunks from multiple replicas concurrently. */
    @Min(1)
    private int fetchPoolSize = 16;

    private int nodeRequestTimeoutMs = 15_000;

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public int getDefaultReadPercent() {
        return defaultReadPercent;
    }

    public void setDefaultReadPercent(int defaultReadPercent) {
        this.defaultReadPercent = defaultReadPercent;
    }

    public int getMaxFetchAttempts() {
        return maxFetchAttempts;
    }

    public void setMaxFetchAttempts(int maxFetchAttempts) {
        this.maxFetchAttempts = maxFetchAttempts;
    }

    public int getFetchPoolSize() {
        return fetchPoolSize;
    }

    public void setFetchPoolSize(int fetchPoolSize) {
        this.fetchPoolSize = fetchPoolSize;
    }

    public int getNodeRequestTimeoutMs() {
        return nodeRequestTimeoutMs;
    }

    public void setNodeRequestTimeoutMs(int nodeRequestTimeoutMs) {
        this.nodeRequestTimeoutMs = nodeRequestTimeoutMs;
    }
}
