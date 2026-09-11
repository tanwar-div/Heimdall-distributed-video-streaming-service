package com.example.heimdall.gateway.dto;

import java.util.Map;

/** Aggregated, dashboard-friendly view of the counters/timers the gateway records as it serves traffic. */
public record MetricsSummaryDto(
        double totalUploads,
        double totalUploadFailures,
        double totalDeletes,
        double totalDeleteFailures,
        double totalDownloads,
        double failoverCount,
        double avgChunkFetchLatencyMs,
        Map<String, Double> uploadsByPrimary,
        Map<String, Double> readsByPrimary
) {
}
