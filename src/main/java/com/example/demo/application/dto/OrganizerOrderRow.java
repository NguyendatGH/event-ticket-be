package com.example.demo.application.dto;

import java.time.Instant;
import java.util.UUID;

public record OrganizerOrderRow(UUID id, long orderCode, String status, Customer customer, long quantity,
                                long subtotalAmount, long totalAmount, Instant createdAt, Instant paidAt) {

    public record Customer(String name, String email, String phone) {}
}
