package com.example.demo.domain.payment;

import java.time.Instant;

/** Trạng thái link tại provider, dùng cho job hết hạn và đối chiếu. */
public record PaymentStatusResult(Status status, long amountPaid, String transactionRef, Instant paidAt) {
    public enum Status { PENDING, PAID, CANCELLED, EXPIRED }
}
