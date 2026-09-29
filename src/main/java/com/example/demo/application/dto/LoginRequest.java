package com.example.demo.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
        @Schema(example = "admin@example.com") @NotBlank @Email String email,
        @Schema(example = "password123") @NotBlank String password
) {}
