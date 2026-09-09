package com.example.heimdall.common.hash;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * MD5, truncated to its first 64 bits - the hash libketama (and therefore
 * memcached's standard client-side ring) uses.
 *
 * <p>Cryptographically broken and comparatively slow, but its avalanche is
 * excellent, which is the only property a ring cares about. Included so the
 * benchmark can separate "does a better hash help?" from "is the cost of a
 * better hash worth it?" - MD5 answers the first yes and the second no.
 */
public final class Md5Hash implements HashFunction {

    @Override
    public String name() {
        return "md5";
    }

    @Override
    public int bits() {
        return 64;
    }

    @Override
    public long hash(byte[] data) {
        byte[] digest = digest(data);
        long h = 0;
        for (int i = 7; i >= 0; i--) {
            h = (h << 8) | (digest[i] & 0xffL);
        }
        return h >>> 1; // keep it non-negative without losing a whole byte
    }

    /** The raw 16-byte digest, needed by {@code KetamaRing}'s 4-points-per-digest layout. */
    public static byte[] digest(byte[] data) {
        try {
            return MessageDigest.getInstance("MD5").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 is required by every JVM but was unavailable", e);
        }
    }
}
