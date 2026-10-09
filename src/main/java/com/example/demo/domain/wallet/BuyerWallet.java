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
@Table(name = "buyer_wallets")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BuyerWallet {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Column(nullable = false)
    private long balance;

    @Column(name = "total_topups", nullable = false)
    private long totalTopUps;

    @Column(name = "total_spent", nullable = false)
    private long totalSpent;

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

    public static BuyerWallet create(UUID userId) {
        BuyerWallet wallet = new BuyerWallet();
        wallet.userId = userId;
        return wallet;
    }

    public void topUp(long amount) {
        requirePositive(amount);
        balance += amount;
        totalTopUps += amount;
    }

    public void spend(long amount) {
        requirePositive(amount);
        if (balance < amount) throw new IllegalStateException("Buyer wallet balance is insufficient");
        balance -= amount;
        totalSpent += amount;
    }

    public void creditRefund(long amount) {
        requirePositive(amount);
        balance += amount;
        totalRefunds += amount;
    }

    private static void requirePositive(long amount) {
        if (amount <= 0) throw new IllegalArgumentException("Wallet amount must be positive");
    }
}
