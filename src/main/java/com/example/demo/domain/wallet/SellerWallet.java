package com.example.demo.domain.wallet;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "seller_wallets")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SellerWallet {

    @Id
    @Column(name = "organizer_id")
    private UUID organizerId;

    @Column(nullable = false)
    private long balance;

    @Column(name = "total_topups", nullable = false)
    private long totalTopUps;

    @Column(name = "total_sales", nullable = false)
    private long totalSales;

    @Column(name = "total_refunds", nullable = false)
    private long totalRefunds;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    public static SellerWallet create(UUID organizerId) {
        SellerWallet wallet = new SellerWallet();
        wallet.organizerId = organizerId;
        return wallet;
    }

    public void topUp(long amount) {
        requirePositive(amount);
        balance += amount;
        totalTopUps += amount;
    }

    public void creditSale(long amount) {
        requirePositive(amount);
        balance += amount;
        totalSales += amount;
    }

    public void debitRefund(long amount) {
        requirePositive(amount);
        balance -= amount;
        totalRefunds += amount;
    }

    private static void requirePositive(long amount) {
        if (amount <= 0) throw new IllegalArgumentException("Wallet amount must be positive");
    }
}
