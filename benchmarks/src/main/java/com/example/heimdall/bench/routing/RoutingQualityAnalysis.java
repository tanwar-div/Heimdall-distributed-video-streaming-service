package com.example.heimdall.bench.routing;

import com.example.heimdall.common.ring.KeyRouter;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Measures the two properties of a routing algorithm that throughput numbers
 * cannot see, and that a correctness test will happily pass while ignoring.
 *
 * <p><b>Balance.</b> Consistent hashing does not promise an even split, it
 * promises an even split <em>in expectation</em>. What a cluster actually
 * feels is the busiest node, so the headline figure here is the peak load
 * factor: the most-loaded member's share divided by its fair share. A peak
 * load factor of 1.40 means one node is carrying 40% more traffic than the
 * cluster was provisioned for, and it is the node that will fall over first.
 *
 * <p><b>Churn.</b> The entire reason to use consistent hashing rather than
 * {@code hash(key) % n} is that losing a node should move only that node's
 * keys. The theoretical floor when removing one of N members is 1/N of the
 * keyspace; anything above that is data being re-fetched, re-replicated, or
 * re-cached for no reason. Measuring the excess over that floor is what
 * separates the algorithms that genuinely deliver the property from the ones
 * that only claim it.
 */
public final class RoutingQualityAnalysis {

    /** Balance of the keyspace across members. All ratios are relative to a perfectly fair share. */
    public record Balance(int members, int keys, double coefficientOfVariation,
                          double peakLoadFactor, double minLoadFactor, String busiestMember) {
    }

    /** Fraction of keys that changed owner after a membership change, against the theoretical floor. */
    public record Churn(int membersBefore, int membersAfter, String changedMember,
                        double movedFraction, double optimalFraction) {

        /** How many times more keys moved than strictly had to. 1.0 is optimal. */
        public double excessFactor() {
            return optimalFraction == 0 ? 0 : movedFraction / optimalFraction;
        }
    }

    public record Result(RouterCatalog.Variant variant, Balance balance, Churn removalChurn,
                         Churn additionChurn, long footprintBytes) {
    }

    private RoutingQualityAnalysis() {
    }

    public static Result analyse(RouterCatalog.Variant variant, int members, int keyCount) {
        KeyRouter<String> router = variant.build(members);
        String[] keys = keys(keyCount);

        Map<String, String> ownerBefore = new HashMap<>(keyCount * 2);
        for (String key : keys) {
            ownerBefore.put(key, router.route(key));
        }

        Balance balance = balance(ownerBefore, members, keyCount);
        long footprint = router.approximateFootprintBytes();

        // Removing member 0 rather than the last one is deliberate: the last
        // member is the easy case that jump consistent hash is optimal for,
        // and a real cluster does not get to choose which node fails.
        String removed = RouterCatalog.memberId(0);
        KeyRouter<String> afterRemoval = variant.build(members);
        afterRemoval.removeMember(removed);
        Churn removalChurn = new Churn(members, members - 1, removed,
                movedFraction(keys, ownerBefore, afterRemoval), 1.0 / members);

        String added = RouterCatalog.memberId(members);
        KeyRouter<String> afterAddition = variant.build(members);
        afterAddition.addMember(added, added);
        Churn additionChurn = new Churn(members, members + 1, added,
                movedFraction(keys, ownerBefore, afterAddition), 1.0 / (members + 1));

        return new Result(variant, balance, removalChurn, additionChurn, footprint);
    }

    private static double movedFraction(String[] keys, Map<String, String> ownerBefore, KeyRouter<String> after) {
        int moved = 0;
        for (String key : keys) {
            if (!after.route(key).equals(ownerBefore.get(key))) {
                moved++;
            }
        }
        return (double) moved / keys.length;
    }

    private static Balance balance(Map<String, String> owners, int members, int keyCount) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (int i = 0; i < members; i++) {
            counts.put(RouterCatalog.memberId(i), 0);
        }
        for (String owner : owners.values()) {
            counts.merge(owner, 1, Integer::sum);
        }

        double fairShare = (double) keyCount / members;
        double sumSquaredDeviation = 0;
        int max = Integer.MIN_VALUE;
        int min = Integer.MAX_VALUE;
        String busiest = "";
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            int count = entry.getValue();
            sumSquaredDeviation += Math.pow(count - fairShare, 2);
            if (count > max) {
                max = count;
                busiest = entry.getKey();
            }
            min = Math.min(min, count);
        }
        double stdDev = Math.sqrt(sumSquaredDeviation / members);

        return new Balance(members, keyCount, stdDev / fairShare,
                max / fairShare, min / fairShare, busiest);
    }

    /**
     * Object keys shaped like the ones this cluster stores. Deliberately
     * structured and highly similar to each other, because that is the input a
     * weak hash handles worst and the input a real workload actually produces -
     * a benchmark over random UUIDs would flatter CRC-32 by hiding exactly the
     * clustering it suffers from.
     */
    static String[] keys(int count) {
        String[] keys = new String[count];
        for (int i = 0; i < count; i++) {
            keys[i] = "uploads/2026/video-" + i + ".mp4";
        }
        return keys;
    }
}
