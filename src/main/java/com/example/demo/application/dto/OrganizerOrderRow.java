package com.example.demo.application.dto;

import java.time.Instant;
import java.util.UUID;

/** Đơn của một sự kiện, cho BTC xem. quantity = tổng số vé trong đơn. */
public record OrganizerOrderRow(UUID id, long orderCode, String status, Customer customer, long quantity,
                                long subtotalAmount, long totalAmount, Instant createdAt, Instant paidAt) {

    public record Customer(String name, String email, String phone) {}
}
