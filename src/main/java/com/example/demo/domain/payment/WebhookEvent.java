package com.example.demo.domain.payment;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * Sổ ghi mọi webhook nhận được, kể cả sai chữ ký. Unique (provider, event_id) là lớp chống trùng thật:
 * insert trước khi xử lý, trùng thì DataIntegrityViolation.
 */
@Entity
@Table(name = "webhook_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WebhookEvent {

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentProvider provider;

    @Column(name = "event_id", nullable = false, length = 200)
    private String eventId;

    @Column(name = "event_type", length = 50)
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_payload")
    private String rawPayload;

    @Column(name = "signature_valid", nullable = false)
    private boolean signatureValid;

    @Enumerated(EnumType.STRING)
    @Column(name = "processing_result", length = 30)
    private WebhookProcessingResult processingResult;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    @Column(name = "processed_at")
    private Instant processedAt;

    public static WebhookEvent received(PaymentProvider provider, String eventId, String eventType, String rawPayload) {
        WebhookEvent w = new WebhookEvent();
        w.id = UUID.randomUUID();
        w.provider = provider;
        w.eventId = eventId;
        w.eventType = eventType;
        w.rawPayload = rawPayload;
        w.signatureValid = true;
        w.receivedAt = Instant.now();
        return w;
    }

    /** Webhook sai chữ ký vẫn ghi lại để audit; event_id là UUID vì không tin được payload. */
    public static WebhookEvent rejected(PaymentProvider provider, String rawPayload) {
        WebhookEvent w = received(provider, "rejected:" + UUID.randomUUID(), "payment", rawPayload);
        w.signatureValid = false;
        w.processingResult = WebhookProcessingResult.REJECTED_SIGNATURE;
        w.processedAt = w.receivedAt;
        return w;
    }

    public void finish(WebhookProcessingResult result) {
        processingResult = result;
        processedAt = Instant.now();
    }
}
