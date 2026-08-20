package com.example.heimdall.node.controller;

import com.example.heimdall.common.dto.ErrorResponseDto;
import com.example.heimdall.node.service.NodeStorageException;
import com.example.heimdall.node.service.ObjectTooLargeException;
import com.example.heimdall.node.service.UnsupportedContentTypeException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.NoSuchElementException;

@RestControllerAdvice
public class NodeExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(NodeExceptionHandler.class);

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<ErrorResponseDto> handleNotFound(NoSuchElementException e, HttpServletRequest request) {
        return body(HttpStatus.NOT_FOUND, e.getMessage(), request);
    }

    @ExceptionHandler(ObjectTooLargeException.class)
    public ResponseEntity<ErrorResponseDto> handleTooLarge(ObjectTooLargeException e, HttpServletRequest request) {
        return body(HttpStatus.PAYLOAD_TOO_LARGE, e.getMessage(), request);
    }

    @ExceptionHandler(UnsupportedContentTypeException.class)
    public ResponseEntity<ErrorResponseDto> handleUnsupportedType(UnsupportedContentTypeException e, HttpServletRequest request) {
        return body(HttpStatus.UNSUPPORTED_MEDIA_TYPE, e.getMessage(), request);
    }

    @ExceptionHandler(NodeStorageException.class)
    public ResponseEntity<ErrorResponseDto> handleStorage(NodeStorageException e, HttpServletRequest request) {
        log.error("storage failure on {}", request.getRequestURI(), e);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "Storage backend error", request);
    }

    private ResponseEntity<ErrorResponseDto> body(HttpStatus status, String message, HttpServletRequest request) {
        return ResponseEntity.status(status)
                .body(ErrorResponseDto.of(status.value(), status.getReasonPhrase(), message, request.getRequestURI()));
    }
}
