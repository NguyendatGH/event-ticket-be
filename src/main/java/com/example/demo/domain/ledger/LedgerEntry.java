package com.example.demo.domain.ledger;

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
@Table(name = "ledger_entries")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LedgerEntry {

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private LedgerAccount account;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private LedgerDirection direction;

    @Column(nullable = false)
    private long amount;

    @Column(name = "ref_type", length = 20)
    private String refType;

    @Column(name = "ref_id")
    private UUID refId;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    public static LedgerEntry of(LedgerAccount account, LedgerDirection direction, long amount,
                                 String refType, UUID refId) {
        LedgerEntry e = new LedgerEntry();
        e.id = UUID.randomUUID();
        e.account = account;
        e.direction = direction;
        e.amount = amount;
        e.refType = refType;
        e.refId = refId;
        e.occurredAt = Instant.now();
        return e;
    }
}
