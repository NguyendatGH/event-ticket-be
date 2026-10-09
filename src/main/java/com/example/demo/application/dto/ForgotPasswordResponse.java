package com.example.demo.application.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

public record ForgotPasswordResponse(
        boolean sent,
        long expiresInMinutes,
        @Schema(description = "Chỉ có ở profile dev: link đặt lại mật khẩu để test không cần email", nullable = true)
        @JsonInclude(JsonInclude.Include.NON_NULL) String devResetUrl
) {}
