package com.example.heimdall.common.stream;

/**
 * The arithmetic that turns an HTTP byte range into "fetch chunks i..j, and
 * trim this many bytes off the first and last one".
 *
 * <p>Extracted from the streaming orchestrator because it is the part of the
 * read path most likely to be quietly wrong and least likely to look wrong:
 * every term is an inclusive-vs-exclusive boundary, and an off-by-one shows up
 * not as an exception but as a video that plays with a few corrupt bytes at a
 * seek point. As a pure function over three numbers it can be checked
 * exhaustively against a trivially-correct reference implementation, which is
 * what {@code ChunkPlanTest} does.
 *
 * <p>Ranges here are <em>inclusive</em> at both ends, matching RFC 7233 and
 * the {@code Content-Range} header, not Java's usual half-open convention.
 */
public record ChunkPlan(long start, long end, int chunkSize, int firstChunk, int lastChunk) {

    public ChunkPlan {
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("chunkSize must be positive, was " + chunkSize);
        }
    }

    /** The plan for the inclusive byte range {@code [start, end]}. An empty range yields {@link #isEmpty()}. */
    public static ChunkPlan forRange(long start, long end, int chunkSize) {
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("chunkSize must be positive, was " + chunkSize);
        }
        if (start < 0) {
            throw new IllegalArgumentException("start must be non-negative, was " + start);
        }
        if (end < start) {
            // An empty range: no chunks to fetch. Signalled by lastChunk < firstChunk
            // rather than by a null plan, so callers can loop unconditionally.
            return new ChunkPlan(start, end, chunkSize, 0, -1);
        }
        return new ChunkPlan(start, end, chunkSize,
                (int) (start / chunkSize), (int) (end / chunkSize));
    }

    public boolean isEmpty() {
        return lastChunk < firstChunk;
    }

    /** How many chunks this range touches. */
    public int chunkCount() {
        return isEmpty() ? 0 : lastChunk - firstChunk + 1;
    }

    /** Total bytes the plan will emit. */
    public long byteCount() {
        return isEmpty() ? 0 : end - start + 1;
    }

    /**
     * Offset of the first wanted byte within chunk {@code chunkIndex} - non-zero
     * only for the first chunk, where the range starts mid-chunk.
     */
    public int offsetWithin(int chunkIndex) {
        return (int) Math.max(0, start - (long) chunkIndex * chunkSize);
    }

    /**
     * Exclusive end offset of the wanted bytes within chunk {@code chunkIndex},
     * clamped to the chunk's actual length - the last chunk of an object is
     * usually short, and the range may also stop before the chunk does.
     */
    public int endWithin(int chunkIndex, int actualChunkLength) {
        return (int) Math.min(actualChunkLength, end - (long) chunkIndex * chunkSize + 1);
    }
}
