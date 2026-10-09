package com.example.demo.domain.payment;

import java.time.Instant;
import java.util.List;

public record CreatePaymentCommand(
        java.util.UUID organizerId,
        long orderCode,
        long amount,
        String description,
        String paymentMethod,
        List<Item> items,
        String returnUrl,
        String cancelUrl,
        Instant expiresAt
) {
    public CreatePaymentCommand masked() {
        return this;
    }

    public CreatePaymentCommand(java.util.UUID organizerId, long orderCode, long amount, String description,
                                List<Item> items, String returnUrl, String cancelUrl, Instant expiresAt) {
        this(organizerId, orderCode, amount, description, "CARD", items, returnUrl, cancelUrl, expiresAt);
    }

    public record Item(String name, int quantity, long price) {}
}
