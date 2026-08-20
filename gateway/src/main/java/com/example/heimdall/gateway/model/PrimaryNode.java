package com.example.heimdall.gateway.model;

import java.util.List;

/** A primary node on the ring: owns writes for whichever object keys hash to it, and has its own set of replicas. */
public record PrimaryNode(String id, String baseUrl, List<ReplicaNode> replicas) {
}
