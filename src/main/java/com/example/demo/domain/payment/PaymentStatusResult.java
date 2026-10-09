package com.example.demo.domain.payment;

import java.time.Instant;

public record PaymentStatusResult(Status status, long amountPaid, String transactionRef, Instant paidAt) {
    public enum Status { PENDING, PAID, CANCELLED, EXPIRED }
}
