package com.example.heimdall.common.hash;

import com.google.common.hash.Hashing;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verification of the hand-written {@link Murmur3} against Guava's reference
 * implementation of the same algorithm.
 *
 * <p>This matters more than it looks: every distribution and churn number the
 * benchmark module reports is downstream of this hash being genuinely
 * murmur3 and not a subtly-wrong lookalike, which would still pass a
 * "is it deterministic and evenly spread?" test while invalidating the
 * comparison against published results.
 */
class Murmur3Test {

    private final Murmur3 murmur3 = new Murmur3();

    @SuppressWarnings("deprecation") // Guava marks its hashing API @Beta, not deprecated behaviour
    private static long reference(byte[] data) {
        return Hashing.murmur3_128().hashBytes(data).asLong();
    }

    @Test
    void emptyInputWithSeedZeroHashesToZero() {
        // A documented property of murmur3_x64_128: the finalizer of 0 is 0.
        assertThat(murmur3.hash(new byte[0])).isZero();
    }

    @Test
    void matchesGuavaOnStringsThatStraddleTheBlockBoundary() {
        // 16 bytes is one full block; lengths 0..40 exercise every tail branch.
        StringBuilder sb = new StringBuilder();
        for (int length = 0; length <= 40; length++) {
            byte[] data = sb.toString().getBytes(StandardCharsets.UTF_8);
            assertThat(murmur3.hash(data))
                    .as("length %d", length)
                    .isEqualTo(reference(data));
            sb.append((char) ('a' + (length % 26)));
        }
    }

    @Test
    void matchesGuavaOnTheRingPointsThisProjectActuallyHashes() {
        for (int primary = 0; primary < 8; primary++) {
            for (int vnode = 0; vnode < 250; vnode++) {
                String point = "primary-" + primary + "#" + vnode;
                assertThat(murmur3.hash(point))
                        .as(point)
                        .isEqualTo(reference(point.getBytes(StandardCharsets.UTF_8)));
            }
        }
    }

    @Test
    void matchesGuavaOnRandomBinaryInput() {
        Random random = new Random(20260909L);
        for (int i = 0; i < 2000; i++) {
            byte[] data = new byte[random.nextInt(300)];
            random.nextBytes(data);
            assertThat(murmur3.hash(data)).isEqualTo(reference(data));
        }
    }

    @Property(tries = 2000)
    void matchesGuavaForEveryGeneratedString(@ForAll String key) {
        byte[] data = key.getBytes(StandardCharsets.UTF_8);
        assertThat(murmur3.hash(data)).isEqualTo(reference(data));
    }

    @Test
    void avalanchesFlippedInputBitsAcrossRoughlyHalfTheOutputBits() {
        // The property that actually makes a hash suitable for a ring: a
        // one-bit input change should look like a fresh random output.
        Random random = new Random(7L);
        long totalFlipped = 0;
        int trials = 5000;
        for (int i = 0; i < trials; i++) {
            byte[] data = new byte[16];
            random.nextBytes(data);
            long before = murmur3.hash(data);
            data[random.nextInt(data.length)] ^= (byte) (1 << random.nextInt(8));
            long after = murmur3.hash(data);
            totalFlipped += Long.bitCount(before ^ after);
        }
        double averageFlipped = (double) totalFlipped / trials;
        assertThat(averageFlipped).isBetween(30.0, 34.0); // ideal is 32 of 64 bits
    }
}
