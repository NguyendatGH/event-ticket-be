package com.example.demo.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record UploadResponse(
        @Schema(example = "/uploads/avatars/0b6f….jpg") String url,
        @Schema(example = "image/jpeg") String contentType,
        long bytes
) {}
