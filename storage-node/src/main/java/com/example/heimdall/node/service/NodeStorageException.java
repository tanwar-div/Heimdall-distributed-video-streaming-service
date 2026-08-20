package com.example.heimdall.node.service;

/** Unchecked wrapper around the checked-exception zoo the MinIO SDK throws. */
public class NodeStorageException extends RuntimeException {
    public NodeStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
