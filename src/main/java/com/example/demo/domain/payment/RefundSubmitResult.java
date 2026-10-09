package com.example.demo.domain.payment;

public record RefundSubmitResult(String providerRefundId, RefundStatusResult.Status status, String rawResponse) {}
