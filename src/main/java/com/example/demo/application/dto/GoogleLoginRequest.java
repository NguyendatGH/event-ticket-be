package com.example.demo.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/** id_token (JWT) FE nhận được từ Google Identity Services sau khi user bấm nút đăng nhập. */
public record GoogleLoginRequest(
        @Schema(description = "id_token do Google phát, FE lấy từ credential của nút Google")
        @NotBlank String idToken
) {}
