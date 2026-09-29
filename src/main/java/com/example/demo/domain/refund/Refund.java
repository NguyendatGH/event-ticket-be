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

/**
 * Một lần hoàn tiền cho một nhóm vé của một đơn, chạy bằng lệnh chi (PAYOUT) chứ không đảo giao dịch thu.
 * Chỉ RefundResultHandler được gọi succeed()/fail(); service không set status trực tiếp.
 */
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

    /** transactionRef của giao dịch thu gốc, để đối soát "hoàn cho khoản nào". */
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

    /** Id lệnh chi ở provider (PayOS: payout id). Có giá trị = provider ĐÃ nhận lệnh, không được gửi lại key mới. */
    @Column(name = "provider_refund_id", length = 100)
    private String providerRefundId;

    /**
     * Key gửi provider: RF-&lt;refundId&gt;-&lt;attempt&gt;, refundId là UUID nên không trùng lại sau khi reset DB.
     * Giữ nguyên khi retry sau timeout; xóa khi provider từ chối dứt khoát để lần sau sinh key mới.
     */
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

    // ponytail: chưa mã hóa, giống payments.payer_account_number; API và log đã mask. Nâng cấp chung một AttributeConverter.
    @Column(name = "destination_account")
    private String destinationAccount;

    @Column(name = "destination_is_payer")
    private Boolean destinationIsPayer;

    private String reason;

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

    // EAGER: RefundResponse.from() luôn đọc items, và create() dựng response ngoài transaction
    // (self-invocation nên @Transactional trên get() bị bypass). Danh sách vé của một refund luôn nhỏ.
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "refund_id", nullable = false)
    private List<RefundItem> items = new ArrayList<>();

    public static Refund request(UUID orderId, Payment payment, List<RefundItem> items, long amount,
                                 RefundInitiator initiator, String reason,
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
        r.destinationBin = destinationBin;
        r.destinationAccount = destinationAccount;
        r.destinationIsPayer = destinationIsPayer;
        r.items.addAll(items);
        r.createdAt = Instant.now();
        return r;
    }

    /** Đích không phải tài khoản đã trả: không tự chi, chờ admin duyệt. */
    public void holdForDestinationReview() {
        require(RefundStatus.REQUESTED, "MANUAL_REVIEW");
        status = RefundStatus.MANUAL_REVIEW;
        failureCode = "DESTINATION_REVIEW";
        failureReason = "Tài khoản nhận khác tài khoản đã thanh toán, cần admin duyệt";
    }

    /**
     * Gọi trong TX ngay TRƯỚC khi gọi provider, trả key cho lần thử này. Có key sẵn (lần trước timeout) thì dùng lại
     * — đây là thứ chặn chi tiền hai lần; chưa có thì tăng attempt và sinh key mới.
     */
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

    /** Provider đã nhận lệnh. */
    public void submitted(String providerRefundId) {
        require(RefundStatus.REQUESTED, "PROCESSING");
        this.providerRefundId = providerRefundId;
        status = RefundStatus.PROCESSING;
    }

    /** Ví thiếu (pre-check hoặc provider từ chối). Lần sau dùng key mới vì lần này provider đã từ chối dứt khoát. */
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

    public void manualReview(String code, String reason) {
        requireNotTerminal("MANUAL_REVIEW");
        status = RefundStatus.MANUAL_REVIEW;
        failureCode = code;
        failureReason = reason;
    }

    /**
     * Admin bấm RETRY. Chỉ khi provider CHƯA từng nhận lệnh, nếu không sẽ chi hai lần.
     * Có destination mới thì đổi đích (admin đã duyệt).
     */
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

    /** Recovery job tìm thấy lệnh ở provider sau timeout. */
    public void attachProvider(String providerRefundId) {
        if (this.providerRefundId == null) this.providerRefundId = providerRefundId;
    }

    public void polled() {
        lastPolledAt = Instant.now();
    }

    public boolean isTerminal() {
        return status == RefundStatus.SUCCEEDED || status == RefundStatus.FAILED;
    }

    /** Kết quả từ provider chỉ nhận khi đang PROCESSING, hoặc REQUESTED (timeout mà provider đã nhận lệnh). */
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
