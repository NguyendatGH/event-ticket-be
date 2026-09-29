package com.example.demo.application.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record OrganizerProfileRequest(
        @NotBlank @Size(max = 200) String name,
        @Size(max = 5000) String description,
        @Size(max = 1000) String logoUrl,
        @Size(max = 1000) String coverUrl,
        @Pattern(regexp = WEBSITE, message = "Website phải bắt đầu bằng http:// hoặc https://") @Size(max = 500) String website,
        @Size(max = 100) String city,
        @Email @Size(max = 200) String contactEmail,
        @Pattern(regexp = PHONE, message = "Số điện thoại không hợp lệ") String contactPhone
) {
    public static final String PHONE = "^$|^[0-9+ .-]{8,20}$";
    public static final String WEBSITE = "^$|^https?://\\S+$";
}
