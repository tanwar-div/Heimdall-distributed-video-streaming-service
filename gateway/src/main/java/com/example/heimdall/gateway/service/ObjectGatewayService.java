package com.example.heimdall.gateway.service;

import com.example.heimdall.common.dto.ObjectMetadataDto;
import com.example.heimdall.gateway.model.PrimaryNode;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static com.example.heimdall.gateway.service.MetricsService.DELETES;
import static com.example.heimdall.gateway.service.MetricsService.OUTCOME_FAILURE;
import static com.example.heimdall.gateway.service.MetricsService.OUTCOME_SUCCESS;
import static com.example.heimdall.gateway.service.MetricsService.TAG_OUTCOME;
import static com.example.heimdall.gateway.service.MetricsService.TAG_PRIMARY;
import static com.example.heimdall.gateway.service.MetricsService.UPLOADS;

/** The write path: resolve the owning primary for an object key, then forward straight through to it. */
@Service
public class ObjectGatewayService {

    private static final Logger log = LoggerFactory.getLogger(ObjectGatewayService.class);

    private final LoadBalancerService loadBalancerService;
    private final NodeClient nodeClient;
    private final List<PrimaryNode> primaryNodes;
    private final MeterRegistry meterRegistry;

    public ObjectGatewayService(LoadBalancerService loadBalancerService, NodeClient nodeClient,
                                 List<PrimaryNode> primaryNodes, MeterRegistry meterRegistry) {
        this.loadBalancerService = loadBalancerService;
        this.nodeClient = nodeClient;
        this.primaryNodes = primaryNodes;
        this.meterRegistry = meterRegistry;
    }

    public UploadResult upload(String objectId, InputStream data, Long contentLength, String contentType, String originalFilename) {
        PrimaryNode primary = loadBalancerService.resolvePrimary(objectId);
        try {
            ObjectMetadataDto meta = nodeClient.store(primary.baseUrl(), objectId, data, contentLength, contentType, originalFilename);
            meterRegistry.counter(UPLOADS, TAG_OUTCOME, OUTCOME_SUCCESS, TAG_PRIMARY, primary.id()).increment();
            return new UploadResult(primary.id(), meta);
        } catch (RuntimeException e) {
            meterRegistry.counter(UPLOADS, TAG_OUTCOME, OUTCOME_FAILURE, TAG_PRIMARY, primary.id()).increment();
            throw e;
        }
    }

    public void delete(String objectId) {
        PrimaryNode primary = loadBalancerService.resolvePrimary(objectId);
        try {
            nodeClient.delete(primary.baseUrl(), objectId);
            meterRegistry.counter(DELETES, TAG_OUTCOME, OUTCOME_SUCCESS).increment();
        } catch (RuntimeException e) {
            meterRegistry.counter(DELETES, TAG_OUTCOME, OUTCOME_FAILURE).increment();
            throw e;
        }
    }

    /** Best-effort union of every object id across every primary; a primary that's down is skipped, not fatal. */
    public List<String> listAll() {
        List<String> all = new ArrayList<>();
        for (PrimaryNode primary : primaryNodes) {
            try {
                all.addAll(nodeClient.list(primary.baseUrl()));
            } catch (NodeUnavailableException e) {
                log.warn("skipping {} while listing objects: {}", primary.id(), e.getMessage());
            }
        }
        return all;
    }

    public record UploadResult(String primaryId, ObjectMetadataDto metadata) {
    }
}
