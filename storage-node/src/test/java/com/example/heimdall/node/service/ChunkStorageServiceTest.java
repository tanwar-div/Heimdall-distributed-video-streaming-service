package com.example.heimdall.node.service;

import com.example.heimdall.common.dto.ObjectMetadataDto;
import com.example.heimdall.node.config.NodeProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.ErrorResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChunkStorageServiceTest {

    @Mock
    private MinioClient minioClient;

    private NodeProperties properties;
    private ChunkStorageService service;

    @BeforeEach
    void setUp() {
        properties = new NodeProperties();
        properties.setId("primary-0");
        properties.setChunkSizeBytes(10);
        properties.setMaxObjectSizeBytes(1000);
        service = new ChunkStorageService(minioClient, properties, new ObjectMapper().registerModule(new JavaTimeModule()));
    }

    @Test
    void splitsDataIntoFixedSizeChunksPlusAShortTrailingChunk() throws Exception {
        byte[] data = new byte[25]; // 10 + 10 + 5 with a chunk size of 10
        ObjectMetadataDto meta = service.store("video-1", new ByteArrayInputStream(data), "video/mp4", "clip.mp4");

        assertThat(meta.totalSize()).isEqualTo(25);
        assertThat(meta.chunkCount()).isEqualTo(3);
        assertThat(meta.chunkSize()).isEqualTo(10);
        assertThat(meta.contentType()).isEqualTo("video/mp4");

        ArgumentCaptor<PutObjectArgs> captor = ArgumentCaptor.forClass(PutObjectArgs.class);
        verify(minioClient, times(4)).putObject(captor.capture()); // 3 chunks + meta.json
        List<String> objectNames = captor.getAllValues().stream().map(PutObjectArgs::object).toList();
        assertThat(objectNames).containsExactly(
                "video-1/chunks/0", "video-1/chunks/1", "video-1/chunks/2", "video-1/meta.json");
    }

    @Test
    void storesExactlyOneEmptyChunkForAnEmptyUpload() throws Exception {
        ObjectMetadataDto meta = service.store("empty-video", new ByteArrayInputStream(new byte[0]), "video/mp4", null);

        assertThat(meta.totalSize()).isZero();
        assertThat(meta.chunkCount()).isEqualTo(1);
        verify(minioClient, times(2)).putObject(any()); // one empty chunk + meta.json
    }

    @Test
    void rejectsContentTypesNotOnTheAllowList() {
        assertThatThrownBy(() -> service.store("bad", new ByteArrayInputStream(new byte[5]), "application/x-evil", null))
                .isInstanceOf(UnsupportedContentTypeException.class);
    }

    @Test
    void rejectsObjectsLargerThanTheConfiguredMax() {
        byte[] tooBig = new byte[2000]; // > maxObjectSizeBytes of 1000
        assertThatThrownBy(() -> service.store("huge", new ByteArrayInputStream(tooBig), "video/mp4", null))
                .isInstanceOf(ObjectTooLargeException.class);
    }

    @Test
    void getMetadataTranslatesNoSuchKeyIntoNoSuchElementException() throws Exception {
        ErrorResponse notFound = new ErrorResponse("NoSuchKey", "not found", "primary-0", "missing/meta.json", "", "", "");
        when(minioClient.getObject(any())).thenThrow(new ErrorResponseException(notFound, null, "trace-id"));

        assertThatThrownBy(() -> service.getMetadata("missing"))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void defaultsMissingContentTypeToOctetStream() throws Exception {
        ObjectMetadataDto meta = service.store("no-type", new ByteArrayInputStream(new byte[5]), null, null);
        assertThat(meta.contentType()).isEqualTo("application/octet-stream");
    }
}
