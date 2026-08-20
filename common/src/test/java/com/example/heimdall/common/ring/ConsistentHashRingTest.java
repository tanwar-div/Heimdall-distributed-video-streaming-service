package com.example.heimdall.common.ring;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConsistentHashRingTest {

    @Test
    void throwsWhenEmpty() {
        ConsistentHashRing<String> ring = new ConsistentHashRing<>(10);
        assertThatThrownBy(() -> ring.getMemberFor("x"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void routingIsDeterministicForTheSameKey() {
        ConsistentHashRing<String> ring = new ConsistentHashRing<>(100);
        ring.addMember("a", "a");
        ring.addMember("b", "b");
        ring.addMember("c", "c");

        String first = ring.getMemberFor("my-video.mp4");
        for (int i = 0; i < 50; i++) {
            assertThat(ring.getMemberFor("my-video.mp4")).isEqualTo(first);
        }
    }

    @Test
    void distributesKeysAcrossAllMembersReasonablyEvenly() {
        ConsistentHashRing<String> ring = new ConsistentHashRing<>(200);
        ring.addMember("primary-0", "primary-0");
        ring.addMember("primary-1", "primary-1");
        ring.addMember("primary-2", "primary-2");

        Map<String, Integer> counts = new HashMap<>();
        int totalKeys = 3000;
        for (int i = 0; i < totalKeys; i++) {
            String owner = ring.getMemberFor("object-" + i);
            counts.merge(owner, 1, Integer::sum);
        }

        assertThat(counts).hasSize(3);
        // With enough virtual nodes, no single member should own a wildly
        // disproportionate share of the keyspace.
        counts.values().forEach(count ->
                assertThat(count).isBetween(totalKeys / 3 / 2, totalKeys / 3 * 2));
    }

    @Test
    void removingAMemberRoutesItsKeysElsewhereButLeavesOthersStable() {
        ConsistentHashRing<String> ring = new ConsistentHashRing<>(200);
        ring.addMember("primary-0", "primary-0");
        ring.addMember("primary-1", "primary-1");
        ring.addMember("primary-2", "primary-2");

        Map<String, String> before = new HashMap<>();
        for (int i = 0; i < 500; i++) {
            before.put("object-" + i, ring.getMemberFor("object-" + i));
        }

        ring.removeMember("primary-1");

        int stable = 0;
        for (Map.Entry<String, String> entry : before.entrySet()) {
            String newOwner = ring.getMemberFor(entry.getKey());
            assertThat(newOwner).isNotEqualTo("primary-1");
            if (newOwner.equals(entry.getValue())) {
                stable++;
            }
        }
        // Keys that weren't owned by the removed member should mostly stay put.
        assertThat(stable).isGreaterThan(0);
    }

    @Test
    void rejectsNonPositiveVirtualNodeCount() {
        assertThatThrownBy(() -> new ConsistentHashRing<String>(0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
