package com.example.demo.application.dto;

import com.example.demo.domain.user.User;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * Một dòng lịch sử vé. Tên người đã che: "Tran Thi B" → "Tran B.".
 * Không lưu bảng riêng: vé chỉ có một mốc (phát hành cho chủ vé), suy ra từ chính dòng tickets.
 */
public record TicketHistoryItem(
        @Schema(allowableValues = "ISSUED") String type,
        @Schema(nullable = true) Long price,
        Instant at,
        @Schema(nullable = true) Party from,
        @Schema(nullable = true) Party to
) {
    public record Party(String displayName) {}

    public static TicketHistoryItem issued(long price, Instant at, String ownerName) {
        return new TicketHistoryItem("ISSUED", price, at, null, party(ownerName));
    }

    private static Party party(String fullName) {
        return fullName == null ? null : new Party(User.maskedName(fullName));
    }
}
