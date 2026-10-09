package com.example.demo.domain.wallet;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "buyer_wallet_transactions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BuyerWalletTransaction {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private BuyerWalletTransactionType type;

    @Column(nullable = false)
    private long amount;

    @Column(name = "balance_after", nullable = false)
    private long balanceAfter;

    @Column(name = "ref_type", length = 30)
    private String refType;

    @Column(name = "ref_id")
    private UUID refId;

    @Column(length = 500)
    private String note;

    @Column(name = "idempotency_key", length = 100)
    private String idempotencyKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public static BuyerWalletTransaction of(UUID userId, BuyerWalletTransactionType type, long amount,
                                            long balanceAfter, String refType, UUID refId, String note) {
        return of(userId, type, amount, balanceAfter, refType, refId, note, null);
    }

    public static BuyerWalletTransaction of(UUID userId, BuyerWalletTransactionType type, long amount,
                                            long balanceAfter, String refType, UUID refId, String note, String idempotencyKey) {
        BuyerWalletTransaction tx = new BuyerWalletTransaction();
        tx.id = UUID.randomUUID();
        tx.userId = userId;
        tx.type = type;
        tx.amount = amount;
        tx.balanceAfter = balanceAfter;
        tx.refType = refType;
        tx.refId = refId;
        tx.note = note;
        tx.idempotencyKey = idempotencyKey;
        tx.createdAt = Instant.now();
        return tx;
    }
}
