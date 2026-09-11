package com.example.heimdall.bench.e2e;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A fixed-capacity, lock-free recorder of latency samples in nanoseconds.
 *
 * <p>Deliberately records every sample rather than aggregating into a
 * histogram: at these request rates the memory is trivial, and keeping the raw
 * samples means the tail percentiles are exact rather than bucketed. Tail
 * latency is the entire point of measuring a streaming service - the p99 is
 * the request where a viewer sees the player stall - so it is the one number
 * that should not be approximated.
 */
public final class Latencies {

    private final long[] samples;
    private final AtomicInteger index = new AtomicInteger();

    public Latencies(int capacity) {
        this.samples = new long[capacity];
    }

    /** Records a sample, silently dropping it if capacity is exhausted. */
    public void record(long nanos) {
        int i = index.getAndIncrement();
        if (i < samples.length) {
            samples[i] = nanos;
        }
    }

    public int count() {
        return Math.min(index.get(), samples.length);
    }

    /** Sorted copy of the recorded samples; call once, then query percentiles off the result. */
    public long[] sorted() {
        long[] copy = Arrays.copyOf(samples, count());
        Arrays.sort(copy);
        return copy;
    }

    public static double percentileMillis(long[] sorted, double percentile) {
        if (sorted.length == 0) {
            return Double.NaN;
        }
        int rank = (int) Math.ceil(percentile / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, rank))] / 1_000_000.0;
    }

    public static double meanMillis(long[] sorted) {
        if (sorted.length == 0) {
            return Double.NaN;
        }
        long total = 0;
        for (long sample : sorted) {
            total += sample;
        }
        return total / (double) sorted.length / 1_000_000.0;
    }
}
