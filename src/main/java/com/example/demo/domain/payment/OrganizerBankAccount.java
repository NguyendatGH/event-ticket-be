package com.example.demo.domain.payment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "organizer_bank_accounts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrganizerBankAccount {

    @Id
    private UUID id;

    @Column(name = "organizer_id", nullable = false)
    private UUID organizerId;

    @Column(name = "bank_name", nullable = false, length = 120)
    private String bankName;

    @Column(name = "bank_bin", length = 6)
    private String bankBin;

    @Column(name = "account_name", nullable = false, length = 120)
    private String accountName;

    @Column(name = "account_number", nullable = false, length = 30)
    private String accountNumber;

    @Column(name = "is_default", nullable = false)
    private boolean isDefault;

    @Column(name = "gateway_synced_at")
    private Instant gatewaySyncedAt;
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static OrganizerBankAccount create(UUID organizerId, String bankName, String bankBin,
                                               String accountName, String accountNumber, boolean isDefault) {
        OrganizerBankAccount account = new OrganizerBankAccount();
        account.id = UUID.randomUUID();
        account.organizerId = organizerId;
        account.updateDetails(bankName, bankBin, accountName, accountNumber);
        account.isDefault = isDefault;
        return account;
    }

    public void updateDetails(String bankName, String bankBin, String accountName, String accountNumber) {
        this.bankName = bankName;
        this.bankBin = bankBin;
        this.accountName = accountName;
        this.accountNumber = accountNumber;
        this.gatewaySyncedAt = null;
    }

    public void markDefault(boolean value) {
        this.isDefault = value;
    }
}
