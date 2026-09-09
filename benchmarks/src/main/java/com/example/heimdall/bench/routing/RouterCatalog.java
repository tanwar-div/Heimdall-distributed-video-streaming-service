package com.example.heimdall.bench.routing;

import com.example.heimdall.common.hash.HashFunctions;
import com.example.heimdall.common.ring.ConsistentHashRing;
import com.example.heimdall.common.ring.JumpHashRouter;
import com.example.heimdall.common.ring.KetamaRing;
import com.example.heimdall.common.ring.KeyRouter;
import com.example.heimdall.common.ring.RendezvousRouter;

import java.util.List;
import java.util.function.Supplier;

/**
 * The routing algorithms under test, in one place so the JMH throughput
 * benchmarks and the distribution/churn analysis measure exactly the same
 * set of configurations.
 *
 * <p>{@link #PRODUCTION_DEFAULT} is the configuration the gateway actually
 * shipped with before any of this was measured - CRC-32 with 100 virtual
 * nodes - and every other entry exists to answer a specific question about
 * it: does the hash matter (fnv1a/murmur3/md5 at the same vnode count), does
 * the vnode count matter (murmur3 at 100 vs 500), does the industry-standard
 * implementation do better (ketama), and can a fundamentally different
 * algorithm beat the ring outright (rendezvous, jump).
 */
public final class RouterCatalog {

    public record Variant(String id, String description, Supplier<KeyRouter<String>> factory) {
        public KeyRouter<String> build(int memberCount) {
            KeyRouter<String> router = factory.get();
            for (int i = 0; i < memberCount; i++) {
                String id = memberId(i);
                router.addMember(id, id);
            }
            return router;
        }
    }

    /** Stable, realistic member ids - the ring is sensitive to how similar member names are. */
    public static String memberId(int index) {
        return "primary-" + index;
    }

    public static final Variant RING_CRC32_100 = new Variant(
            "ring/crc32/v100",
            "Ring with CRC-32, 100 virtual nodes (the shipped default)",
            () -> new ConsistentHashRing<>(100, HashFunctions.CRC32));

    public static final Variant RING_CRC32_500 = new Variant(
            "ring/crc32/v500",
            "Ring with CRC-32, 500 virtual nodes (can more vnodes rescue a weak hash?)",
            () -> new ConsistentHashRing<>(500, HashFunctions.CRC32));

    public static final Variant RING_FNV1A_100 = new Variant(
            "ring/fnv1a64/v100",
            "Ring with FNV-1a 64, 100 virtual nodes (cheapest possible upgrade)",
            () -> new ConsistentHashRing<>(100, HashFunctions.FNV1A64));

    public static final Variant RING_MURMUR3_100 = new Variant(
            "ring/murmur3/v100",
            "Ring with murmur3-128, 100 virtual nodes",
            () -> new ConsistentHashRing<>(100, HashFunctions.MURMUR3));

    public static final Variant RING_MURMUR3_500 = new Variant(
            "ring/murmur3/v500",
            "Ring with murmur3-128, 500 virtual nodes",
            () -> new ConsistentHashRing<>(500, HashFunctions.MURMUR3));

    public static final Variant RING_MD5_100 = new Variant(
            "ring/md5/v100",
            "Ring with MD5, 100 virtual nodes",
            () -> new ConsistentHashRing<>(100, HashFunctions.MD5));

    public static final Variant KETAMA = new Variant(
            "ketama/md5/p160",
            "libketama's ring as used by memcached clients, 160 points per member",
            KetamaRing::new);

    public static final Variant RENDEZVOUS = new Variant(
            "rendezvous/murmur3",
            "Rendezvous (highest random weight) hashing",
            RendezvousRouter::new);

    public static final Variant JUMP = new Variant(
            "jump/murmur3",
            "Google jump consistent hash",
            JumpHashRouter::new);

    public static final Variant PRODUCTION_DEFAULT = RING_CRC32_100;

    public static final List<Variant> ALL = List.of(
            RING_CRC32_100, RING_CRC32_500, RING_FNV1A_100,
            RING_MURMUR3_100, RING_MURMUR3_500, RING_MD5_100,
            KETAMA, RENDEZVOUS, JUMP);

    public static Variant byId(String id) {
        return ALL.stream()
                .filter(v -> v.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown variant '" + id + "'; known: "
                        + ALL.stream().map(Variant::id).toList()));
    }

    private RouterCatalog() {
    }
}
