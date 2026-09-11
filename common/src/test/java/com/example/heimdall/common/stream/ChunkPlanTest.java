package com.example.heimdall.common.stream;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proof, rather than spot-checks, that the byte-range arithmetic is correct.
 *
 * <p>The central property is the only one that actually matters to a user:
 * feed the plan an object's bytes, follow it chunk by chunk, and the
 * concatenation must equal {@code Arrays.copyOfRange(object, start, end + 1)}
 * - the trivially-correct answer - for <em>every</em> combination of offset,
 * length and chunk size. A conventional example-based test picks a handful of
 * those combinations and proves nothing about the rest; the boundaries where
 * range arithmetic actually breaks (a range ending exactly on a chunk edge, a
 * range entirely inside one chunk, a single-byte range at the last byte of the
 * object) are precisely the ones a hand-written test forgets.
 */
class ChunkPlanTest {

    /**
     * Reassembles a byte range by following the plan, exactly as the streaming
     * orchestrator does, and returns what a client would receive.
     */
    private static byte[] streamThroughPlan(byte[] object, long start, long end, int chunkSize) {
        ChunkPlan plan = ChunkPlan.forRange(start, end, chunkSize);
        ByteArrayOutputStream received = new ByteArrayOutputStream();
        for (int chunkIndex = plan.firstChunk(); chunkIndex <= plan.lastChunk(); chunkIndex++) {
            // The chunk as the storage node would hand it back: full size,
            // except the object's last chunk which is short.
            int chunkStart = chunkIndex * chunkSize;
            int chunkLength = Math.min(chunkSize, object.length - chunkStart);
            if (chunkLength <= 0) {
                continue;
            }
            byte[] chunk = Arrays.copyOfRange(object, chunkStart, chunkStart + chunkLength);

            int from = plan.offsetWithin(chunkIndex);
            int to = plan.endWithin(chunkIndex, chunk.length);
            if (from < to) {
                received.write(chunk, from, to - from);
            }
        }
        return received.toByteArray();
    }

    private static byte[] object(int size) {
        byte[] data = new byte[size];
        for (int i = 0; i < size; i++) {
            data[i] = (byte) (i * 31 + 7); // position-dependent, so a misplaced slice is detectable
        }
        return data;
    }

    @Property(tries = 3000)
    void deliversExactlyTheRequestedByteRangeForAnyOffsetLengthAndChunkSize(
            @ForAll @IntRange(min = 1, max = 5000) int objectSize,
            @ForAll @IntRange(min = 1, max = 512) int chunkSize,
            @ForAll @IntRange(min = 0, max = 4999) int rawStart,
            @ForAll @IntRange(min = 0, max = 4999) int rawLength) {

        byte[] object = object(objectSize);
        long start = Math.min(rawStart, objectSize - 1);
        long end = Math.min(start + rawLength, objectSize - 1);

        assertThat(streamThroughPlan(object, start, end, chunkSize))
                .isEqualTo(Arrays.copyOfRange(object, (int) start, (int) end + 1));
    }

    @Test
    void deliversExactlyTheRequestedByteRangeForEveryRangeOfASmallObject() {
        // Exhaustive rather than sampled: for objects up to 40 bytes and chunk
        // sizes up to 12, every single (start, end) pair is checked.
        for (int objectSize = 1; objectSize <= 40; objectSize++) {
            byte[] object = object(objectSize);
            for (int chunkSize = 1; chunkSize <= 12; chunkSize++) {
                for (int start = 0; start < objectSize; start++) {
                    for (int end = start; end < objectSize; end++) {
                        assertThat(streamThroughPlan(object, start, end, chunkSize))
                                .as("object=%d chunk=%d range=[%d,%d]", objectSize, chunkSize, start, end)
                                .isEqualTo(Arrays.copyOfRange(object, start, end + 1));
                    }
                }
            }
        }
    }

    @Property(tries = 2000)
    void fetchesNoChunkThatContributesNoBytes(
            @ForAll @LongRange(min = 0, max = 1_000_000) long start,
            @ForAll @IntRange(min = 0, max = 1_000_000) int length,
            @ForAll @IntRange(min = 1, max = 65536) int chunkSize) {

        long end = start + length;
        ChunkPlan plan = ChunkPlan.forRange(start, end, chunkSize);

        // Wasted chunk fetches are wasted network round-trips, so the plan must
        // be tight at both ends, not merely correct.
        assertThat(plan.offsetWithin(plan.firstChunk()))
                .as("first chunk must contain the range's first byte")
                .isLessThan(chunkSize);
        assertThat((long) plan.firstChunk() * chunkSize).isLessThanOrEqualTo(start);
        assertThat((long) (plan.firstChunk() + 1) * chunkSize).isGreaterThan(start);
        assertThat((long) plan.lastChunk() * chunkSize).isLessThanOrEqualTo(end);
        assertThat((long) (plan.lastChunk() + 1) * chunkSize).isGreaterThan(end);
    }

    @Property(tries = 1000)
    void byteCountAlwaysMatchesTheInclusiveRangeWidth(
            @ForAll @LongRange(min = 0, max = 1_000_000_000L) long start,
            @ForAll @IntRange(min = 0, max = 1_000_000) int length,
            @ForAll @IntRange(min = 1, max = 1 << 22) int chunkSize) {

        ChunkPlan plan = ChunkPlan.forRange(start, start + length, chunkSize);
        assertThat(plan.byteCount()).isEqualTo(length + 1);
        assertThat(plan.chunkCount()).isEqualTo(plan.lastChunk() - plan.firstChunk() + 1);
    }

    @Property(tries = 500)
    void anEmptyRangeFetchesNothing(
            @ForAll @LongRange(min = 1, max = 1_000_000) long start,
            @ForAll @IntRange(min = 1, max = 4096) int chunkSize) {

        // A zero-length object produces end == -1; the plan must be empty
        // rather than fetching a phantom chunk 0.
        ChunkPlan plan = ChunkPlan.forRange(start, start - 1, chunkSize);
        assertThat(plan.isEmpty()).isTrue();
        assertThat(plan.chunkCount()).isZero();
        assertThat(plan.byteCount()).isZero();
    }

    @Test
    void handlesAMultiGigabyteOffsetWithoutOverflowing() {
        // 1 MiB chunks over a 4 GiB object: chunk indices stay in int range,
        // but every intermediate offset must be computed in long arithmetic.
        int chunkSize = 1024 * 1024;
        long start = 4L * 1024 * 1024 * 1024 - 10;
        ChunkPlan plan = ChunkPlan.forRange(start, start + 9, chunkSize);

        assertThat(plan.firstChunk()).isEqualTo(4095);
        assertThat(plan.lastChunk()).isEqualTo(4095);
        assertThat(plan.byteCount()).isEqualTo(10);
        assertThat(plan.offsetWithin(4095)).isEqualTo(chunkSize - 10);
        assertThat(plan.endWithin(4095, chunkSize)).isEqualTo(chunkSize);
    }

    @Test
    void rejectsNonsensicalInputs() {
        assertThatThrownBy(() -> ChunkPlan.forRange(0, 10, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ChunkPlan.forRange(-1, 10, 16))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
