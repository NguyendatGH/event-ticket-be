package com.example.demo.application.dto;

import com.example.demo.domain.payment.Payment;
import com.example.demo.domain.payment.PaymentProvider;
import com.example.demo.domain.payment.PaymentStatus;

import java.time.Instant;

public record PaymentInfo(PaymentProvider provider, PaymentStatus status, String checkoutUrl, String paymentLinkId,
                          String qrCode, String transactionRef, Instant paidAt) {
    static PaymentInfo from(Payment p) {
        return p == null ? null : new PaymentInfo(p.getProvider(), p.getStatus(), p.getCheckoutUrl(),
                p.getPaymentLinkId(), null, p.getProviderTransactionRef(), p.getPaidAt());
    }
}