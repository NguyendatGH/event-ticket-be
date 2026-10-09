package com.example.demo.domain.payment;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Payment {

    @Id
    private UUID id;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentProvider provider;

    @Column(name = "provider_payment_id", length = 100)
    private String providerPaymentId;

    @Column(name = "payment_link_id", length = 100)
    private String paymentLinkId;

    @Column(name = "checkout_url")
    private String checkoutUrl;

    @Column(name = "provider_transaction_ref", length = 100)
    private String providerTransactionRef;

    @Column(nullable = false)
    private long amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentStatus status;

    @Column(name = "payer_bank_bin", length = 20)
    private String payerBankBin;

    @Column(name = "payer_account_number")
    private String payerAccountNumber;

    @Column(name = "paid_at")
    private Instant paidAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_webhook_payload")
    private String rawWebhookPayload;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static Payment pending(UUID orderId, PaymentProvider provider, PaymentLink link, long amount) {
        Payment p = new Payment();
        p.id = UUID.randomUUID();
        p.orderId = orderId;
        p.provider = provider;
        p.providerPaymentId = link.providerPaymentId();
        p.paymentLinkId = link.providerPaymentId();
        p.checkoutUrl = link.checkoutUrl();
        p.amount = amount;
        p.status = PaymentStatus.PENDING;
        p.createdAt = Instant.now();
        return p;
    }

    public void confirmPaid(PaymentEvent e) { apply(e, PaymentStatus.PAID); }

    public void markUnderpaid(PaymentEvent e) { apply(e, PaymentStatus.UNDERPAID); }

    public void markLate(PaymentEvent e) { apply(e, PaymentStatus.PAID_LATE); }

    public void markFailed(PaymentEvent e) { apply(e, PaymentStatus.FAILED); }

    public void expire() {
        if (isAwaitingMoney()) status = PaymentStatus.EXPIRED;
    }

    public boolean isAwaitingMoney() {
        return status == PaymentStatus.PENDING || status == PaymentStatus.CREATED || status == PaymentStatus.FAILED;
    }

    public boolean isPaid() { return status == PaymentStatus.PAID; }

    private void apply(PaymentEvent e, PaymentStatus next) {
        status = next;
        providerTransactionRef = e.transactionRef();
        payerBankBin = BankBins.isBin(e.payerBankBin()) ? e.payerBankBin().trim() : null;
        payerAccountNumber = e.payerAccountNumber();
        paidAt = e.paidAt();
        rawWebhookPayload = e.rawPayload();
    }
}
