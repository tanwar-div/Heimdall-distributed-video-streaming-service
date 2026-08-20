package com.example.heimdall.gateway.controller;

import com.example.heimdall.common.dto.ErrorResponseDto;
import com.example.heimdall.gateway.service.NodeUnavailableException;
import com.example.heimdall.gateway.service.RangeNotSatisfiableException;
import com.example.heimdall.gateway.service.UpstreamRejectedException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.NoSuchElementException;

@RestControllerAdvice
public class GatewayExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GatewayExceptionHandler.class);

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<ErrorResponseDto> handleNotFound(NoSuchElementException e, HttpServletRequest request) {
        return body(HttpStatus.NOT_FOUND, e.getMessage(), request);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorResponseDto> handleConflict(IllegalStateException e, HttpServletRequest request) {
        return body(HttpStatus.CONFLICT, e.getMessage(), request);
    }

    @ExceptionHandler(UpstreamRejectedException.class)
    public ResponseEntity<ErrorResponseDto> handleUpstreamRejected(UpstreamRejectedException e, HttpServletRequest request) {
        HttpStatus status = HttpStatus.resolve(e.getStatus());
        if (status == null) {
            status = HttpStatus.BAD_GATEWAY;
        }
        return body(status, e.getMessage(), request);
    }

    @ExceptionHandler(NodeUnavailableException.class)
    public ResponseEntity<ErrorResponseDto> handleUnavailable(NodeUnavailableException e, HttpServletRequest request) {
        Throwable cause = e.getCause();
        log.warn("node unavailable while serving {}: {} ({})", request.getRequestURI(), e.getMessage(),
                cause == null ? "no cause" : cause.toString());
        return body(HttpStatus.SERVICE_UNAVAILABLE, "The storage cluster is currently unavailable, please retry", request);
    }

    @ExceptionHandler(RangeNotSatisfiableException.class)
    public ResponseEntity<ErrorResponseDto> handleRangeNotSatisfiable(RangeNotSatisfiableException e, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                .header(HttpHeaders.CONTENT_RANGE, "bytes */" + e.getTotalSize())
                .body(ErrorResponseDto.of(416, "Requested Range Not Satisfiable", e.getMessage(), request.getRequestURI()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponseDto> handleTooLarge(MaxUploadSizeExceededException e, HttpServletRequest request) {
        return body(HttpStatus.PAYLOAD_TOO_LARGE, "Upload exceeds the maximum allowed size", request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponseDto> handleUnexpected(Exception e, HttpServletRequest request) {
        log.error("unexpected error serving {}", request.getRequestURI(), e);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error", request);
    }

    private ResponseEntity<ErrorResponseDto> body(HttpStatus status, String message, HttpServletRequest request) {
        return ResponseEntity.status(status)
                .body(ErrorResponseDto.of(status.value(), status.getReasonPhrase(), message, request.getRequestURI()));
    }
}
