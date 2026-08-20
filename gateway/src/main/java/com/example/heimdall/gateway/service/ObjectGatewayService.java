package com.example.heimdall.gateway.service;

import com.example.heimdall.common.dto.ObjectMetadataDto;
import com.example.heimdall.gateway.model.PrimaryNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** The write path: resolve the owning primary for an object key, then forward straight through to it. */
@Service
public class ObjectGatewayService {

    private static final Logger log = LoggerFactory.getLogger(ObjectGatewayService.class);

    private final LoadBalancerService loadBalancerService;
    private final NodeClient nodeClient;
    private final List<PrimaryNode> primaryNodes;

    public ObjectGatewayService(LoadBalancerService loadBalancerService, NodeClient nodeClient, List<PrimaryNode> primaryNodes) {
        this.loadBalancerService = loadBalancerService;
        this.nodeClient = nodeClient;
        this.primaryNodes = primaryNodes;
    }

    public UploadResult upload(String objectId, InputStream data, Long contentLength, String contentType, String originalFilename) {
        PrimaryNode primary = loadBalancerService.resolvePrimary(objectId);
        ObjectMetadataDto meta = nodeClient.store(primary.baseUrl(), objectId, data, contentLength, contentType, originalFilename);
        return new UploadResult(primary.id(), meta);
    }

    public void delete(String objectId) {
        PrimaryNode primary = loadBalancerService.resolvePrimary(objectId);
        nodeClient.delete(primary.baseUrl(), objectId);
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
