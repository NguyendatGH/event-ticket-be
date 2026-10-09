package com.example.demo.application.dto;

import com.example.demo.domain.event.Venue;
import com.example.demo.domain.order.TicketStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record MyTicketResponse(
        UUID id,
        String ticketCode,
        TicketStatus status,
        @Schema(description = "Giá gốc (mệnh giá) của vé") long price,
        Instant issuedAt,
        Tier tier,
        Event event,
        @Schema(description = "Đơn đã cấp vé") UUID orderId,
        long orderCode,
        @Schema(nullable = true, description = "Chỉ có ở chi tiết") List<TicketHistoryItem> history
) {
    public record Tier(UUID id, String name) {}

    public record Event(UUID id, String slug, String name, Instant startsAt, Instant endsAt, Venue venue,
                        String coverImageUrl, String status) {}

    public MyTicketResponse withHistory(List<TicketHistoryItem> items) {
        return new MyTicketResponse(id, ticketCode, status, price, issuedAt, tier, event, orderId, orderCode, items);
    }
}
