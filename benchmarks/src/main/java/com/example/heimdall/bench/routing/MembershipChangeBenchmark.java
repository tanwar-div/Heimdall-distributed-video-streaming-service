package com.example.heimdall.bench.routing;

import com.example.heimdall.common.ring.KeyRouter;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.TimeUnit;

/**
 * The cost of a node joining or leaving.
 *
 * <p>Off the request path, but it is the number that decides whether the
 * "possible next step" of dynamic service discovery is viable: with discovery,
 * membership changes stop being a restart-time event and become something that
 * happens live, under load, while holding the ring's write lock and blocking
 * every concurrent lookup. A ring at 500 virtual nodes pays 500 hashes and 500
 * red-black tree insertions per join; rendezvous and jump pay one array copy.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 2, jvmArgs = {"-Xms1g", "-Xmx1g"})
public class MembershipChangeBenchmark {

    @Param({"ring/crc32/v100", "ring/murmur3/v100", "ring/murmur3/v500",
            "ketama/md5/p160", "rendezvous/murmur3", "jump/murmur3"})
    public String variant;

    @Param({"8", "128"})
    public int members;

    private RouterCatalog.Variant catalogEntry;
    private KeyRouter<String> router;

    @Setup(Level.Iteration)
    public void setUp() {
        catalogEntry = RouterCatalog.byId(variant);
        router = catalogEntry.build(members);
    }

    /** A node joins, then leaves - one full membership churn cycle, leaving state unchanged for the next invocation. */
    @Benchmark
    public int addThenRemoveMember() {
        String joining = RouterCatalog.memberId(members);
        router.addMember(joining, joining);
        router.removeMember(joining);
        return router.memberCount();
    }
}
