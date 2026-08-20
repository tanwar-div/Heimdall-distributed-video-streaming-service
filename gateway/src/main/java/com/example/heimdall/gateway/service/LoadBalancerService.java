package com.example.heimdall.gateway.service;

import com.example.heimdall.common.ring.ConsistentHashRing;
import com.example.heimdall.gateway.config.GatewayProperties;
import com.example.heimdall.gateway.model.PrimaryNode;
import com.example.heimdall.gateway.model.ReplicaNode;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Given an object request, finds the owning primary on the ring and hands
 * back a customizable percentage of that primary's read replicas.
 */
@Service
public class LoadBalancerService {

    private final ConsistentHashRing<PrimaryNode> ring;
    private final GatewayProperties properties;
    private final Random random = new Random();

    public LoadBalancerService(ConsistentHashRing<PrimaryNode> ring, GatewayProperties properties) {
        this.ring = ring;
        this.properties = properties;
    }

    /** Which primary in the ring owns this object. */
    public PrimaryNode resolvePrimary(String objectId) {
        return ring.getMemberFor(objectId);
    }

    /** A customized percentage of the owning primary's read replicas, randomly chosen and shuffled. */
    public List<ReplicaNode> selectReadReplicas(String objectId, Integer requestedPercent) {
        PrimaryNode primary = resolvePrimary(objectId);
        return selectReadReplicas(primary, requestedPercent);
    }

    public List<ReplicaNode> selectReadReplicas(PrimaryNode primary, Integer requestedPercent) {
        List<ReplicaNode> candidates = new ArrayList<>(primary.replicas());
        if (candidates.isEmpty()) {
            return candidates;
        }

        int percent = requestedPercent != null ? requestedPercent : properties.getDefaultReadPercent();
        percent = Math.max(1, Math.min(100, percent));

        Collections.shuffle(candidates, random);
        int count = Math.max(1, (int) Math.ceil(candidates.size() * percent / 100.0));
        return candidates.subList(0, count);
    }
}
