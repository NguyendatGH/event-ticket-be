package com.example.demo.domain.inventory;

import com.example.demo.domain.common.DomainException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "inventory")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Inventory {

    @Id
    @Column(name = "ticket_tier_id")
    private UUID ticketTierId;

    @Column(nullable = false)
    private int available;

    @Version
    private Long version;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Inventory(UUID ticketTierId, int available) {
        this.ticketTierId = ticketTierId;
        this.available = available;
    }

    public boolean canReserve(int quantity) {
        return quantity > 0 && available >= quantity;
    }

    public void reserve(int quantity) {
        if (!canReserve(quantity)) {
            throw new IllegalStateException("Không đủ vé: còn " + available + ", cần " + quantity);
        }
        available -= quantity;
    }

    public void adjust(int delta) {
        if (available + delta < 0) {
            throw DomainException.conflict("TIER_QUANTITY_BELOW_SOLD",
                    "Tổng số vé không được nhỏ hơn số vé đã bán và đang giữ (" + (-delta - available) + " vé thiếu)");
        }
        available += delta;
    }

    public void release(int quantity) {
        if (quantity <= 0) throw new IllegalArgumentException("Số lượng trả kho phải > 0");
        available += quantity;
    }
}
