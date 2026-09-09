package com.example.heimdall.common.hash;

import java.nio.charset.StandardCharsets;

/**
 * A named, non-cryptographic-strength-agnostic string hash, used to place
 * members and keys on a routing ring.
 *
 * <p>The quality that matters for a hash ring is not collision resistance but
 * <em>avalanche</em>: flipping one input bit should change roughly half the
 * output bits. A hash with poor avalanche clusters its outputs, which on a
 * ring means virtual nodes bunch together and some members end up owning a
 * disproportionate arc of the keyspace no matter how many virtual nodes are
 * configured. {@code Crc32}, the ring's original hash, is a checksum with
 * exactly that weakness, and the benchmark module measures the cost.
 */
public interface HashFunction {

    /** The hash of {@code data}, as an unsigned value widened into a long. */
    long hash(byte[] data);

    /** A short, stable name used in benchmark output and configuration. */
    String name();

    /** How many bits of the returned long are actually significant (32 or 64). */
    int bits();

    default long hash(String key) {
        return hash(key.getBytes(StandardCharsets.UTF_8));
    }
}
