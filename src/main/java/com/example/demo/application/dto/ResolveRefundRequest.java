package com.example.demo.application.dto;

import jakarta.validation.constraints.NotNull;

/** Admin chốt một refund MANUAL_REVIEW. RETRY chỉ hợp lệ khi provider chưa từng nhận lệnh. */
public record ResolveRefundRequest(@NotNull Outcome outcome, String note, CreateRefundRequest.Destination destination) {
    public enum Outcome { SUCCEEDED, FAILED, RETRY }
}
