package com.example.heimdall.bench.routing;

import com.example.heimdall.common.ring.KeyRouter;
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
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * How fast each routing algorithm answers "who owns this key?".
 *
 * <p>This runs on every single request the gateway serves, so it is the one
 * routing cost that is unambiguously on the hot path. The member counts span
 * the cluster this project actually deploys (2-8 primaries) up to a size
 * where rendezvous hashing's O(N) scan should visibly lose to the ring's
 * O(log V) tree walk - finding that crossover is the point.
 *
 * <p>Keys are drawn from a pre-generated pool with a bitmask index so the
 * benchmark measures routing, not key generation.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(value = 2, jvmArgs = {"-Xms1g", "-Xmx1g"})
@Threads(1)
public class RouterLookupBenchmark {

    private static final int KEY_POOL_SIZE = 8192; // power of two: index with a mask, no modulo

    @Param({"ring/crc32/v100", "ring/murmur3/v100", "ring/murmur3/v500", "ring/md5/v100",
            "ketama/md5/p160", "rendezvous/murmur3", "jump/murmur3"})
    public String variant;

    @Param({"2", "8", "32", "128"})
    public int members;

    private KeyRouter<String> router;
    private String[] keys;
    private int cursor;

    @Setup
    public void setUp() {
        router = RouterCatalog.byId(variant).build(members);
        keys = new String[KEY_POOL_SIZE];
        for (int i = 0; i < KEY_POOL_SIZE; i++) {
            keys[i] = "video-" + i + ".mp4";
        }
    }

    @Benchmark
    public void route(Blackhole blackhole) {
        cursor = (cursor + 1) & (KEY_POOL_SIZE - 1);
        blackhole.consume(router.route(keys[cursor]));
    }
}
