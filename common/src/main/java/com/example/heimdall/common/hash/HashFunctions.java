package com.example.heimdall.common.hash;

import java.util.List;
import java.util.Locale;

/** The hashes the ring can be configured with, and lookup by name. */
public final class HashFunctions {

    public static final HashFunction CRC32 = new Crc32Hash();
    public static final HashFunction MD5 = new Md5Hash();
    public static final HashFunction MURMUR3 = new Murmur3();
    public static final HashFunction FNV1A64 = new Fnv1a64();

    public static final List<HashFunction> ALL = List.of(CRC32, FNV1A64, MURMUR3, MD5);

    private HashFunctions() {
    }

    public static HashFunction byName(String name) {
        String normalized = name.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
        for (HashFunction f : ALL) {
            if (f.name().toLowerCase(Locale.ROOT).replace("_", "").startsWith(normalized)) {
                return f;
            }
        }
        throw new IllegalArgumentException("Unknown hash function '" + name + "'; known: "
                + ALL.stream().map(HashFunction::name).toList());
    }
}
