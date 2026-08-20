package com.example.heimdall.gateway.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.ArrayList;
import java.util.List;

/**
 * Static cluster topology: which primaries exist, and which replicas belong
 * to each. This is the gateway's equivalent of a service registry - instead
 * of dynamic discovery (Eureka/Consul), the topology is fixed config, matched
 * to whatever hostnames docker-compose (or a Kubernetes Service, etc.) gives
 * each node. That trade-off keeps the deployment story to "one compose file,
 * no extra discovery infrastructure" while every call between the gateway and
 * a node is still a real network call to a separate process.
 */
@Validated
@ConfigurationProperties(prefix = "heimdall.cluster")
public class ClusterProperties {

    @Positive
    private int virtualNodes = 100;

    @NotEmpty
    @Valid
    private List<PrimaryConfig> primaries = new ArrayList<>();

    public int getVirtualNodes() {
        return virtualNodes;
    }

    public void setVirtualNodes(int virtualNodes) {
        this.virtualNodes = virtualNodes;
    }

    public List<PrimaryConfig> getPrimaries() {
        return primaries;
    }

    public void setPrimaries(List<PrimaryConfig> primaries) {
        this.primaries = primaries;
    }

    public static class PrimaryConfig {
        @NotBlank
        private String id;
        @NotBlank
        private String baseUrl;
        @Valid
        private List<ReplicaConfig> replicas = new ArrayList<>();

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

        public List<ReplicaConfig> getReplicas() {
            return replicas;
        }

        public void setReplicas(List<ReplicaConfig> replicas) {
            this.replicas = replicas;
        }
    }

    public static class ReplicaConfig {
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
}
