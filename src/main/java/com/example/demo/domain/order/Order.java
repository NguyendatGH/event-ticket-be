package com.example.demo.domain.order;

import com.example.demo.domain.common.DomainException;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Entity
@Table(name = "orders")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Order {

    @Id
    private UUID id;

    @Column(name = "order_code", nullable = false)
    private long orderCode;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "user_id")
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private OrderStatus status;

    @Column(name = "subtotal_amount", nullable = false)
    private long subtotalAmount;

    @Column(name = "fee_amount", nullable = false)
    private long feeAmount;

    @Column(name = "total_amount", nullable = false)
    private long totalAmount;

    @Column(name = "paid_amount", nullable = false)
    private long paidAmount;

    @Column(name = "refunded_amount", nullable = false)
    private long refundedAmount;

    @Column(name = "customer_name", nullable = false, length = 200)
    private String customerName;

    @Column(name = "customer_email", nullable = false, length = 200)
    private String customerEmail;

    @Column(name = "customer_phone", length = 30)
    private String customerPhone;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "idempotency_key", length = 100)
    private String idempotencyKey;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Version
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "order_id", nullable = false)
    @BatchSize(size = 50)
    private List<OrderItem> items = new ArrayList<>();


    public static Order create(UUID eventId, List<OrderItem> items, String customerName, String customerEmail,
                               String customerPhone, long fee, Instant expiresAt, String idempotencyKey, UUID userId) {
        if (items.isEmpty()) throw DomainException.badRequest("ORDER_EMPTY", "Đơn hàng chưa có vé");
        Order o = new Order();
        o.id = UUID.randomUUID();
        o.orderCode = System.currentTimeMillis() * 1000 + ThreadLocalRandom.current().nextInt(1000);
        o.eventId = eventId;
        o.userId = userId;
        o.status = OrderStatus.PENDING_PAYMENT;
        o.items.addAll(items);
        o.subtotalAmount = items.stream().mapToLong(OrderItem::lineTotal).sum();
        o.feeAmount = fee;
        o.totalAmount = o.subtotalAmount + fee;
        o.customerName = customerName;
        o.customerEmail = customerEmail;
        o.customerPhone = customerPhone;
        o.expiresAt = expiresAt;
        o.idempotencyKey = idempotencyKey;
        o.createdAt = Instant.now();
        return o;
    }

    public void expire() {
        requirePending("hết hạn");
        status = OrderStatus.EXPIRED;
    }

    public void markPaid(long amount) {
        requirePending("thanh toán");
        status = OrderStatus.PAID;
        paidAmount = amount;
        paidAt = Instant.now();
    }

    public void markManualReview() {
        if (status != OrderStatus.EXPIRED && status != OrderStatus.CANCELLED) {
            throw new IllegalStateException("MANUAL_REVIEW chỉ từ EXPIRED/CANCELLED, hiện tại " + status);
        }
        status = OrderStatus.MANUAL_REVIEW;
    }

    public void cancel() {
        if (status != OrderStatus.PENDING_PAYMENT) {
            throw DomainException.conflict("ORDER_NOT_CANCELLABLE", "Chỉ hủy được đơn đang chờ thanh toán (hiện tại: " + status + ")");
        }
        status = OrderStatus.CANCELLED;
    }

    public void startRefund() {
        if (!isRefundable()) {
            throw DomainException.conflict("ORDER_NOT_REFUNDABLE",
                    "Chỉ hoàn được đơn đã thanh toán và không có refund đang chạy (hiện tại: " + status + ")");
        }
        status = OrderStatus.REFUND_PROCESSING;
    }

    public void onRefundSucceeded(long amount, long remainingTickets) {
        requireRefundProcessing();
        refundedAmount += amount;
        status = remainingTickets == 0 ? OrderStatus.REFUNDED : OrderStatus.PARTIALLY_REFUNDED;
    }

    public void onRefundFailed() {
        requireRefundProcessing();
        status = OrderStatus.REFUND_FAILED;
    }

   
    public void requireOwner(UUID actorId) {
        if (actorId == null || !actorId.equals(userId)) {
            throw DomainException.notFound("ORDER_NOT_FOUND", "Không tìm thấy đơn hàng");
        }
    }

    public boolean isRefundable() {
        return status == OrderStatus.PAID || status == OrderStatus.PARTIALLY_REFUNDED || status == OrderStatus.REFUND_FAILED;
    }

    private void requireRefundProcessing() {
        if (status != OrderStatus.REFUND_PROCESSING) {
            throw new IllegalStateException("Đơn đang " + status + ", không nhận kết quả refund");
        }
    }

    public boolean isPending() {
        return status == OrderStatus.PENDING_PAYMENT;
    }

    private void requirePending(String action) {
        if (!isPending()) throw new IllegalStateException("Không thể " + action + " đơn ở trạng thái " + status);
    }
}
