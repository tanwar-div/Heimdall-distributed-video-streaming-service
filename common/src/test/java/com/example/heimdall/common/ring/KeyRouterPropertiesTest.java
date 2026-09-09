package com.example.heimdall.common.ring;

import com.example.heimdall.common.hash.HashFunctions;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The guarantees every routing algorithm in this package is supposed to
 * provide, checked against all of them rather than asserted about one.
 *
 * <p>Two of these are the whole reason consistent hashing exists, and neither
 * is visible in a test that only checks "the same key returns the same node":
 *
 * <ul>
 *   <li><b>Only the departed node's keys move.</b> If losing a node also
 *       reshuffles keys between the survivors, the cluster does far more
 *       re-replication than it needed to during exactly the incident when it
 *       has the least capacity to spare.
 *   <li><b>Membership changes commute back.</b> Adding a node and removing it
 *       again must restore the original mapping byte for byte, or the routing
 *       table has drifted and objects have quietly become unreachable at their
 *       old location.
 * </ul>
 *
 * <p>{@link JumpHashRouter} is deliberately excluded from the first of those
 * and has its violation asserted explicitly instead - see
 * {@link #jumpHashingViolatesMinimalDisruptionWhenANonTailMemberLeaves()}.
 * Its published contract only covers changes at the end of the bucket range,
 * and pretending otherwise would be the kind of thing this test suite exists
 * to catch.
 */
class KeyRouterPropertiesTest {

    private static Stream<org.junit.jupiter.params.provider.Arguments> allRouters() {
        return Stream.of(
                args("ring/crc32", () -> new ConsistentHashRing<String>(100, HashFunctions.CRC32)),
                args("ring/murmur3", () -> new ConsistentHashRing<String>(100, HashFunctions.MURMUR3)),
                args("ring/md5", () -> new ConsistentHashRing<String>(100, HashFunctions.MD5)),
                args("ketama", KetamaRing::new),
                args("rendezvous", RendezvousRouter::new),
                args("jump", JumpHashRouter::new));
    }

    /** Everything except jump hashing, which does not claim the minimal-disruption property for arbitrary removals. */
    private static Stream<org.junit.jupiter.params.provider.Arguments> minimalDisruptionRouters() {
        return allRouters().filter(a -> !((String) a.get()[0]).equals("jump"));
    }

    private static org.junit.jupiter.params.provider.Arguments args(String name, Supplier<KeyRouter<String>> factory) {
        return org.junit.jupiter.params.provider.Arguments.of(name, factory);
    }

    private static KeyRouter<String> populated(Supplier<KeyRouter<String>> factory, int members) {
        KeyRouter<String> router = factory.get();
        for (int i = 0; i < members; i++) {
            router.addMember("primary-" + i, "primary-" + i);
        }
        return router;
    }

    private static List<String> keys(int count) {
        List<String> keys = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            keys.add("uploads/video-" + i + ".mp4");
        }
        return keys;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allRouters")
    void routesEveryKeyToACurrentMember(String name, Supplier<KeyRouter<String>> factory) {
        KeyRouter<String> router = populated(factory, 7);
        Set<String> members = new HashSet<>();
        for (int i = 0; i < 7; i++) {
            members.add("primary-" + i);
        }
        for (String key : keys(5000)) {
            assertThat(router.route(key)).isIn(members);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allRouters")
    void routingIsDeterministicAcrossRepeatedLookupsAndFreshInstances(String name, Supplier<KeyRouter<String>> factory) {
        KeyRouter<String> first = populated(factory, 5);
        KeyRouter<String> second = populated(factory, 5);
        for (String key : keys(2000)) {
            String owner = first.route(key);
            assertThat(first.route(key)).isEqualTo(owner);
            assertThat(second.route(key))
                    .as("a fresh instance with the same members must agree, or two gateways disagree on ownership")
                    .isEqualTo(owner);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allRouters")
    void aDepartedMemberNeverReceivesTrafficAgain(String name, Supplier<KeyRouter<String>> factory) {
        KeyRouter<String> router = populated(factory, 6);
        router.removeMember("primary-2");
        for (String key : keys(5000)) {
            assertThat(router.route(key)).isNotEqualTo("primary-2");
        }
        assertThat(router.memberCount()).isEqualTo(5);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("minimalDisruptionRouters")
    void losingAMemberMovesOnlyThatMembersKeys(String name, Supplier<KeyRouter<String>> factory) {
        KeyRouter<String> before = populated(factory, 6);
        List<String> keys = keys(20000);

        Map<String, String> ownerBefore = new HashMap<>();
        for (String key : keys) {
            ownerBefore.put(key, before.route(key));
        }

        KeyRouter<String> after = populated(factory, 6);
        after.removeMember("primary-2");

        for (String key : keys) {
            if (!ownerBefore.get(key).equals("primary-2")) {
                assertThat(after.route(key))
                        .as("key '%s' was owned by %s and must not have moved", key, ownerBefore.get(key))
                        .isEqualTo(ownerBefore.get(key));
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("minimalDisruptionRouters")
    void gainingAMemberOnlyEverStealsKeysAndNeverSwapsThemBetweenIncumbents(
            String name, Supplier<KeyRouter<String>> factory) {
        KeyRouter<String> before = populated(factory, 6);
        List<String> keys = keys(20000);

        Map<String, String> ownerBefore = new HashMap<>();
        for (String key : keys) {
            ownerBefore.put(key, before.route(key));
        }

        KeyRouter<String> after = populated(factory, 6);
        after.addMember("primary-99", "primary-99");

        for (String key : keys) {
            String now = after.route(key);
            if (!now.equals(ownerBefore.get(key))) {
                assertThat(now)
                        .as("key '%s' moved from %s, so it must have gone to the joining member", key, ownerBefore.get(key))
                        .isEqualTo("primary-99");
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allRouters")
    void addingThenRemovingAMemberRestoresTheExactOriginalMapping(String name, Supplier<KeyRouter<String>> factory) {
        KeyRouter<String> router = populated(factory, 5);
        List<String> keys = keys(5000);

        Map<String, String> ownerBefore = new HashMap<>();
        for (String key : keys) {
            ownerBefore.put(key, router.route(key));
        }

        router.addMember("primary-transient", "primary-transient");
        router.removeMember("primary-transient");

        for (String key : keys) {
            assertThat(router.route(key)).isEqualTo(ownerBefore.get(key));
        }
        assertThat(router.memberCount()).isEqualTo(5);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allRouters")
    void memberInsertionOrderDoesNotAffectRouting(String name, Supplier<KeyRouter<String>> factory) {
        KeyRouter<String> ascending = factory.get();
        for (int i = 0; i < 6; i++) {
            ascending.addMember("primary-" + i, "primary-" + i);
        }
        KeyRouter<String> descending = factory.get();
        for (int i = 5; i >= 0; i--) {
            descending.addMember("primary-" + i, "primary-" + i);
        }

        for (String key : keys(3000)) {
            // Jump hashing is the exception that proves why this matters: it
            // addresses bucket *positions*, so a different insertion order is
            // genuinely a different mapping.
            if (name.equals("jump")) {
                continue;
            }
            assertThat(descending.route(key)).isEqualTo(ascending.route(key));
        }
    }

    @Property(tries = 200)
    void ringSurvivesAnyInterleavingOfAddsAndRemoves(
            @ForAll @Size(min = 2, max = 12) @net.jqwik.api.constraints.UniqueElements List<@IntRange(min = 0, max = 30) Integer> joining,
            @ForAll @IntRange(min = 0, max = 5) int removeCount) {

        ConsistentHashRing<String> ring = new ConsistentHashRing<>(100, HashFunctions.MURMUR3);
        for (int id : joining) {
            ring.addMember("primary-" + id, "primary-" + id);
        }
        List<Integer> remaining = new ArrayList<>(joining);
        for (int i = 0; i < Math.min(removeCount, joining.size() - 1); i++) {
            int removed = remaining.remove(0);
            ring.removeMember("primary-" + removed);
        }

        assertThat(ring.memberCount()).isEqualTo(remaining.size());
        Set<String> live = new HashSet<>();
        remaining.forEach(id -> live.add("primary-" + id));
        for (String key : keys(500)) {
            assertThat(ring.route(key)).isIn(live);
        }
    }

    @Test
    void jumpHashingViolatesMinimalDisruptionWhenANonTailMemberLeaves() {
        // Asserted rather than avoided: this is the documented trade-off that
        // disqualifies jump consistent hash for a cluster whose nodes fail in
        // arbitrary order, and it should fail loudly if it ever stops being true.
        JumpHashRouter<String> before = new JumpHashRouter<>();
        JumpHashRouter<String> after = new JumpHashRouter<>();
        for (int i = 0; i < 8; i++) {
            before.addMember("primary-" + i, "primary-" + i);
            after.addMember("primary-" + i, "primary-" + i);
        }
        after.removeMember("primary-0");

        List<String> keys = keys(20000);
        long moved = keys.stream()
                .filter(key -> !before.route(key).equals("primary-0"))
                .filter(key -> !after.route(key).equals(before.route(key)))
                .count();
        long notOwnedByRemoved = keys.stream().filter(key -> !before.route(key).equals("primary-0")).count();

        double survivorChurn = (double) moved / notOwnedByRemoved;
        assertThat(survivorChurn)
                .as("removing a non-tail bucket re-indexes everything after it, so most survivors' keys move too")
                .isGreaterThan(0.5);
    }
}
