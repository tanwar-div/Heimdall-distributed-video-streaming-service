package com.example.heimdall.common.ring;

import com.example.heimdall.common.hash.HashFunction;
import com.example.heimdall.common.hash.HashFunctions;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Google's jump consistent hash (Lamping and Veach, 2014): maps a key to a
 * bucket in [0, n) in O(ln n) time with <em>zero</em> routing state - no ring,
 * no member list to hash against, just a loop over a linear congruential
 * generator.
 *
 * <p>Its distribution is essentially perfect and its cost is a handful of
 * multiplications, which makes it the fastest and smallest option here by a
 * wide margin. The catch, and the reason it is not simply the answer, is in
 * its contract: it addresses <em>bucket indices</em>, not member identities.
 * It guarantees minimal key movement only when the bucket count changes at the
 * end of the range - growing from n to n+1 moves exactly 1/(n+1) of keys. Any
 * other membership change means re-indexing, and every member after the
 * removed one shifts down a slot, remapping a large fraction of the keyspace.
 *
 * <p>That is a real operational difference for this cluster, not a footnote:
 * losing {@code primary-0} out of three primaries is an ordinary failure, and
 * the benchmark's churn measurement quantifies what jump hashing costs when it
 * happens. This class implements removal in the only way the algorithm allows
 * (drop the member and re-index the rest) so that cost is measured honestly
 * rather than assumed away.
 */
public class JumpHashRouter<T> implements KeyRouter<T> {

    private record Member<T>(String id, T value) {
    }

    private volatile List<Member<T>> buckets = List.of();
    private final ReadWriteLock writeLock = new ReentrantReadWriteLock();
    private final HashFunction hashFunction;

    public JumpHashRouter() {
        this(HashFunctions.MURMUR3);
    }

    public JumpHashRouter(HashFunction hashFunction) {
        this.hashFunction = hashFunction;
    }

    @Override
    public void addMember(String memberId, T member) {
        writeLock.writeLock().lock();
        try {
            List<Member<T>> next = new ArrayList<>(buckets);
            next.removeIf(m -> m.id().equals(memberId));
            next.add(new Member<>(memberId, member)); // appended: the growth case the algorithm is optimal for
            buckets = List.copyOf(next);
        } finally {
            writeLock.writeLock().unlock();
        }
    }

    @Override
    public void removeMember(String memberId) {
        writeLock.writeLock().lock();
        try {
            buckets = buckets.stream().filter(m -> !m.id().equals(memberId)).toList();
        } finally {
            writeLock.writeLock().unlock();
        }
    }

    @Override
    public T route(String key) {
        List<Member<T>> snapshot = buckets;
        if (snapshot.isEmpty()) {
            throw new IllegalStateException("Ring has no members");
        }
        return snapshot.get(jump(hashFunction.hash(key), snapshot.size())).value();
    }

    /**
     * The algorithm verbatim from the paper. {@code key} is advanced by an LCG;
     * each iteration jumps to the next bucket index that would capture the key,
     * so the loop body runs O(ln n) times rather than n.
     */
    static int jump(long key, int numBuckets) {
        long b = -1;
        long j = 0;
        while (j < numBuckets) {
            b = j;
            key = key * 2862933555777941757L + 1;
            j = (long) ((b + 1) * ((double) (1L << 31) / (double) ((key >>> 33) + 1)));
        }
        return (int) b;
    }

    @Override
    public boolean isEmpty() {
        return buckets.isEmpty();
    }

    @Override
    public int memberCount() {
        return buckets.size();
    }

    @Override
    public String name() {
        return "jump(" + hashFunction.name() + ")";
    }

    /** Only the member array itself; the algorithm keeps no index at all. */
    @Override
    public long approximateFootprintBytes() {
        return (long) buckets.size() * 32L;
    }
}
