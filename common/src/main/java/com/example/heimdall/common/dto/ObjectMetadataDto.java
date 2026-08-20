package com.example.heimdall.common.dto;

import java.time.Instant;

/**
 * Wire format for an object's metadata, as returned by a storage node's
 * {@code GET /internal/objects/{objectId}/meta} endpoint and consumed by the
 * gateway to plan chunk fetches / byte-range math without touching chunk data.
 */
public record ObjectMetadataDto(
        String objectId,
        String contentType,
        String originalFilename,
        long totalSize,
        int chunkSize,
        int chunkCount,
        Instant createdAt
) {
}
