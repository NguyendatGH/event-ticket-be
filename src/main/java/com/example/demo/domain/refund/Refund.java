package com.example.demo.domain.refund;

import com.example.demo.domain.common.DomainException;
import com.example.demo.domain.payment.Payment;
import com.example.demo.domain.payment.PaymentProvider;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "refunds")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Refund {

    @Id
    private UUID id;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(name = "payment_id")
    private UUID paymentId;

    @Column(name = "original_payment_transaction_id", length = 100)
    private String originalPaymentTransactionId;

    @Column(nullable = false)
    private long amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RefundStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "execution_method", length = 20)
    private RefundExecutionMethod executionMethod;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RefundInitiator initiator;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private PaymentProvider provider;

    @Column(name = "provider_refund_id", length = 100)
    private String providerRefundId;

    @Column(name = "idempotency_key", length = 100)
    private String idempotencyKey;

    @Column(nullable = false)
    private int attempt;

    @Column(name = "failure_code", length = 50)
    private String failureCode;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "destination_bin", length = 20)
    private String destinationBin;

    @Column(name = "destination_account")
    private String destinationAccount;

    @Column(name = "destination_is_payer")
    private Boolean destinationIsPayer;

    private String reason;

    @Column(name = "contact_email", length = 200)
    private String contactEmail;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "last_polled_at")
    private Instant lastPolledAt;

    @Column(name = "queued_since")
    private Instant queuedSince;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "refund_id", nullable = false)
    private List<RefundItem> items = new ArrayList<>();

    public static Refund request(UUID orderId, Payment payment, List<RefundItem> items, long amount,
                                 RefundInitiator initiator, String reason, String contactEmail,
                                 String destinationBin, String destinationAccount, boolean destinationIsPayer) {
        if (items.isEmpty()) throw DomainException.badRequest("REFUND_EMPTY", "Chưa chọn vé để hoàn");
        if (amount <= 0) throw DomainException.badRequest("REFUND_AMOUNT_INVALID", "Số tiền hoàn phải lớn hơn 0");
        Refund r = new Refund();
        r.id = UUID.randomUUID();
        r.orderId = orderId;
        r.paymentId = payment.getId();
        r.originalPaymentTransactionId = payment.getProviderTransactionRef();
        r.amount = amount;
        r.currency = "VND";
        r.status = RefundStatus.REQUESTED;
        r.executionMethod = RefundExecutionMethod.PAYOUT;
        r.initiator = initiator;
        r.provider = payment.getProvider();
        r.attempt = 0;
        r.reason = reason;
        r.contactEmail = contactEmail;
        r.destinationBin = destinationBin;
        r.destinationAccount = destinationAccount;
        r.destinationIsPayer = destinationIsPayer;
        r.items.addAll(items);
        r.createdAt = Instant.now();
        return r;
    }

    public void holdForDestinationReview() {
        require(RefundStatus.REQUESTED, "MANUAL_REVIEW");
        status = RefundStatus.MANUAL_REVIEW;
        failureCode = "DESTINATION_REVIEW";
        failureReason = "Tài khoản nhận khác tài khoản đã thanh toán, cần admin duyệt";
    }

    public String beginAttempt() {
        if (status != RefundStatus.REQUESTED && status != RefundStatus.AWAITING_FUNDS) {
            throw new IllegalStateException("Không gửi được refund đang " + status);
        }
        if (idempotencyKey == null) {
            attempt++;
            idempotencyKey = "RF-" + id + "-" + attempt;
        }
        status = RefundStatus.REQUESTED;
        queuedSince = null;
        submittedAt = Instant.now();
        return idempotencyKey;
    }

    public void submitted(String providerRefundId) {
        require(RefundStatus.REQUESTED, "PROCESSING");
        this.providerRefundId = providerRefundId;
        status = RefundStatus.PROCESSING;
    }

    public void awaitFunds(String code) {
        if (status != RefundStatus.REQUESTED && status != RefundStatus.AWAITING_FUNDS) {
            throw new IllegalStateException("AWAITING_FUNDS chỉ từ REQUESTED/AWAITING_FUNDS, hiện tại " + status);
        }
        status = RefundStatus.AWAITING_FUNDS;
        failureCode = code;
        idempotencyKey = null;
        if (queuedSince == null) queuedSince = Instant.now();
    }

    public void succeed() {
        requireNotTerminal("SUCCEEDED");
        status = RefundStatus.SUCCEEDED;
        failureCode = null;
        failureReason = null;
    }

    public void fail(String code, String reason) {
        requireNotTerminal("FAILED");
        status = RefundStatus.FAILED;
        failureCode = code;
        failureReason = reason;
    }

    public void useManualTransfer() {
        executionMethod = RefundExecutionMethod.MANUAL_TRANSFER;
    }

    public void manualReview(String code, String reason) {
        requireNotTerminal("MANUAL_REVIEW");
        status = RefundStatus.MANUAL_REVIEW;
        failureCode = code;
        failureReason = reason;
    }

    public void retry(String newBin, String newAccount) {
        require(RefundStatus.MANUAL_REVIEW, "REQUESTED");
        if (providerRefundId != null) {
            throw DomainException.conflict("REFUND_ALREADY_AT_PROVIDER",
                    "Lệnh đã nằm ở provider, tra dashboard rồi chốt SUCCEEDED hoặc FAILED, không RETRY");
        }
        if (newBin != null && newAccount != null) {
            destinationBin = newBin;
            destinationAccount = newAccount;
            destinationIsPayer = false;
        }
        status = RefundStatus.REQUESTED;
        idempotencyKey = null;
        failureCode = null;
        failureReason = null;
    }

    public void attachProvider(String providerRefundId) {
        if (this.providerRefundId == null) this.providerRefundId = providerRefundId;
    }

    public void polled() {
        lastPolledAt = Instant.now();
    }

    public boolean isTerminal() {
        return status == RefundStatus.SUCCEEDED || status == RefundStatus.FAILED;
    }

    public boolean acceptsProviderResult() {
        return status == RefundStatus.PROCESSING || status == RefundStatus.REQUESTED;
    }

    public List<UUID> ticketIds() {
        return items.stream().map(RefundItem::getTicketId).toList();
    }

    private void require(RefundStatus expected, String next) {
        if (status != expected) throw new IllegalStateException("Refund " + id + " đang " + status + ", không thể sang " + next);
    }

    private void requireNotTerminal(String next) {
        if (isTerminal()) throw new IllegalStateException("Refund " + id + " đã " + status + ", không thể sang " + next);
    }
}
