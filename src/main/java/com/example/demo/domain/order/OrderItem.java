package com.example.demo.domain.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Entity
@Table(name = "order_items")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderItem {

    @Id
    private UUID id;

    @Column(name = "ticket_tier_id", nullable = false)
    private UUID ticketTierId;

    @Column(name = "tier_name", nullable = false, length = 200)
    private String tierName;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "unit_price", nullable = false)
    private long unitPrice;

    public OrderItem(UUID ticketTierId, String tierName, int quantity, long unitPrice) {
        this.id = UUID.randomUUID();
        this.ticketTierId = ticketTierId;
        this.tierName = tierName;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
    }

    public long lineTotal() {
        return unitPrice * quantity;
    }
}
