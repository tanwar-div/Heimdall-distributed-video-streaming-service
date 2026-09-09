package com.example.heimdall.it;

import com.example.heimdall.gateway.GatewayApplication;
import com.example.heimdall.node.NodeApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Boots a genuine Heimdall cluster in one test JVM: each storage node and the
 * gateway is a separate Spring Boot application on its own real port, talking
 * to each other over real HTTP and to a real MinIO.
 *
 * <p>Separate contexts on separate ports rather than one context with mocked
 * collaborators, because almost everything worth testing at this level lives in
 * the gaps between the processes - HTTP status translation, streaming a body
 * through two hops without buffering it, a node being genuinely unreachable
 * rather than a mock throwing. A test that stubs those gaps out cannot fail in
 * any of the ways the real system does.
 *
 * <p>Ports are assigned by the OS ({@code server.port=0}) and read back after
 * startup, so the suite never collides with whatever else is running.
 */
public final class HeimdallCluster implements AutoCloseable {

    public record NodeHandle(String id, String baseUrl, ConfigurableApplicationContext context) {
        /**
         * Stops this node the way a crash would: the process goes away and
         * connections to it fail, rather than it politely returning errors.
         */
        public void kill() {
            context.close();
        }

        public boolean isRunning() {
            return context.isActive();
        }
    }

    private final List<NodeHandle> nodes = new ArrayList<>();
    private final Map<String, List<NodeHandle>> replicasByPrimary = new LinkedHashMap<>();
    private ConfigurableApplicationContext gatewayContext;
    private String gatewayUrl;
    private final String apiKey = "integration-test-key";

    private final String minioEndpoint;
    private final String minioAccessKey;
    private final String minioSecretKey;

    public HeimdallCluster(String minioEndpoint, String minioAccessKey, String minioSecretKey) {
        this.minioEndpoint = minioEndpoint;
        this.minioAccessKey = minioAccessKey;
        this.minioSecretKey = minioSecretKey;
    }

    /**
     * Starts {@code primaryCount} primaries, each with {@code replicasPerPrimary}
     * replicas, then the gateway pointed at all of them.
     */
    public HeimdallCluster start(int primaryCount, int replicasPerPrimary, boolean synchronousReplication) {
        for (int p = 0; p < primaryCount; p++) {
            String primaryId = "primary-" + p;
            List<NodeHandle> replicas = new ArrayList<>();

            // Replicas first: a primary needs its replicas' URLs at startup to
            // know where to fan writes out to.
            for (int r = 0; r < replicasPerPrimary; r++) {
                String replicaId = primaryId + "-replica-" + r;
                replicas.add(startNode(replicaId, "REPLICA", List.of(), false));
            }

            List<String> replicaUrls = replicas.stream().map(NodeHandle::baseUrl).toList();
            NodeHandle primary = startNode(primaryId, "PRIMARY", replicaUrls, synchronousReplication);

            nodes.add(primary);
            nodes.addAll(replicas);
            replicasByPrimary.put(primaryId, replicas);
        }

        startGateway();
        return this;
    }

    private NodeHandle startNode(String nodeId, String role, List<String> replicaUrls, boolean synchronousReplication) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("server.port", 0);
        properties.put("spring.main.banner-mode", "off");
        properties.put("heimdall.node.id", nodeId);
        properties.put("heimdall.node.role", role);
        properties.put("heimdall.node.chunk-size-bytes", 64 * 1024);
        properties.put("heimdall.node.synchronous-replication", synchronousReplication);
        properties.put("minio.endpoint", minioEndpoint);
        properties.put("minio.access-key", minioAccessKey);
        properties.put("minio.secret-key", minioSecretKey);
        for (int i = 0; i < replicaUrls.size(); i++) {
            properties.put("heimdall.node.replicas[" + i + "].id", nodeId + "-replica-" + i);
            properties.put("heimdall.node.replicas[" + i + "].base-url", replicaUrls.get(i));
        }

        ConfigurableApplicationContext context = new SpringApplicationBuilder(NodeApplication.class)
                .properties(properties)
                .run();
        return new NodeHandle(nodeId, "http://localhost:" + port(context), context);
    }

    private void startGateway() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("server.port", 0);
        properties.put("spring.main.banner-mode", "off");
        properties.put("heimdall.api-key", apiKey);
        properties.put("heimdall.default-read-percent", 100);
        properties.put("heimdall.max-fetch-attempts", 8);

        int p = 0;
        for (Map.Entry<String, List<NodeHandle>> entry : replicasByPrimary.entrySet()) {
            String primaryId = entry.getKey();
            properties.put("heimdall.cluster.primaries[" + p + "].id", primaryId);
            properties.put("heimdall.cluster.primaries[" + p + "].base-url", node(primaryId).baseUrl());
            List<NodeHandle> replicas = entry.getValue();
            for (int r = 0; r < replicas.size(); r++) {
                properties.put("heimdall.cluster.primaries[" + p + "].replicas[" + r + "].id", replicas.get(r).id());
                properties.put("heimdall.cluster.primaries[" + p + "].replicas[" + r + "].base-url",
                        replicas.get(r).baseUrl());
            }
            p++;
        }

        gatewayContext = new SpringApplicationBuilder(GatewayApplication.class).properties(properties).run();
        gatewayUrl = "http://localhost:" + port(gatewayContext);
    }

    private static int port(ConfigurableApplicationContext context) {
        Environment environment = context.getEnvironment();
        String port = environment.getProperty("local.server.port");
        if (port == null) {
            throw new IllegalStateException("application did not publish local.server.port");
        }
        return Integer.parseInt(port);
    }

    public String gatewayUrl() {
        return gatewayUrl;
    }

    public String apiKey() {
        return apiKey;
    }

    public NodeHandle node(String id) {
        return nodes.stream()
                .filter(n -> n.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no such node: " + id));
    }

    public List<NodeHandle> replicasOf(String primaryId) {
        return replicasByPrimary.get(primaryId);
    }

    public List<NodeHandle> nodes() {
        return List.copyOf(nodes);
    }

    @Override
    public void close() {
        if (gatewayContext != null) {
            gatewayContext.close();
        }
        for (NodeHandle node : nodes) {
            if (node.isRunning()) {
                node.context().close();
            }
        }
    }
}
