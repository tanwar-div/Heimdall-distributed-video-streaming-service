package com.example.heimdall.bench.e2e;

/**
 * The request patterns a video service actually receives, rather than a
 * generic "fetch a blob" loop.
 *
 * <p>These are separated because they stress different things and a single
 * aggregate number would hide both. {@link #STARTUP} is what decides whether a
 * video begins playing quickly - it is a small read whose cost is dominated by
 * per-request overhead, which is exactly where a gateway hop shows up worst.
 * {@link #SEEK} is a viewer dragging the scrub bar, the case that justifies
 * chunked storage at all. {@link #FULL_DOWNLOAD} is a bulk transfer, where
 * per-request overhead is amortised away and only streaming throughput remains.
 */
public enum Workload {

    /** First 1 MiB: a player buffering enough to start playback. Latency-bound. */
    STARTUP("startup-1MiB", 1024L * 1024),

    /** A 4 MiB read from a random offset: a viewer seeking. Latency-bound, cache-hostile. */
    SEEK("seek-4MiB", 4L * 1024 * 1024),

    /** The whole object. Throughput-bound. */
    FULL_DOWNLOAD("full-download", -1);

    private final String label;
    private final long bytes;

    Workload(String label, long bytes) {
        this.label = label;
        this.bytes = bytes;
    }

    public String label() {
        return label;
    }

    /** Bytes requested per operation, or -1 for the whole object. */
    public long bytes() {
        return bytes;
    }

    public boolean isRanged() {
        return bytes > 0;
    }
}
