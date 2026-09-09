package com.example.heimdall.gateway.controller;

import com.example.heimdall.gateway.dto.MetricsSummaryDto;
import com.example.heimdall.gateway.dto.NodeHealthDto;
import com.example.heimdall.gateway.service.MetricsService;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Dashboard-facing endpoints: aggregated traffic counters and live per-node reachability. */
@RestController
public class MetricsController {

    private final MetricsService metricsService;

    public MetricsController(MetricsService metricsService) {
        this.metricsService = metricsService;
    }

    @Operation(summary = "Aggregated upload/download/failover counters and average chunk-fetch latency.")
    @GetMapping("/metrics/summary")
    public MetricsSummaryDto summary() {
        return metricsService.summary();
    }

    @Operation(summary = "Live UP/DOWN status of every primary and replica the gateway knows about.")
    @GetMapping("/cluster/health")
    public List<NodeHealthDto> health() {
        return metricsService.health();
    }
}
