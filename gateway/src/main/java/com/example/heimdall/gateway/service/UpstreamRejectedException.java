package com.example.heimdall.gateway.service;

/** A definitive 4xx rejection from a node (bad content type, object too large, ...) that should be surfaced to the caller as-is. */
public class UpstreamRejectedException extends RuntimeException {

    private final int status;

    public UpstreamRejectedException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int getStatus() {
        return status;
    }
}
