package com.example.heimdall.bench.gateway;

import com.example.heimdall.common.ring.ConsistentHashRing;
import com.example.heimdall.common.stream.ChunkPlan;
import com.example.heimdall.gateway.config.GatewayProperties;
import com.example.heimdall.gateway.model.PrimaryNode;
import com.example.heimdall.gateway.model.ReplicaNode;
import com.example.heimdall.gateway.service.LoadBalancerService;
import com.example.heimdall.gateway.service.StreamingOrchestratorService;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The per-request CPU work the gateway does before a single byte moves:
 * choosing which replicas to read from, ordering the failover candidates, and
 * planning which chunks a byte range touches.
 *
 * <p>None of this involves I/O, so it is pure overhead added to every read.
 * The question these answer is whether it is negligible next to the network
 * round-trips it precedes - and specifically whether the per-read
 * {@code Collections.shuffle} in replica selection is worth what it buys.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 2, jvmArgs = {"-Xms1g", "-Xmx1g"})
public class ReadPathBenchmark {

    /** 2 is the shipped topology; 16 asks what happens when a primary fans out widely. */
    @Param({"2", "16"})
    public int replicasPerPrimary;

    private LoadBalancerService loadBalancer;
    private PrimaryNode primary;
    private List<ReplicaNode> selected;

    @Setup
    public void setUp() {
        GatewayProperties properties = new GatewayProperties();
        properties.setDefaultReadPercent(50);

        List<ReplicaNode> replicas = new ArrayList<>();
        for (int i = 0; i < replicasPerPrimary; i++) {
            replicas.add(new ReplicaNode("primary-0-replica-" + i, "http://replica-" + i + ":8081"));
        }
        primary = new PrimaryNode("primary-0", "http://primary-0:8081", replicas);

        ConsistentHashRing<PrimaryNode> ring = new ConsistentHashRing<>(100);
        ring.addMember(primary.id(), primary);
        ring.addMember("primary-1", new PrimaryNode("primary-1", "http://primary-1:8081", List.of()));

        loadBalancer = new LoadBalancerService(ring, properties);
        selected = loadBalancer.selectReadReplicas(primary, 50);
    }

    /** Shuffle-and-take: runs once per read, allocating a fresh list every time. */
    @Benchmark
    public List<ReplicaNode> selectReadReplicas() {
        return loadBalancer.selectReadReplicas(primary, 50);
    }

    /** Building the ordered failover list; was quadratic in replica count before the LinkedHashSet rewrite. */
    @Benchmark
    public List<String> buildCandidateOrder() {
        return StreamingOrchestratorService.buildCandidateOrder(primary, selected);
    }

    /** Planning a 4 MiB seek within a 1 GiB object at a 1 MiB chunk size - a video player scrubbing. */
    @Benchmark
    public void planSeekRange(Blackhole blackhole) {
        ChunkPlan plan = ChunkPlan.forRange(700_000_000L, 700_000_000L + 4 * 1024 * 1024 - 1, 1024 * 1024);
        for (int chunk = plan.firstChunk(); chunk <= plan.lastChunk(); chunk++) {
            blackhole.consume(plan.offsetWithin(chunk));
            blackhole.consume(plan.endWithin(chunk, 1024 * 1024));
        }
    }
}
