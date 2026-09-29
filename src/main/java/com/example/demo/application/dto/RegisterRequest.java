package com.example.demo.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @Schema(example = "Nguyen Van C") @NotBlank @Size(max = 200) String fullName,
        @Schema(example = "c@example.com") @NotBlank @Email @Size(max = 200) String email,
        @Schema(example = "password123") @NotBlank @Size(min = 8, max = 72) @PasswordBytes String password
) {}
