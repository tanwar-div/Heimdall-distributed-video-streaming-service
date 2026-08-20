package com.example.heimdall.node.service;

public class ObjectTooLargeException extends RuntimeException {
    public ObjectTooLargeException(String message) {
        super(message);
    }
}
