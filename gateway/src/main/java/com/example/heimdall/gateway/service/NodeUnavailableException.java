package com.example.heimdall.gateway.service;

/** A network-level or 5xx failure talking to a specific node - the caller should try a different candidate node. */
public class NodeUnavailableException extends RuntimeException {
    public NodeUnavailableException(String baseUrl, Throwable cause) {
        super("Node unavailable: " + baseUrl, cause);
    }
}
