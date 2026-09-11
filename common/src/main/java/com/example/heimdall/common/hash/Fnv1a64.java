package com.example.heimdall.common.hash;

/**
 * FNV-1a, 64-bit. A one-line-per-byte hash with no table and no block
 * structure, included as the cheapest plausible alternative to CRC-32: it
 * shows whether the ring's distribution problem can be fixed for free, or
 * whether it genuinely needs a mixing step like murmur3's finalizer.
 */
public final class Fnv1a64 implements HashFunction {

    private static final long OFFSET_BASIS = 0xcbf29ce484222325L;
    private static final long PRIME = 0x100000001b3L;

    @Override
    public String name() {
        return "fnv1a64";
    }

    @Override
    public int bits() {
        return 64;
    }

    @Override
    public long hash(byte[] data) {
        long h = OFFSET_BASIS;
        for (byte b : data) {
            h ^= (b & 0xff);
            h *= PRIME;
        }
        return h;
    }
}
