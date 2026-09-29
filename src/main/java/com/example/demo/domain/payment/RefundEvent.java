package com.example.demo.domain.payment;

/**
 * Webhook kết quả refund đã verify chữ ký. PayOS KHÔNG có webhook lệnh chi nên chỉ test double dùng cái này;
 * đường chính thức với PayOS là poll. {@code eventId} duy nhất theo provider để chống trùng qua bảng webhook_events.
 */
public record RefundEvent(String eventId, String providerRefundId, String referenceId, RefundStatusResult.Status status,
                          String failureCode, String failureReason, String rawPayload) {}
