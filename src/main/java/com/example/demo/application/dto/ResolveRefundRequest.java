package com.example.demo.application.dto;

import jakarta.validation.constraints.NotNull;

public record ResolveRefundRequest(@NotNull Outcome outcome, String note, CreateRefundRequest.Destination destination) {
    public enum Outcome { SUCCEEDED, FAILED, RETRY, CANCELLED }
}
