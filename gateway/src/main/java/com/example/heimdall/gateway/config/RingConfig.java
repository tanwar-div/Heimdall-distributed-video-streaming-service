package com.example.heimdall.gateway.config;

import com.example.heimdall.common.ring.ConsistentHashRing;
import com.example.heimdall.gateway.model.PrimaryNode;
import com.example.heimdall.gateway.model.ReplicaNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Builds the cluster topology from the static {@link ClusterProperties} at
 * startup: the flat list of {@link PrimaryNode}s (used anywhere the gateway
 * needs to enumerate every primary, e.g. a cluster-wide object listing) and
 * the {@link ConsistentHashRing} built from that same list (used for routing
 * a given object key to its owning primary). This is the gateway's
 * replacement for the old in-process ClusterInitializer: instead of
 * constructing PrimaryServer/ReplicaServer objects that live in this JVM, it
 * builds lightweight records that carry the *real* base URL of a separate
 * process the gateway will call over HTTP.
 */
@Configuration
public class RingConfig {

    private static final Logger log = LoggerFactory.getLogger(RingConfig.class);

    @Bean
    public List<PrimaryNode> primaryNodes(ClusterProperties properties) {
        return properties.getPrimaries().stream()
                .map(primaryConfig -> {
                    List<ReplicaNode> replicas = primaryConfig.getReplicas().stream()
                            .map(r -> new ReplicaNode(r.getId(), r.getBaseUrl()))
                            .toList();
                    return new PrimaryNode(primaryConfig.getId(), primaryConfig.getBaseUrl(), replicas);
                })
                .toList();
    }

    @Bean
    public ConsistentHashRing<PrimaryNode> primaryRing(ClusterProperties properties, List<PrimaryNode> primaryNodes) {
        ConsistentHashRing<PrimaryNode> ring = new ConsistentHashRing<>(properties.getVirtualNodes());
        for (PrimaryNode primary : primaryNodes) {
            ring.addMember(primary.id(), primary);
            log.info("[cluster] registered {} ({}) with {} replicas: {}",
                    primary.id(), primary.baseUrl(), primary.replicas().size(),
                    primary.replicas().stream().map(ReplicaNode::id).toList());
        }
        return ring;
    }
}
