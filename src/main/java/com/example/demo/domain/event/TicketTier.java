package com.example.demo.domain.event;

import com.example.demo.domain.common.DomainException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ticket_tiers")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TicketTier {

    @Id
    private UUID id;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(nullable = false, length = 200)
    private String name;

    private String description;

    @Column(nullable = false)
    private long price;

    @Column(name = "total_quantity", nullable = false)
    private int totalQuantity;

    @Column(name = "max_per_order", nullable = false)
    private int maxPerOrder;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public TicketTier(UUID eventId, String name, String description, long price, int totalQuantity, int maxPerOrder) {
        this.id = UUID.randomUUID();
        this.eventId = eventId;
        this.name = name;
        this.description = description;
        this.price = price;
        this.totalQuantity = totalQuantity;
        this.maxPerOrder = maxPerOrder;
    }

    public void update(String name, String description, long price, int totalQuantity, int maxPerOrder, int heldCount) {
        if (price != this.price && heldCount > 0) {
            throw DomainException.conflict("TIER_PRICE_LOCKED", "Hạng vé \"" + this.name + "\" đã có vé bán/giữ, không đổi giá được");
        }
        this.name = name;
        this.description = description;
        this.price = price;
        this.totalQuantity = totalQuantity;
        this.maxPerOrder = maxPerOrder;
    }
}
