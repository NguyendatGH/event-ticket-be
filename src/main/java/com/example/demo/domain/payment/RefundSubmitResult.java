package com.example.demo.domain.payment;

/** Kết quả gửi lệnh hoàn. providerRefundId là khóa để poll về sau. */
public record RefundSubmitResult(String providerRefundId, RefundStatusResult.Status status, String rawResponse) {}
