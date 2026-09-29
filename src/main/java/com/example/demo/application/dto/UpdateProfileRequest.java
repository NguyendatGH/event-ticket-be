package com.example.demo.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UpdateProfileRequest(
        @NotBlank @Size(max = 200) String fullName,
        @Pattern(regexp = OrganizerProfileRequest.PHONE, message = "Số điện thoại không hợp lệ") String phone,
        @Size(max = 500) String bio,
        @Size(max = 1000) String avatarUrl
) {}
