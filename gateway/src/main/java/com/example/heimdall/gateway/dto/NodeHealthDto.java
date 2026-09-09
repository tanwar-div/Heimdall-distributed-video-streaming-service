package com.example.heimdall.gateway.dto;

/** One node's reachability as observed by the gateway, for the dashboard's cluster health panel. */
public record NodeHealthDto(String id, String baseUrl, String role, String status) {
}
