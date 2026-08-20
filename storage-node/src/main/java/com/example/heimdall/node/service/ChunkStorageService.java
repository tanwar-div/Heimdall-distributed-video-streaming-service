package com.example.heimdall.node.service;

import com.example.heimdall.common.dto.ObjectMetadataDto;
import com.example.heimdall.node.config.NodeProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectsArgs;
import io.minio.Result;
import io.minio.errors.ErrorResponseException;
import io.minio.errors.MinioException;
import io.minio.messages.DeleteRequest;
import io.minio.messages.DeleteResult;
import io.minio.messages.Item;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * The write/read path for one node's object storage. Every object is split
 * into fixed-size chunks as it streams in, and each chunk is persisted as its
 * own MinIO object - never buffered whole in memory or on local disk. That's
 * what lets both an upload and a read of a multi-gigabyte video stay bounded
 * to roughly one chunk's worth of memory at a time.
 */
@Service
public class ChunkStorageService {

    private static final Logger log = LoggerFactory.getLogger(ChunkStorageService.class);
    private static final String META_OBJECT = "meta.json";

    private final MinioClient minioClient;
    private final NodeProperties properties;
    private final ObjectMapper objectMapper;

    public ChunkStorageService(MinioClient minioClient, NodeProperties properties, ObjectMapper objectMapper) {
        this.minioClient = minioClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    private String bucket() {
        return properties.getId();
    }

    private static String chunkKey(String objectId, int index) {
        return objectId + "/chunks/" + index;
    }

    private static String metaKey(String objectId) {
        return objectId + "/" + META_OBJECT;
    }

    public ObjectMetadataDto store(String objectId, InputStream rawData, String contentType, String originalFilename) {
        String effectiveContentType = (contentType == null || contentType.isBlank())
                ? "application/octet-stream" : contentType;
        if (!properties.getAllowedContentTypes().contains(effectiveContentType)) {
            throw new UnsupportedContentTypeException("Content type not allowed: " + effectiveContentType
                    + " (allowed: " + properties.getAllowedContentTypes() + ")");
        }

        int chunkSize = properties.getChunkSizeBytes();
        byte[] buffer = new byte[chunkSize];
        long totalSize = 0;
        int chunkCount = 0;

        try {
            while (true) {
                int read = readFully(rawData, buffer);
                if (read <= 0) {
                    break;
                }
                totalSize += read;
                if (totalSize > properties.getMaxObjectSizeBytes()) {
                    throw new ObjectTooLargeException(
                            "Object exceeds max size of " + properties.getMaxObjectSizeBytes() + " bytes");
                }
                putChunk(objectId, chunkCount, buffer, read);
                chunkCount++;
                if (read < chunkSize) {
                    break; // short read means the source stream is exhausted
                }
            }
        } catch (IOException e) {
            throw new NodeStorageException("Failed reading upload stream for object " + objectId, e);
        }

        if (chunkCount == 0) {
            putChunk(objectId, 0, buffer, 0);
            chunkCount = 1;
        }

        ObjectMetadataDto meta = new ObjectMetadataDto(
                objectId, effectiveContentType, originalFilename, totalSize, chunkSize, chunkCount, Instant.now());
        putMeta(meta);
        log.info("[{}] stored '{}': {} bytes across {} chunks", properties.getId(), objectId, totalSize, chunkCount);
        return meta;
    }

    /** Reads a byte[] fully, looping over partial reads, and returns the number of bytes actually read (0 only at true EOF). */
    private static int readFully(InputStream in, byte[] buffer) throws IOException {
        int total = 0;
        while (total < buffer.length) {
            int n = in.read(buffer, total, buffer.length - total);
            if (n == -1) {
                break;
            }
            total += n;
        }
        return total;
    }

    private void putChunk(String objectId, int index, byte[] buffer, int length) {
        try {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket())
                    .object(chunkKey(objectId, index))
                    .stream(new ByteArrayInputStream(buffer, 0, length), (long) length, -1L)
                    .contentType("application/octet-stream")
                    .build());
        } catch (Exception e) {
            throw new NodeStorageException("Failed writing chunk " + index + " of " + objectId, e);
        }
    }

    private void putMeta(ObjectMetadataDto meta) {
        try {
            byte[] json = objectMapper.writeValueAsBytes(meta);
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket())
                    .object(metaKey(meta.objectId()))
                    .stream(new ByteArrayInputStream(json), (long) json.length, -1L)
                    .contentType("application/json")
                    .build());
        } catch (Exception e) {
            throw new NodeStorageException("Failed writing metadata for " + meta.objectId(), e);
        }
    }

    /** Streams a single chunk's bytes straight from MinIO. Caller is responsible for closing the returned stream. */
    public GetObjectResponse getChunk(String objectId, int index) {
        try {
            return minioClient.getObject(GetObjectArgs.builder()
                    .bucket(bucket())
                    .object(chunkKey(objectId, index))
                    .build());
        } catch (ErrorResponseException e) {
            if ("NoSuchKey".equals(e.errorResponse().code())) {
                throw new NoSuchElementException("Chunk " + index + " of object " + objectId + " not found on node " + properties.getId());
            }
            throw new NodeStorageException("Failed reading chunk " + index + " of " + objectId, e);
        } catch (Exception e) {
            throw new NodeStorageException("Failed reading chunk " + index + " of " + objectId, e);
        }
    }

    public ObjectMetadataDto getMetadata(String objectId) {
        try (InputStream in = minioClient.getObject(GetObjectArgs.builder()
                .bucket(bucket())
                .object(metaKey(objectId))
                .build())) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            in.transferTo(out);
            return objectMapper.readValue(out.toByteArray(), ObjectMetadataDto.class);
        } catch (ErrorResponseException e) {
            if ("NoSuchKey".equals(e.errorResponse().code())) {
                throw new NoSuchElementException("Object not found: " + objectId);
            }
            throw new NodeStorageException("Failed reading metadata for " + objectId, e);
        } catch (Exception e) {
            throw new NodeStorageException("Failed reading metadata for " + objectId, e);
        }
    }

    public void delete(String objectId) {
        // Confirm it exists first so callers get a clean 404 instead of a silent no-op.
        getMetadata(objectId);

        List<DeleteRequest.Object> toDelete = new ArrayList<>();
        Iterable<Result<Item>> listing = minioClient.listObjects(ListObjectsArgs.builder()
                .bucket(bucket())
                .prefix(objectId + "/")
                .recursive(true)
                .build());
        try {
            for (Result<Item> result : listing) {
                toDelete.add(new DeleteRequest.Object(result.get().objectName()));
            }
            Iterable<Result<DeleteResult.Error>> errors = minioClient.removeObjects(RemoveObjectsArgs.builder()
                    .bucket(bucket())
                    .objects(toDelete)
                    .build());
            for (Result<DeleteResult.Error> errorResult : errors) {
                DeleteResult.Error error = errorResult.get();
                log.warn("[{}] failed to delete {}: {}", properties.getId(), error.objectName(), error.message());
            }
        } catch (MinioException e) {
            throw new NodeStorageException("Failed deleting object " + objectId, e);
        }
        log.info("[{}] deleted '{}' ({} objects)", properties.getId(), objectId, toDelete.size());
    }

    public List<String> listObjectIds() {
        List<String> ids = new ArrayList<>();
        Iterable<Result<Item>> listing = minioClient.listObjects(ListObjectsArgs.builder()
                .bucket(bucket())
                .recursive(false)
                .build());
        try {
            for (Result<Item> result : listing) {
                Item item = result.get();
                if (item.isDir()) {
                    String name = item.objectName();
                    ids.add(name.endsWith("/") ? name.substring(0, name.length() - 1) : name);
                }
            }
        } catch (Exception e) {
            throw new NodeStorageException("Failed listing objects", e);
        }
        return ids;
    }
}
