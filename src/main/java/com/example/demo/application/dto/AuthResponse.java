package com.example.demo.application.dto;

import com.example.demo.infrastructure.security.JwtService;
import io.swagger.v3.oas.annotations.media.Schema;

public record AuthResponse(
        @Schema(description = "JWT, gửi lại ở header: Authorization: Bearer <accessToken>") String accessToken,
        @Schema(example = "Bearer") String tokenType,
        @Schema(description = "Số giây token còn hiệu lực", example = "900") long expiresIn,
        @Schema(description = "Opaque token để lấy access token mới qua POST /auth/refresh (rotation)") String refreshToken,
        @Schema(description = "Số giây refresh token còn hiệu lực", example = "1209600") long refreshExpiresIn,
        UserResponse user
) {
    public static AuthResponse of(JwtService.IssuedToken access, String refreshToken, long refreshExpiresIn, UserResponse user) {
        return new AuthResponse(access.value(), "Bearer", access.expiresInSeconds(), refreshToken, refreshExpiresIn, user);
    }
}
