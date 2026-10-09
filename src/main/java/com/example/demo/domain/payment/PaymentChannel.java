package com.example.demo.domain.payment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
@Table(name = "organizer_payment_channels")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentChannel {

    public enum Status { ACTIVE, REMOVED }

    @Id
    private UUID id;

    @Column(name = "organizer_id", nullable = false)
    private UUID organizerId;

    @Column(name = "gateway_terminal_id", nullable = false, length = 32)
    private String gatewayTerminalId;

    @Column(name = "bank_code", nullable = false, length = 64)
    private String bankCode;

    @Column(name = "bank_bin", nullable = false, length = 6)
    private String bankBin;

    @Column(name = "bank_name", nullable = false, length = 120)
    private String bankName;

    @Column(name = "account_name", nullable = false, length = 120)
    private String accountName;

    @Column(name = "account_number", nullable = false, length = 30)
    private String accountNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status;

    @Column(name = "opened_at", nullable = false)
    private Instant openedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static PaymentChannel open(UUID organizerId, String terminalId, String bankCode, String bankBin, String bankName,
                                      String accountName, String accountNumber) {
        PaymentChannel channel = new PaymentChannel();
        channel.id = UUID.randomUUID();
        channel.organizerId = organizerId;
        channel.gatewayTerminalId = terminalId;
        channel.bankCode = bankCode;
        channel.bankBin = bankBin;
        channel.bankName = bankName;
        channel.accountName = accountName;
        channel.accountNumber = accountNumber;
        channel.status = Status.ACTIVE;
        channel.openedAt = Instant.now();
        return channel;
    }

    public boolean isActive() {
        return status == Status.ACTIVE;
    }

    public void reopen(String accountName, String accountNumber) {
        if (isActive()) throw new IllegalStateException("Kênh " + id + " đang hoạt động, không mở lại được");
        this.status = Status.ACTIVE;
        this.openedAt = Instant.now();
        changeAccount(accountName, accountNumber);
    }

    public void changeAccount(String accountName, String accountNumber) {
        this.accountName = accountName;
        this.accountNumber = accountNumber;
    }

    public void remove() {
        if (!isActive()) throw new IllegalStateException("Kênh " + id + " đã bị xóa");
        this.status = Status.REMOVED;
    }
}
