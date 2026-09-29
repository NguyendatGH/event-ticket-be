package com.example.demo.domain.refund;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

/** Một vé trong một refund, kèm số tiền hoàn cho vé đó (giá trừ phí hủy). */
@Entity
@Table(name = "refund_items")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefundItem {

    @Id
    private UUID id;

    @Column(name = "ticket_id", nullable = false)
    private UUID ticketId;

    @Column(nullable = false)
    private long amount;

    public RefundItem(UUID ticketId, long amount) {
        this.id = UUID.randomUUID();
        this.ticketId = ticketId;
        this.amount = amount;
    }
}
