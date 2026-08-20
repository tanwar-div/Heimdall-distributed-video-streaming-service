package com.example.heimdall.common.ring;

import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.zip.CRC32;

/**
 * A generic consistent-hash ring. Each member is placed at several virtual
 * points on the ring (hashing "{memberId}#{i}" for i in [0, virtualNodesPerMember)),
 * which keeps key distribution even even with a small number of real members.
 *
 * <p>Thread-safe: reads (routing lookups) happen on every request while
 * membership changes are rare, so a {@link ReadWriteLock} lets lookups run
 * fully concurrently and only serializes against the occasional membership
 * change.
 */
public class ConsistentHashRing<T> {

    private final NavigableMap<Long, T> ring = new TreeMap<>();
    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private final int virtualNodesPerMember;

    public ConsistentHashRing(int virtualNodesPerMember) {
        if (virtualNodesPerMember < 1) {
            throw new IllegalArgumentException("virtualNodesPerMember must be >= 1");
        }
        this.virtualNodesPerMember = virtualNodesPerMember;
    }

    public void addMember(String memberId, T member) {
        lock.writeLock().lock();
        try {
            for (int i = 0; i < virtualNodesPerMember; i++) {
                ring.put(hash(memberId + "#" + i), member);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void removeMember(String memberId) {
        lock.writeLock().lock();
        try {
            for (int i = 0; i < virtualNodesPerMember; i++) {
                ring.remove(hash(memberId + "#" + i));
            }
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

    public boolean isEmpty() {
        lock.readLock().lock();
        try {
            return ring.isEmpty();
        } finally {
            lock.readLock().unlock();
        }
    }

    private long hash(String key) {
        CRC32 crc32 = new CRC32();
        crc32.update(key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return crc32.getValue();
    }
}
