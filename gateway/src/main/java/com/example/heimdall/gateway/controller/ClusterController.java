package com.example.heimdall.gateway.controller;

import com.example.heimdall.gateway.model.PrimaryNode;
import com.example.heimdall.gateway.model.ReplicaNode;
import com.example.heimdall.gateway.service.LoadBalancerService;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Read-only endpoints for inspecting what the load balancer/ring decided, independent of actually streaming anything. */
@RestController
public class ClusterController {

    private final LoadBalancerService loadBalancerService;
    private final List<PrimaryNode> primaryNodes;

    public ClusterController(LoadBalancerService loadBalancerService, List<PrimaryNode> primaryNodes) {
        this.loadBalancerService = loadBalancerService;
        this.primaryNodes = primaryNodes;
    }

    @Operation(summary = "Which primary owns this object key, and which of its replicas would serve a read at the given percent.")
    @GetMapping("/objects/{objectId}/replicas")
    public Map<String, Object> whichReplicas(@PathVariable String objectId,
                                              @RequestParam(required = false) Integer readPercent) {
        PrimaryNode primary = loadBalancerService.resolvePrimary(objectId);
        List<ReplicaNode> selected = loadBalancerService.selectReadReplicas(primary, readPercent);

        return Map.of(
                "objectId", objectId,
                "primary", primary.id(),
                "allReplicas", primary.replicas().stream().map(ReplicaNode::id).collect(Collectors.toList()),
                "selectedReplicas", selected.stream().map(ReplicaNode::id).collect(Collectors.toList())
        );
    }

    @Operation(summary = "The full cluster topology the gateway was configured with.")
    @GetMapping("/cluster")
    public List<Map<String, Object>> cluster() {
        return primaryNodes.stream()
                .map(p -> Map.<String, Object>of(
                        "id", p.id(),
                        "baseUrl", p.baseUrl(),
                        "replicas", p.replicas().stream()
                                .map(r -> Map.of("id", r.id(), "baseUrl", r.baseUrl()))
                                .collect(Collectors.toList())
                ))
                .collect(Collectors.toList());
    }
}
