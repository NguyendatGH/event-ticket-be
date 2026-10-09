package com.example.demo.application.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public record CreateRefundRequest(
        @NotEmpty List<UUID> ticketIds,
        @NotBlank @Email @Size(max = 200) String contactEmail,
        @Size(max = 500) String reason,
        @Valid Destination destination
) {
    public CreateRefundRequest {
        contactEmail = contactEmail == null ? null : contactEmail.trim();
    }

    public record Destination(@NotBlank String bin, @NotBlank String accountNumber) {}
}
