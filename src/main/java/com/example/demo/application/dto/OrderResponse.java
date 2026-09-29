package com.example.demo.application.dto;

import com.example.demo.domain.event.Event;
import com.example.demo.domain.order.Order;
import com.example.demo.domain.order.OrderItem;
import com.example.demo.domain.order.OrderStatus;
import com.example.demo.domain.order.Ticket;
import com.example.demo.domain.order.TicketStatus;
import com.example.demo.domain.payment.Payment;
import com.example.demo.domain.payment.PaymentProvider;
import com.example.demo.domain.payment.PaymentStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

public record OrderResponse(
        UUID id,
        long orderCode,
        OrderStatus status,
        UUID eventId,
        String eventSlug,
        String eventName,
        List<Item> items,
        long subtotalAmount,
        long feeAmount,
        long totalAmount,
        CustomerInfo customer,
        @Schema(nullable = true, description = "null chỉ khi chưa tạo được link ở provider")
        PaymentInfo payment,
        List<TicketResponse> tickets,
        Instant expiresAt,
        Instant createdAt,
        @Schema(nullable = true) Instant paidAt
) {
    public record Item(UUID tierId, String tierName, int quantity, long unitPrice) {}

    public record TicketResponse(UUID id, String ticketCode, UUID tierId, String tierName, TicketStatus status) {}



    public static OrderResponse from(Order o, Event e, List<Ticket> tickets, Payment payment) {
        Map<UUID, String> tierNames = o.getItems().stream().collect(Collectors.toMap(OrderItem::getTicketTierId, OrderItem::getTierName));
        return new OrderResponse(
                o.getId(), o.getOrderCode(), o.getStatus(), e.getId(), e.getSlug(), e.getName(),
                o.getItems().stream().map(i -> new Item(i.getTicketTierId(), i.getTierName(), i.getQuantity(), i.getUnitPrice())).toList(),
                o.getSubtotalAmount(), o.getFeeAmount(), o.getTotalAmount(),
                new CustomerInfo(o.getCustomerName(), o.getCustomerEmail(), o.getCustomerPhone()),
                PaymentInfo.from(payment),
                tickets.stream().map(t -> new TicketResponse(t.getId(), t.getTicketCode(), t.getTicketTierId(),
                        tierNames.get(t.getTicketTierId()), t.getStatus())).toList(),
                o.getExpiresAt(), o.getCreatedAt(), o.getPaidAt());
    }
}
