package com.example.demo.application.dto;

import com.example.demo.domain.refund.Refund;
import com.example.demo.domain.refund.RefundExecutionMethod;
import com.example.demo.domain.refund.RefundInitiator;
import com.example.demo.domain.refund.RefundStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record RefundResponse(
        UUID id,
        UUID orderId,
        RefundStatus status,
        long amount,
        RefundExecutionMethod executionMethod,
        RefundInitiator initiator,
        String providerRefundId,
        String failureCode,
        String failureReason,
        String destinationBin,
        String destinationAccountMasked,
        Boolean destinationIsPayer,
        List<Item> items,
        String reason,
        int attempt,
        Instant submittedAt,
        Instant createdAt,
        Instant updatedAt
) {
    public record Item(UUID ticketId, long amount) {}

    public static RefundResponse from(Refund r) {
        return new RefundResponse(r.getId(), r.getOrderId(), r.getStatus(), r.getAmount(), r.getExecutionMethod(),
                r.getInitiator(), r.getProviderRefundId(), r.getFailureCode(), r.getFailureReason(),
                r.getDestinationBin(), mask(r.getDestinationAccount()), r.getDestinationIsPayer(),
                r.getItems().stream().map(i -> new Item(i.getTicketId(), i.getAmount())).toList(),
                r.getReason(), r.getAttempt(), r.getSubmittedAt(), r.getCreatedAt(), r.getUpdatedAt());
    }

    /** Số tài khoản chỉ lộ 4 số cuối ra ngoài API, kể cả cho admin. */
    static String mask(String account) {
        if (account == null || account.length() <= 4) return account;
        return "*".repeat(account.length() - 4) + account.substring(account.length() - 4);
    }
}
