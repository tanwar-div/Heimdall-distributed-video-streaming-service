package com.example.heimdall.common.ring;

import com.example.heimdall.common.hash.HashFunction;
import com.example.heimdall.common.hash.HashFunctions;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Rendezvous hashing, a.k.a. Highest Random Weight (Thaler and Ravishankar,
 * 1996). For a key, score every member with {@code hash(key, member)} and pick
 * the highest scorer.
 *
 * <p>This trades the ring's O(log V) lookup for an O(N) scan over real members,
 * and gets two things back for it. Distribution is <em>exactly</em> uniform in
 * expectation with no virtual-node tuning knob at all, because each member's
 * score is an independent draw. And removing a member is provably optimal:
 * only that member's keys move, and they scatter over the survivors by their
 * own second-best score, so no other member's keys shift at all. A ring only
 * approximates both of those, and needs hundreds of virtual points per member
 * to approximate them well.
 *
 * <p>The cost is that lookup is linear in cluster size, which is why the
 * benchmark measures it at several member counts: it is the right choice for
 * a cluster of tens of nodes and the wrong one for a cluster of thousands.
 */
public class RendezvousRouter<T> implements KeyRouter<T> {

    private record Member<T>(String id, T value, long idHash) {
    }

    /** Copy-on-write so lookups never lock and never see a torn list. */
    private volatile List<Member<T>> members = List.of();
    private final ReadWriteLock writeLock = new ReentrantReadWriteLock();
    private final HashFunction hashFunction;

    public RendezvousRouter() {
        this(HashFunctions.MURMUR3);
    }

    public RendezvousRouter(HashFunction hashFunction) {
        this.hashFunction = hashFunction;
    }

    @Override
    public void addMember(String memberId, T member) {
        writeLock.writeLock().lock();
        try {
            List<Member<T>> next = new ArrayList<>(members.size() + 1);
            for (Member<T> existing : members) {
                if (!existing.id().equals(memberId)) {
                    next.add(existing);
                }
            }
            next.add(new Member<>(memberId, member, hashFunction.hash(memberId)));
            members = List.copyOf(next);
        } finally {
            writeLock.writeLock().unlock();
        }
    }

    @Override
    public void removeMember(String memberId) {
        writeLock.writeLock().lock();
        try {
            members = members.stream().filter(m -> !m.id().equals(memberId)).toList();
        } finally {
            writeLock.writeLock().unlock();
        }
    }

    @Override
    public T route(String key) {
        List<Member<T>> snapshot = members;
        if (snapshot.isEmpty()) {
            throw new IllegalStateException("Ring has no members");
        }
        long keyHash = hashFunction.hash(key);
        T best = null;
        long bestScore = Long.MIN_VALUE;
        for (Member<T> member : snapshot) {
            long score = mix(keyHash ^ member.idHash());
            // Ties broken by member id keeps routing deterministic across JVMs
            // even in the astronomically unlikely 64-bit collision.
            if (score > bestScore) {
                bestScore = score;
                best = member.value();
            }
        }
        return best;
    }

    /**
     * murmur3's 64-bit finalizer. Applied to {@code keyHash ^ memberHash} it
     * turns two independently-hashed values into a score with full avalanche,
     * which is what makes each member's score an effectively independent draw.
     */
    private static long mix(long h) {
        h ^= h >>> 33;
        h *= 0xff51afd7ed558ccdL;
        h ^= h >>> 33;
        h *= 0xc4ceb9fe1a85ec53L;
        h ^= h >>> 33;
        return h;
    }

    @Override
    public boolean isEmpty() {
        return members.isEmpty();
    }

    @Override
    public int memberCount() {
        return members.size();
    }

    @Override
    public String name() {
        return "rendezvous(" + hashFunction.name() + ")";
    }

    /** One record per real member - no virtual nodes, so footprint is independent of any tuning knob. */
    @Override
    public long approximateFootprintBytes() {
        return (long) members.size() * 48L;
    }
}
