package com.example.demo.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Đăng ký tài khoản ORGANIZER kèm hồ sơ BTC trong một bước. Field tùy chọn gửi "" coi như bỏ trống. */
public record RegisterOrganizerRequest(
        @Schema(example = "Nguyen Van C") @NotBlank @Size(max = 200) String fullName,
        @Schema(example = "c@example.com") @NotBlank @Email @Size(max = 200) String email,
        @Schema(example = "password123") @NotBlank @Size(min = 8, max = 72) @PasswordBytes String password,
        @Schema(example = "Sunrise Live") @NotBlank @Size(max = 200) String organizerName,
        @Size(max = 5000) String organizerDescription,
        @Pattern(regexp = OrganizerProfileRequest.PHONE) String contactPhone,
        @Pattern(regexp = OrganizerProfileRequest.WEBSITE, message = "Website phải bắt đầu bằng http:// hoặc https://")
        @Size(max = 500) String website,
        @Size(max = 100) String city
) {}
