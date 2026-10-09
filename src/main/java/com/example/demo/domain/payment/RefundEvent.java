package com.example.demo.domain.payment;

public record RefundEvent(String eventId, String providerRefundId, String referenceId, RefundStatusResult.Status status,
                          String failureCode, String failureReason, String rawPayload) {}
