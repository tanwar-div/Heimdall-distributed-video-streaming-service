package com.example.heimdall.gateway.service;

import com.example.heimdall.common.ring.ConsistentHashRing;
import com.example.heimdall.gateway.config.GatewayProperties;
import com.example.heimdall.gateway.model.PrimaryNode;
import com.example.heimdall.gateway.model.ReplicaNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LoadBalancerServiceTest {

    private ConsistentHashRing<PrimaryNode> ring;
    private GatewayProperties properties;
    private LoadBalancerService service;
    private PrimaryNode primaryWithThreeReplicas;

    @BeforeEach
    void setUp() {
        ring = new ConsistentHashRing<>(100);
        properties = new GatewayProperties();
        properties.setDefaultReadPercent(50);

        List<ReplicaNode> replicas = List.of(
                new ReplicaNode("primary-0-replica-0", "http://r0"),
                new ReplicaNode("primary-0-replica-1", "http://r1"),
                new ReplicaNode("primary-0-replica-2", "http://r2"));
        primaryWithThreeReplicas = new PrimaryNode("primary-0", "http://p0", replicas);
        ring.addMember("primary-0", primaryWithThreeReplicas);
        ring.addMember("primary-1", new PrimaryNode("primary-1", "http://p1", List.of()));

        service = new LoadBalancerService(ring, properties);
    }

    @Test
    void resolvePrimaryIsDeterministicForTheSameKey() {
        PrimaryNode first = service.resolvePrimary("my-video.mp4");
        for (int i = 0; i < 20; i++) {
            assertThat(service.resolvePrimary("my-video.mp4")).isEqualTo(first);
        }
    }

    @Test
    void selectReadReplicasHonorsAnExplicitPercent() {
        List<ReplicaNode> selected = service.selectReadReplicas(primaryWithThreeReplicas, 66);
        assertThat(selected).hasSize(2); // ceil(3 * 0.66) = 2
        assertThat(primaryWithThreeReplicas.replicas()).containsAll(selected);
    }

    @Test
    void selectReadReplicasFallsBackToTheConfiguredDefaultWhenPercentIsNull() {
        List<ReplicaNode> selected = service.selectReadReplicas(primaryWithThreeReplicas, null);
        assertThat(selected).hasSize(2); // ceil(3 * 0.50) = 2
    }

    @Test
    void selectReadReplicasClampsOutOfRangePercentages() {
        assertThat(service.selectReadReplicas(primaryWithThreeReplicas, -5)).hasSize(1); // clamped to 1%, ceil(3*0.01)=1
        assertThat(service.selectReadReplicas(primaryWithThreeReplicas, 500)).hasSize(3); // clamped to 100%
    }

    @Test
    void selectReadReplicasReturnsEmptyForAPrimaryWithNoReplicas() {
        PrimaryNode lonely = new PrimaryNode("primary-1", "http://p1", List.of());
        assertThat(service.selectReadReplicas(lonely, 50)).isEmpty();
    }
}
