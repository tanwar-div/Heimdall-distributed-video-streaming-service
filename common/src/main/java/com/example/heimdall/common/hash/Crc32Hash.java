package com.example.heimdall.common.hash;

import java.util.zip.CRC32;

/**
 * The ring's original hash: a CRC-32 checksum widened to a long.
 *
 * <p>Kept as a first-class, selectable option precisely so the benchmark
 * module can quantify what it costs. CRC-32 is designed to detect burst
 * errors, not to scatter inputs: it is linear over GF(2), so structurally
 * similar inputs (exactly what ring points are - {@code "primary-0#0"},
 * {@code "primary-0#1"}, ...) produce structurally similar outputs. That
 * clustering is invisible in a correctness test and shows up only as
 * measurably uneven key distribution.
 */
public final class Crc32Hash implements HashFunction {

    @Override
    public String name() {
        return "crc32";
    }

    @Override
    public int bits() {
        return 32;
    }

    @Override
    public long hash(byte[] data) {
        CRC32 crc32 = new CRC32();
        crc32.update(data);
        return crc32.getValue();
    }
}
