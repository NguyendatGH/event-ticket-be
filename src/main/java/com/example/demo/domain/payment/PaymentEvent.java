package com.example.demo.domain.payment;

import java.time.Instant;

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
