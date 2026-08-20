package com.example.heimdall.gateway.controller;

import com.example.heimdall.gateway.dto.UploadResponseDto;
import com.example.heimdall.gateway.service.ObjectGatewayService;
import com.example.heimdall.gateway.service.RangeNotSatisfiableException;
import com.example.heimdall.gateway.service.StreamingOrchestratorService;
import com.example.heimdall.gateway.service.StreamingOrchestratorService.PreparedStream;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRange;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/objects")
public class ObjectController {

    private final ObjectGatewayService gatewayService;
    private final StreamingOrchestratorService streamingService;

    public ObjectController(ObjectGatewayService gatewayService, StreamingOrchestratorService streamingService) {
        this.gatewayService = gatewayService;
        this.streamingService = streamingService;
    }

    @Operation(summary = "Upload a video as a multipart form field named 'file'. Requires X-API-Key.")
    @PostMapping(value = "/{objectId}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<UploadResponseDto> uploadMultipart(@PathVariable String objectId,
                                                               @RequestPart("file") MultipartFile file) throws IOException {
        var result = gatewayService.upload(objectId, file.getInputStream(), file.getSize(),
                file.getContentType(), file.getOriginalFilename());
        return ResponseEntity.status(HttpStatus.CREATED).body(UploadResponseDto.from(result));
    }

    @Operation(summary = "Upload a video as a raw request body (any Content-Type). Requires X-API-Key.")
    @PostMapping("/{objectId}")
    public ResponseEntity<UploadResponseDto> uploadRaw(@PathVariable String objectId, HttpServletRequest request)
            throws IOException {
        long declaredLength = request.getContentLengthLong();
        Long contentLength = declaredLength >= 0 ? declaredLength : null;
        var result = gatewayService.upload(objectId, request.getInputStream(), contentLength,
                request.getContentType(), null);
        return ResponseEntity.status(HttpStatus.CREATED).body(UploadResponseDto.from(result));
    }

    @Operation(summary = "Download (or seek within, via a Range header) a video. Fetched concurrently from multiple replicas. Public.")
    @GetMapping("/{objectId}")
    public ResponseEntity<StreamingResponseBody> download(
            @PathVariable String objectId,
            @Parameter(description = "Percent of the owning primary's replicas to read from, 1-100")
            @RequestParam(required = false) Integer readPercent,
            @RequestHeader(value = HttpHeaders.RANGE, required = false) String rangeHeader) {

        PreparedStream prepared = streamingService.prepare(objectId, readPercent);
        long totalSize = prepared.meta().totalSize();

        long start = 0;
        long end = totalSize - 1;
        boolean partial = false;

        if (rangeHeader != null) {
            List<HttpRange> ranges;
            try {
                ranges = HttpRange.parseRanges(rangeHeader);
            } catch (IllegalArgumentException malformed) {
                ranges = List.of(); // malformed Range header: fall back to a full 200 response, per RFC 7233
            }
            if (!ranges.isEmpty()) {
                HttpRange range = ranges.get(0); // only the first range of a (rare) multi-range request is honored
                try {
                    start = range.getRangeStart(totalSize);
                    end = range.getRangeEnd(totalSize);
                } catch (IllegalArgumentException unsatisfiable) {
                    throw new RangeNotSatisfiableException("Requested range not satisfiable", totalSize);
                }
                partial = true;
            }
        }

        long contentLength = totalSize == 0 ? 0 : (end - start + 1);
        long finalStart = start;
        long finalEnd = end;
        StreamingResponseBody body = out -> streamingService.stream(prepared, finalStart, finalEnd, out);

        ResponseEntity.BodyBuilder response = partial
                ? ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                        .header(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + totalSize)
                : ResponseEntity.ok();

        return response
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(contentLength))
                .contentType(MediaType.parseMediaType(prepared.meta().contentType()))
                .body(body);
    }

    @Operation(summary = "Delete a video from its primary and all its replicas. Requires X-API-Key.")
    @DeleteMapping("/{objectId}")
    public ResponseEntity<Void> delete(@PathVariable String objectId) {
        gatewayService.delete(objectId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Best-effort list of every object id known across the cluster.")
    @GetMapping
    public List<String> list() {
        return gatewayService.listAll();
    }
}
