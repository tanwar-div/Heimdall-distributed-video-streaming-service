package com.example.heimdall.bench.routing;

import com.example.heimdall.common.hash.HashFunction;
import com.example.heimdall.common.hash.HashFunctions;
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

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * The raw cost of each hash, isolated from the data structure around it.
 *
 * <p>Needed to attribute the routing numbers correctly: if a murmur3 ring
 * beats a CRC-32 ring on lookup, this says whether that is because murmur3 is
 * faster or because better-scattered points make the TreeMap walk shallower.
 * Sized at 24 bytes (a typical object key) and 512 bytes to show how each
 * hash scales with input length.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(value = 2, jvmArgs = {"-Xms1g", "-Xmx1g"})
public class HashFunctionBenchmark {

    @Param({"crc32", "fnv1a64", "murmur3_128", "md5"})
    public String function;

    @Param({"24", "512"})
    public int inputBytes;

    private HashFunction hashFunction;
    private byte[] input;

    @Setup
    public void setUp() {
        hashFunction = HashFunctions.byName(function);
        StringBuilder sb = new StringBuilder();
        while (sb.length() < inputBytes) {
            sb.append("video-key-segment/");
        }
        input = sb.substring(0, inputBytes).getBytes(StandardCharsets.UTF_8);
    }

    @Benchmark
    public long hash() {
        return hashFunction.hash(input);
    }
}
