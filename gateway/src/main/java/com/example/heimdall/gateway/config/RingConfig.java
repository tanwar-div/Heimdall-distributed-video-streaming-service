package com.example.heimdall.gateway.config;

import com.example.heimdall.common.hash.HashFunctions;
import com.example.heimdall.common.ring.ConsistentHashRing;
import com.example.heimdall.common.ring.KetamaRing;
import com.example.heimdall.common.ring.KeyRouter;
import com.example.heimdall.common.ring.RendezvousRouter;
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

    /**
     * The key-to-primary router, built from {@code heimdall.cluster.router}.
     *
     * <p>Defaults to rendezvous hashing rather than the ring. That is a
     * measured decision, not a preference: at this cluster's size the ring's
     * busiest primary was carrying 20-36% more than its fair share of the
     * keyspace, while rendezvous holds every primary within 2% of even and
     * moves the theoretically minimal number of keys when a node joins or
     * leaves - using a few hundred bytes of routing state instead of tens of
     * kilobytes. Its O(N) lookup is the trade, and it is a good one only while
     * N stays small; see BENCHMARKS.md for the numbers and the crossover.
     */
    @Bean
    public KeyRouter<PrimaryNode> primaryRouter(ClusterProperties properties, List<PrimaryNode> primaryNodes) {
        KeyRouter<PrimaryNode> router = switch (properties.getRouter()) {
            case RING -> new ConsistentHashRing<>(properties.getVirtualNodes(),
                    HashFunctions.byName(properties.getHashFunction()));
            case KETAMA -> new KetamaRing<>();
            case RENDEZVOUS -> new RendezvousRouter<>(HashFunctions.byName(properties.getHashFunction()));
        };

        for (PrimaryNode primary : primaryNodes) {
            router.addMember(primary.id(), primary);
            log.info("[cluster] registered {} ({}) with {} replicas: {}",
                    primary.id(), primary.baseUrl(), primary.replicas().size(),
                    primary.replicas().stream().map(ReplicaNode::id).toList());
        }
        log.info("[cluster] routing {} primaries with {} ({} of routing state)",
                primaryNodes.size(), router.name(), router.approximateFootprintBytes() + " bytes");
        return router;
    }
}
