package com.example.heimdall.gateway.model;

/** A read replica the gateway knows about, identified by its own base URL on the compose/cluster network. */
public record ReplicaNode(String id, String baseUrl) {
}
