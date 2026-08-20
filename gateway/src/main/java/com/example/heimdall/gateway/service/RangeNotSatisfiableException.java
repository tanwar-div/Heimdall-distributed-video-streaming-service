package com.example.heimdall.gateway.service;

public class RangeNotSatisfiableException extends RuntimeException {

    private final long totalSize;

    public RangeNotSatisfiableException(String message, long totalSize) {
        super(message);
        this.totalSize = totalSize;
    }

    public long getTotalSize() {
        return totalSize;
    }
}
