package com.example.heimdall.gateway.service;

import com.example.heimdall.common.dto.ErrorResponseDto;
import com.example.heimdall.common.dto.ObjectMetadataDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.InputStream;
import java.util.List;
import java.util.NoSuchElementException;

/** Thin, exception-translating wrapper around HTTP calls to one storage node's internal API. */
@Component
public class NodeClient {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public NodeClient(RestClient nodeRestClient, ObjectMapper objectMapper) {
        this.restClient = nodeRestClient;
        this.objectMapper = objectMapper;
    }

    public ObjectMetadataDto store(String baseUrl, String objectId, InputStream body, Long contentLength,
                                    String contentType, String originalFilename) {
        try {
            RestClient.RequestBodySpec spec = restClient.put()
                    .uri(baseUrl + "/internal/objects/{objectId}", objectId)
                    .header(HttpHeaders.CONTENT_TYPE, contentType == null ? "application/octet-stream" : contentType)
                    .header("X-Original-Filename", originalFilename == null ? "" : originalFilename);
            if (contentLength != null && contentLength >= 0) {
                spec = spec.header(HttpHeaders.CONTENT_LENGTH, String.valueOf(contentLength));
            }
            return spec.body(new InputStreamResource(body))
                    .retrieve()
                    .body(ObjectMetadataDto.class);
        } catch (HttpClientErrorException e) {
            throw translateRejection(e);
        } catch (RestClientException e) {
            throw new NodeUnavailableException(baseUrl, e);
        }
    }

    public ObjectMetadataDto fetchMeta(String baseUrl, String objectId) {
        try {
            return restClient.get()
                    .uri(baseUrl + "/internal/objects/{objectId}/meta", objectId)
                    .retrieve()
                    .body(ObjectMetadataDto.class);
        } catch (HttpClientErrorException.NotFound e) {
            throw new NoSuchElementException("Object not found: " + objectId);
        } catch (RestClientException e) {
            throw new NodeUnavailableException(baseUrl, e);
        }
    }

    public byte[] fetchChunk(String baseUrl, String objectId, int index) {
        try {
            byte[] chunk = restClient.get()
                    .uri(baseUrl + "/internal/objects/{objectId}/chunks/{index}", objectId, index)
                    .retrieve()
                    .body(byte[].class);
            return chunk == null ? new byte[0] : chunk;
        } catch (HttpClientErrorException.NotFound e) {
            throw new NoSuchElementException("Chunk " + index + " of " + objectId + " not found on " + baseUrl);
        } catch (RestClientException e) {
            throw new NodeUnavailableException(baseUrl, e);
        }
    }

    public void delete(String baseUrl, String objectId) {
        try {
            restClient.delete()
                    .uri(baseUrl + "/internal/objects/{objectId}", objectId)
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpClientErrorException.NotFound e) {
            throw new NoSuchElementException("Object not found: " + objectId);
        } catch (RestClientException e) {
            throw new NodeUnavailableException(baseUrl, e);
        }
    }

    public List<String> list(String baseUrl) {
        try {
            List<String> ids = restClient.get()
                    .uri(baseUrl + "/internal/objects")
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<String>>() {
                    });
            return ids == null ? List.of() : ids;
        } catch (RestClientException e) {
            throw new NodeUnavailableException(baseUrl, e);
        }
    }

    private UpstreamRejectedException translateRejection(HttpStatusCodeException e) {
        String message = e.getStatusText();
        try {
            byte[] responseBody = e.getResponseBodyAsByteArray();
            if (responseBody.length > 0) {
                ErrorResponseDto error = objectMapper.readValue(responseBody, ErrorResponseDto.class);
                message = error.message();
            }
        } catch (Exception ignored) {
            // fall back to the raw status text below
        }
        return new UpstreamRejectedException(e.getStatusCode().value(), message);
    }
}
