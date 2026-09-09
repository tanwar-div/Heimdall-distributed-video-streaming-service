package com.example.heimdall.common.ring;

import com.example.heimdall.common.hash.HashFunction;
import com.example.heimdall.common.hash.HashFunctions;

import java.util.HashSet;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * The classic consistent-hash ring (Karger et al., 1997). Each member is
 * placed at several virtual points on the ring (hashing
 * "{memberId}#{i}" for i in [0, virtualNodesPerMember)), which keeps key
 * distribution even even with a small number of real members.
 *
 * <p>The hash is pluggable because it is the single biggest lever on the
 * ring's distribution quality - the arcs between consecutive virtual points
 * are only as evenly sized as the hash's outputs are evenly scattered. See
 * {@link HashFunction} and the benchmark module for the measurements behind
 * the chosen default.
 *
 * <p>Thread-safe: reads (routing lookups) happen on every request while
 * membership changes are rare, so a {@link ReadWriteLock} lets lookups run
 * fully concurrently and only serializes against the occasional membership
 * change.
 */
public class ConsistentHashRing<T> implements KeyRouter<T> {

    private final NavigableMap<Long, T> ring = new TreeMap<>();
    private final Set<String> memberIds = new HashSet<>();
    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private final int virtualNodesPerMember;
    private final HashFunction hashFunction;

    /**
     * A ring using the historical CRC-32 hash. Retained so existing callers and
     * their recorded routing decisions keep working unchanged; prefer
     * {@link #ConsistentHashRing(int, HashFunction)} for new code.
     */
    public ConsistentHashRing(int virtualNodesPerMember) {
        this(virtualNodesPerMember, HashFunctions.CRC32);
    }

    public ConsistentHashRing(int virtualNodesPerMember, HashFunction hashFunction) {
        if (virtualNodesPerMember < 1) {
            throw new IllegalArgumentException("virtualNodesPerMember must be >= 1");
        }
        this.virtualNodesPerMember = virtualNodesPerMember;
        this.hashFunction = hashFunction;
    }

    @Override
    public void addMember(String memberId, T member) {
        lock.writeLock().lock();
        try {
            for (int i = 0; i < virtualNodesPerMember; i++) {
                ring.put(hash(memberId + "#" + i), member);
            }
            memberIds.add(memberId);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void removeMember(String memberId) {
        lock.writeLock().lock();
        try {
            for (int i = 0; i < virtualNodesPerMember; i++) {
                ring.remove(hash(memberId + "#" + i));
            }
            memberIds.remove(memberId);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Given an object key, returns the member that owns it (walking clockwise from the key's hash). */
    public T getMemberFor(String key) {
        lock.readLock().lock();
        try {
            if (ring.isEmpty()) {
                throw new IllegalStateException("Ring has no members");
            }
            Map.Entry<Long, T> entry = ring.ceilingEntry(hash(key));
            if (entry == null) {
                entry = ring.firstEntry(); // wrap around the ring
            }
            return entry.getValue();
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public T route(String key) {
        return getMemberFor(key);
    }

    @Override
    public boolean isEmpty() {
        lock.readLock().lock();
        try {
            return ring.isEmpty();
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public int memberCount() {
        lock.readLock().lock();
        try {
            return memberIds.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public String name() {
        return "ring(" + hashFunction.name() + ", vnodes=" + virtualNodesPerMember + ")";
    }

    /**
     * A TreeMap entry costs roughly a 40-byte node plus a boxed Long key; the
     * point of reporting it is the shape (linear in members x virtual nodes),
     * which is what separates a ring from rendezvous or jump hashing.
     */
    @Override
    public long approximateFootprintBytes() {
        lock.readLock().lock();
        try {
            return (long) ring.size() * 56L;
        } finally {
            lock.readLock().unlock();
        }
    }

    /** How many virtual points are currently on the ring. Exposed for the benchmark's memory model. */
    public int virtualPointCount() {
        lock.readLock().lock();
        try {
            return ring.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    private long hash(String key) {
        return hashFunction.hash(key);
    }
}
