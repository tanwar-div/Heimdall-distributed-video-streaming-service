package com.example.heimdall.gateway.dto;

import com.example.heimdall.common.dto.ObjectMetadataDto;
import com.example.heimdall.gateway.service.ObjectGatewayService;

public record UploadResponseDto(
        String objectId,
        String primaryId,
        String contentType,
        long totalSize,
        int chunkCount
) {
    public static UploadResponseDto from(ObjectGatewayService.UploadResult result) {
        ObjectMetadataDto meta = result.metadata();
        return new UploadResponseDto(meta.objectId(), result.primaryId(), meta.contentType(), meta.totalSize(), meta.chunkCount());
    }
}
