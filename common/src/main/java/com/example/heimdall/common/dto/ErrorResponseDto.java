package com.example.heimdall.common.dto;

import java.time.Instant;

/** Standard JSON error body returned by both services on failure. */
public record ErrorResponseDto(
        Instant timestamp,
        int status,
        String error,
        String message,
        String path
) {
    public static ErrorResponseDto of(int status, String error, String message, String path) {
        return new ErrorResponseDto(Instant.now(), status, error, message, path);
    }
}
