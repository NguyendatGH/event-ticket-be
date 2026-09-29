package com.example.demo.domain.payment;

import java.time.Instant;
import java.util.List;


public record CreatePaymentCommand(
        long orderCode,
        long amount,
        String description,
        List<Item> items,
        String returnUrl,
        String cancelUrl,
        Instant expiresAt
) {
    public record Item(String name, int quantity, long price) {}
}
