package com.example.demo.domain.payment;

import java.time.Instant;

/**
 * Webhook thanh toán đã verify chữ ký và chuẩn hóa. Domain chỉ thấy record này, không thấy payload provider.
 * {@code eventId} phải duy nhất theo provider để chống trùng (PayOS: paymentLinkId + reference; mock: trường eventId).
 */
public record PaymentEvent(
        String eventId,
        String providerPaymentId,
        long orderCode,
        boolean success,
        long amount,
        String transactionRef,
        Instant paidAt,
        String payerBankBin,
        String payerAccountNumber,
        String rawPayload
) {}
