package com.example.demo.application.impl;

import com.example.demo.application.OrderQueries;
import com.example.demo.application.dto.OrderResponse;
import com.example.demo.application.dto.PageResponse;
import com.example.demo.application.support.Pages;
import com.example.demo.domain.common.DomainException;
import com.example.demo.domain.event.Event;
import com.example.demo.domain.order.Order;
import com.example.demo.domain.order.OrderStatus;
import com.example.demo.domain.order.Ticket;
import com.example.demo.domain.payment.Payment;
import com.example.demo.infrastructure.persistence.EventRepository;
import com.example.demo.infrastructure.persistence.OrderRepository;
import com.example.demo.infrastructure.persistence.PaymentRepository;
import com.example.demo.infrastructure.persistence.TicketRepository;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Phần ĐỌC của đơn hàng: GET /orders/{id} (OrderController), GET /me/orders (MyTicketsController),
 * audit đơn cho admin, trang giả lập thanh toán. Phần ghi (tạo/hủy/hết hạn) nằm ở CheckoutService.
 */
@Service
public class OrderQueriesImpl implements OrderQueries {

    private final OrderRepository orders;
    private final EventRepository events;
    private final PaymentRepository payments;
    private final TicketRepository tickets;

    public OrderQueriesImpl(OrderRepository orders, EventRepository events, PaymentRepository payments,
                        TicketRepository tickets) {
        this.orders = orders;
        this.events = events;
        this.payments = payments;
        this.tickets = tickets;
    }

    /** Ai có id đơn cũng xem được (khách vãng lai quay về từ cổng thanh toán chỉ có id). */
    @Override
    @Transactional(readOnly = true)
    public OrderResponse get(UUID orderId) {
        return toResponse(load(orderId));
    }

    @Override
    @Transactional(readOnly = true)
    public OrderResponse getOwned(UUID userId, UUID orderId) {
        Order order = load(orderId);
        order.requireOwner(userId);
        return toResponse(order);
    }

    private Order load(UUID orderId) {
        return orders.findById(orderId)
                .orElseThrow(() -> DomainException.notFound("ORDER_NOT_FOUND", "Không tìm thấy đơn hàng"));
    }

    /**
     * Đơn của user, mới nhất trước. Mỗi trang cố định vài query (items, vé, payment, event theo lô).
     * {@code status} tùy chọn: một hoặc nhiều OrderStatus cách nhau dấu phẩy (vd "CANCELLED,EXPIRED");
     * bỏ trống = mọi trạng thái (hành vi cũ).
     */
    @Override
    @Transactional(readOnly = true)
    public PageResponse<OrderResponse> myOrders(UUID userId, String status, int page, int size) {
        Set<OrderStatus> statuses = parseStatuses(status);
        Page<Order> p = statuses.isEmpty()
                ? orders.findAllByUserIdOrderByCreatedAtDesc(userId, Pages.of(page, size))
                : orders.findAllByUserIdAndStatusInOrderByCreatedAtDesc(userId, statuses, Pages.of(page, size));
        return PageResponse.of(p, toResponses(p.getContent()));
    }

    /** "PAID, pending_payment" → {PAID, PENDING_PAYMENT}; phần rỗng bỏ qua; giá trị lạ → 400 VALIDATION (field status). */
    static Set<OrderStatus> parseStatuses(String status) {
        Set<OrderStatus> result = EnumSet.noneOf(OrderStatus.class);
        if (status == null) return result;
        for (String part : status.split(",")) {
            String name = part.trim().toUpperCase(Locale.ROOT);
            if (name.isEmpty()) continue;
            try {
                result.add(OrderStatus.valueOf(name));
            } catch (IllegalArgumentException unknown) {
                throw DomainException.invalid("status", "status phải là một hoặc nhiều giá trị (cách nhau dấu phẩy) trong: "
                        + Arrays.stream(OrderStatus.values()).map(Enum::name).collect(Collectors.joining(", ")));
            }
        }
        return result;
    }

    /** Không tự mở transaction: CheckoutService gọi hàm này bên trong transaction của nó. */
    @Override
    public OrderResponse toResponse(Order order) {
        return toResponses(List.of(order)).getFirst();
    }

    private List<OrderResponse> toResponses(List<Order> list) {
        if (list.isEmpty()) return List.of();
        List<UUID> ids = list.stream().map(Order::getId).toList();
        Map<UUID, Event> eventById = events.findAllById(list.stream().map(Order::getEventId).distinct().toList()).stream()
                .collect(Collectors.toMap(Event::getId, Function.identity()));
        // Một đơn có thể có nhiều payment (tạo lại link); chỉ trả payment mới nhất
        Map<UUID, Payment> latestPayment = new HashMap<>();
        payments.findAllByOrderIdIn(ids).forEach(p -> latestPayment.merge(p.getOrderId(), p,
                (a, b) -> a.getCreatedAt().isAfter(b.getCreatedAt()) ? a : b));
        Map<UUID, List<Ticket>> ticketsByOrder = ticketsStillHeld(list);
        return list.stream()
                .map(o -> OrderResponse.from(o, eventById.get(o.getEventId()),
                        ticketsByOrder.getOrDefault(o.getId(), List.of()), latestPayment.get(o.getId())))
                .toList();
    }

    /**
     * Vé cấp từ từng đơn mà người đặt đơn vẫn là chủ. Vé đã đổi chủ trước khi bỏ tính năng bán lại không hiện ở
     * đơn gốc (đơn tra được bằng id, không được lộ mã vé của chủ mới).
     */
    private Map<UUID, List<Ticket>> ticketsStillHeld(List<Order> list) {
        Map<UUID, Order> byId = list.stream().collect(Collectors.toMap(Order::getId, Function.identity()));
        Map<UUID, List<Ticket>> result = new HashMap<>();
        for (Ticket t : tickets.findAllByOrderIdIn(byId.keySet())) {
            if (Objects.equals(t.getOwnerId(), byId.get(t.getOrderId()).getUserId())) {
                result.computeIfAbsent(t.getOrderId(), k -> new ArrayList<>()).add(t);
            }
        }
        return result;
    }
}
