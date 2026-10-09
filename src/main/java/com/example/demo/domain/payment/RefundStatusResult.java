package com.example.demo.domain.payment;

import java.time.Instant;

public record RefundStatusResult(Status status, String providerRefundId, String failureCode, String failureReason,
                                 Instant completedAt) {

    public enum Status {
        RECEIVED, PROCESSING, SUCCEEDED, FAILED, CANCELLED, ON_HOLD, REVERSED;

        public boolean isFinal() { return this == SUCCEEDED || this == FAILED || this == CANCELLED; }

        public boolean needsHuman() { return this == ON_HOLD || this == REVERSED; }

        public boolean inFlight() { return this == RECEIVED || this == PROCESSING; }
    }

    public boolean isFinal() { return status.isFinal(); }

    public boolean needsHuman() { return status.needsHuman(); }

    public boolean inFlight() { return status.inFlight(); }
}
