package com.example.demo.domain.order;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "tickets")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Ticket {

    @Id
    private UUID id;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(name = "ticket_tier_id", nullable = false)
    private UUID ticketTierId;

    @Column(name = "owner_id")
    private UUID ownerId;

    @Column(nullable = false)
    private long price;

    @Column(name = "ticket_code", nullable = false, length = 100)
    private String ticketCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TicketStatus status;

    @CreationTimestamp
    @Column(name = "issued_at", nullable = false, updatable = false)
    private Instant issuedAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static Ticket issue(UUID orderId, UUID ticketTierId, long price, UUID ownerId) {
        Ticket t = new Ticket();
        t.id = UUID.randomUUID();
        t.orderId = orderId;
        t.ticketTierId = ticketTierId;
        t.ownerId = ownerId;
        t.price = price;
        t.ticketCode = newCode();
        t.status = TicketStatus.ACTIVE;
        return t;
    }

    public void markRefundPending() { require(TicketStatus.ACTIVE, TicketStatus.REFUND_PENDING); status = TicketStatus.REFUND_PENDING; }

    public void markRefunded() { require(TicketStatus.REFUND_PENDING, TicketStatus.REFUNDED); status = TicketStatus.REFUNDED; }

    /** Refund thất bại: vé dùng lại được. */
    public void restoreActive() { require(TicketStatus.REFUND_PENDING, TicketStatus.ACTIVE); status = TicketStatus.ACTIVE; }

    public boolean isActive() { return status == TicketStatus.ACTIVE; }

    private void require(TicketStatus expected, TicketStatus next) {
        if (status != expected) throw new IllegalStateException("Vé " + ticketCode + " đang " + status + ", không thể sang " + next);
    }

    private static String newCode() {
        return UUID.randomUUID().toString();   // GIỚI HẠN: UUID đủ cho demo; mã ngắn + checksum nếu cần in QR đẹp
    }
}
