package com.example.heimdall.node.controller;

import com.example.heimdall.common.dto.ObjectMetadataDto;
import com.example.heimdall.node.config.NodeProperties;
import com.example.heimdall.node.service.ChunkStorageService;
import com.example.heimdall.node.service.ReplicationService;
import io.minio.GetObjectResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;

/**
 * Node-internal API. Not meant to be reached by end users directly - in the
 * docker-compose topology, only the gateway's port is published to the host;
 * every node listens on the internal compose network only.
 */
@RestController
@RequestMapping("/internal/objects")
public class NodeStorageController {

    private final ChunkStorageService chunkStorageService;
    private final ReplicationService replicationService;
    private final NodeProperties properties;

    public NodeStorageController(ChunkStorageService chunkStorageService, ReplicationService replicationService,
                                  NodeProperties properties) {
        this.chunkStorageService = chunkStorageService;
        this.replicationService = replicationService;
        this.properties = properties;
    }

    /** Stores the raw request body as a new object, streaming it straight through to MinIO. */
    @PutMapping("/{objectId}")
    public ResponseEntity<ObjectMetadataDto> store(@PathVariable String objectId, HttpServletRequest request)
            throws IOException {
        String contentType = request.getContentType();
        String originalFilename = request.getHeader("X-Original-Filename");
        ObjectMetadataDto meta = chunkStorageService.store(objectId, request.getInputStream(), contentType, originalFilename);
        if (properties.isPrimary()) {
            replicationService.replicate(meta);
        }
        return ResponseEntity.status(201).body(meta);
    }

    @GetMapping("/{objectId}/meta")
    public ObjectMetadataDto meta(@PathVariable String objectId) {
        return chunkStorageService.getMetadata(objectId);
    }

    @GetMapping("/{objectId}/chunks/{index}")
    public ResponseEntity<InputStreamResource> chunk(@PathVariable String objectId, @PathVariable int index) {
        GetObjectResponse stream = chunkStorageService.getChunk(objectId, index);
        ResponseEntity.BodyBuilder response = ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM);
        String contentLength = stream.headers().get(HttpHeaders.CONTENT_LENGTH);
        if (contentLength != null) {
            response.header(HttpHeaders.CONTENT_LENGTH, contentLength);
        }
        return response.body(new InputStreamResource(stream));
    }

    @DeleteMapping("/{objectId}")
    public ResponseEntity<Void> delete(@PathVariable String objectId) {
        chunkStorageService.delete(objectId);
        if (properties.isPrimary()) {
            replicationService.delete(objectId);
        }
        return ResponseEntity.noContent().build();
    }

    @GetMapping
    public List<String> list() {
        return chunkStorageService.listObjectIds();
    }
}
