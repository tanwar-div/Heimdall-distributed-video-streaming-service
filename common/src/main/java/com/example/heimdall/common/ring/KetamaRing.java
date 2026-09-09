package com.example.heimdall.common.ring;

import com.example.heimdall.common.hash.Md5Hash;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * libketama's ring, as used by memcached's standard clients - the de-facto
 * reference implementation of consistent hashing in production systems.
 *
 * <p>It differs from a naive ring in one detail worth copying: instead of one
 * hash per virtual point, it takes <em>four</em> points out of each 16-byte
 * MD5 digest, reading four little-endian 32-bit words. That amortises the
 * cost of a relatively slow hash over four ring placements, which is how
 * ketama affords 160 points per server. Included here as the "what does the
 * industry-standard implementation actually score?" baseline.
 */
public class KetamaRing<T> implements KeyRouter<T> {

    /** libketama's default: 40 digests x 4 points. */
    public static final int DEFAULT_POINTS_PER_MEMBER = 160;

    private final NavigableMap<Long, T> ring = new TreeMap<>();
    private final Set<String> memberIds = new HashSet<>();
    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private final int pointsPerMember;

    public KetamaRing() {
        this(DEFAULT_POINTS_PER_MEMBER);
    }

    public KetamaRing(int pointsPerMember) {
        if (pointsPerMember < 4 || pointsPerMember % 4 != 0) {
            throw new IllegalArgumentException("pointsPerMember must be a positive multiple of 4 (4 points per MD5 digest)");
        }
        this.pointsPerMember = pointsPerMember;
    }

    @Override
    public void addMember(String memberId, T member) {
        lock.writeLock().lock();
        try {
            for (int i = 0; i < pointsPerMember / 4; i++) {
                byte[] digest = Md5Hash.digest((memberId + "-" + i).getBytes(StandardCharsets.UTF_8));
                for (int h = 0; h < 4; h++) {
                    ring.put(pointFromDigest(digest, h), member);
                }
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
            for (int i = 0; i < pointsPerMember / 4; i++) {
                byte[] digest = Md5Hash.digest((memberId + "-" + i).getBytes(StandardCharsets.UTF_8));
                for (int h = 0; h < 4; h++) {
                    ring.remove(pointFromDigest(digest, h));
                }
            }
            memberIds.remove(memberId);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** The h-th little-endian 32-bit word of a 16-byte MD5 digest, exactly as libketama reads it. */
    private static long pointFromDigest(byte[] digest, int h) {
        return ((long) (digest[3 + h * 4] & 0xFF) << 24)
                | ((long) (digest[2 + h * 4] & 0xFF) << 16)
                | ((long) (digest[1 + h * 4] & 0xFF) << 8)
                | ((long) (digest[h * 4] & 0xFF));
    }

    @Override
    public T route(String key) {
        lock.readLock().lock();
        try {
            if (ring.isEmpty()) {
                throw new IllegalStateException("Ring has no members");
            }
            byte[] digest = Md5Hash.digest(key.getBytes(StandardCharsets.UTF_8));
            Map.Entry<Long, T> entry = ring.ceilingEntry(pointFromDigest(digest, 0));
            if (entry == null) {
                entry = ring.firstEntry();
            }
            return entry.getValue();
        } finally {
            lock.readLock().unlock();
        }
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
        return "ketama(md5, points=" + pointsPerMember + ")";
    }

    @Override
    public long approximateFootprintBytes() {
        lock.readLock().lock();
        try {
            return (long) ring.size() * 56L;
        } finally {
            lock.readLock().unlock();
        }
    }
}
