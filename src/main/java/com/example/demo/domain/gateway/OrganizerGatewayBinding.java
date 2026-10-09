package com.example.demo.domain.gateway;

import com.example.demo.domain.common.DomainException;
import jakarta.persistence.*;
import lombok.Getter;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Entity
@Getter
@Table(name = "organizer_gateway_bindings")
public class OrganizerGatewayBinding {

    public enum Status { PENDING, ACTIVE, SUSPENDED, FAILED }

    @Id
    private UUID id;

    @Column(name = "organizer_id", nullable = false)
    private UUID organizerId;

    @Column(nullable = false, length = 30)
    private String provider;

    @Column(name = "gateway_merchant_no", length = 32)
    private String gatewayMerchantNo;

    @Column(name = "gateway_terminal_id", length = 32)
    private String gatewayTerminalId;

    @Column(name = "encrypted_merchant_secret", columnDefinition = "text")
    private String encryptedMerchantSecret;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status;

    @Column(name = "external_reference", length = 128)
    private String externalReference;

    @Column(name = "last_sync_at")
    private Instant lastSyncAt;

    @Column(name = "provisioning_error", columnDefinition = "text")
    private String provisioningError;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected OrganizerGatewayBinding() {
    }

    public static OrganizerGatewayBinding pending(UUID organizerId, String provider, String externalReference) {
        OrganizerGatewayBinding b = new OrganizerGatewayBinding();
        b.id = UUID.randomUUID();
        b.organizerId = organizerId;
        b.provider = provider;
        b.externalReference = externalReference;
        b.status = Status.PENDING;
        b.createdAt = Instant.now();
        b.updatedAt = b.createdAt;
        return b;
    }

    public void activate(String merchantNo, String terminalId, String encryptedSecret) {
        this.gatewayMerchantNo = merchantNo;
        this.gatewayTerminalId = terminalId;
        this.encryptedMerchantSecret = encryptedSecret;
        this.status = Status.ACTIVE;
        this.provisioningError = null;
        this.lastSyncAt = Instant.now();
        touch();
    }

    public void beginProvisioning(Duration staleAfter) {
        if (status == Status.PENDING && updatedAt.isAfter(Instant.now().minus(staleAfter)))
            throw DomainException.conflict("GATEWAY_PROVISIONING_IN_PROGRESS",
                    "Đang cấp phát cổng thanh toán cho ban tổ chức này, thử lại sau ít giây");
        this.status = Status.PENDING;
        touch();
    }

    public void fail(String error) {
        this.status = Status.FAILED;
        this.provisioningError = error == null ? null : error.substring(0, Math.min(error.length(), 1000));
        touch();
    }

    public void useTerminal(String terminalId) {
        this.gatewayTerminalId = terminalId;
        this.lastSyncAt = Instant.now();
        touch();
    }

    public void replaceSecret(String encryptedSecret) {
        this.encryptedMerchantSecret = encryptedSecret;
        this.lastSyncAt = Instant.now();
        touch();
    }

    public boolean isUsable() {
        return status == Status.ACTIVE && gatewayMerchantNo != null && gatewayTerminalId != null
                && encryptedMerchantSecret != null;
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }
}
