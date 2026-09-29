package com.example.demo.domain.order;

public enum OrderStatus {
    PENDING_PAYMENT,
    PAID,
    REFUND_PROCESSING,
    REFUNDED,
    PARTIALLY_REFUNDED,
    REFUND_FAILED,
    EXPIRED,
    CANCELLED,
    MANUAL_REVIEW
}
