package com.example.demo.domain.refund;

import com.example.demo.domain.common.DomainException;
import com.example.demo.domain.event.Event;
import com.example.demo.domain.order.Order;
import com.example.demo.domain.order.Ticket;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class RefundPolicy {

    private final int feePercent;

    public RefundPolicy(int feePercent) {
        if (feePercent < 0 || feePercent > 100) throw new IllegalArgumentException("fee-percent phải trong [0, 100]");
        this.feePercent = feePercent;
    }

    public Map<UUID, Long> check(Order order, Event event, List<Ticket> tickets, Instant now) {
        if (!order.isRefundable()) {
            throw DomainException.conflict("ORDER_NOT_REFUNDABLE",
                    "Chỉ hoàn được đơn đã thanh toán và không có refund đang chạy (hiện tại: " + order.getStatus() + ")");
        }
        if (tickets.isEmpty()) throw DomainException.badRequest("REFUND_EMPTY", "Chưa chọn vé để hoàn");
        if (!event.isRefundOpen(now)) {
            throw DomainException.conflict("REFUND_DEADLINE_PASSED",
                    "Đã quá hạn hủy vé (" + event.getRefundDeadlineHours() + " giờ trước sự kiện)");
        }
        Map<UUID, Long> amounts = new LinkedHashMap<>();
        for (Ticket t : tickets) {
            if (!t.getOrderId().equals(order.getId())) {
                throw DomainException.badRequest("TICKET_NOT_IN_ORDER", "Vé " + t.getId() + " không thuộc đơn này");
            }
            if (!t.isActive()) {
                throw DomainException.conflict("TICKET_NOT_REFUNDABLE", "Vé " + t.getTicketCode() + " đang " + t.getStatus());
            }
            amounts.put(t.getId(), t.getPrice() - t.getPrice() * feePercent / 100);
        }
        return amounts;
    }
}
