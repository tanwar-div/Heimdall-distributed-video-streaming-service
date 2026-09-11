package com.example.heimdall.common.ring;

import java.util.Collection;

/**
 * Maps an object key to the member that owns it.
 *
 * <p>Every implementation in this package is a real, published algorithm, and
 * they differ in ways that only show up under measurement: lookup cost, how
 * evenly they spread the keyspace, how many keys they move when membership
 * changes, and how much memory they need to do it. The
 * {@code heimdall-benchmarks} module measures all four and
 * {@code BENCHMARKS.md} records the results that picked the default.
 *
 * <p>Implementations must be safe for concurrent lookups; membership changes
 * are assumed rare and may serialize.
 */
public interface KeyRouter<T> {

    /** The member that owns {@code key}. Never null. */
    T route(String key);

    void addMember(String memberId, T member);

    void removeMember(String memberId);

    boolean isEmpty();

    /** Number of distinct members currently routable. */
    int memberCount();

    /** Short algorithm name, e.g. {@code "ring(murmur3_128, vnodes=200)"}. */
    String name();

    /** Bytes of routing state held, approximately - the memory a router costs to keep. */
    long approximateFootprintBytes();

    /** Convenience for building a router that is already populated. */
    static <T> void addAll(KeyRouter<T> router, Collection<? extends T> members,
                           java.util.function.Function<T, String> idOf) {
        for (T member : members) {
            router.addMember(idOf.apply(member), member);
        }
    }
}
