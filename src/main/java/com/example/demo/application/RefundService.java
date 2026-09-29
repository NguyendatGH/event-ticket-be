package com.example.demo.application;

import com.example.demo.application.dto.CreateRefundRequest;
import com.example.demo.application.dto.RefundResponse;
import com.example.demo.application.dto.ResolveRefundRequest;
import com.example.demo.domain.common.DomainException;
import com.example.demo.domain.common.LogContext;
import com.example.demo.domain.event.Event;
import com.example.demo.domain.idempotency.IdempotencyScope;
import com.example.demo.domain.order.Order;
import com.example.demo.domain.order.Ticket;
import com.example.demo.domain.payment.BankBins;
import com.example.demo.domain.payment.GatewayRejectedException;
import com.example.demo.domain.payment.InvalidWebhookSignatureException;
import com.example.demo.domain.payment.Payment;
import com.example.demo.domain.payment.PaymentGatewayPort;
import com.example.demo.domain.payment.PaymentProvider;
import com.example.demo.domain.payment.RefundCommand;
import com.example.demo.domain.payment.RefundEvent;
import com.example.demo.domain.payment.RefundStatusResult;
import com.example.demo.domain.payment.RefundSubmitResult;
import com.example.demo.domain.payment.WebhookEvent;
import com.example.demo.domain.payment.WebhookProcessingResult;
import com.example.demo.domain.refund.Refund;
import com.example.demo.domain.refund.RefundInitiator;
import com.example.demo.domain.refund.RefundItem;
import com.example.demo.domain.refund.RefundPolicy;
import com.example.demo.domain.refund.RefundStatus;
import com.example.demo.infrastructure.persistence.EventRepository;
import com.example.demo.infrastructure.persistence.OrderRepository;
import com.example.demo.infrastructure.persistence.PaymentRepository;
import com.example.demo.infrastructure.persistence.RefundRepository;
import com.example.demo.infrastructure.persistence.TicketRepository;
import com.example.demo.infrastructure.persistence.WebhookEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.example.demo.domain.payment.WebhookProcessingResult.DUPLICATE;
import static com.example.demo.domain.payment.WebhookProcessingResult.IGNORED;

/**
 * Mọi nguồn tạo refund qua {@link #create}, mọi lần gửi provider qua {@link #submit}, mọi kết quả qua {@link RefundResultHandler}.
 * Dùng TransactionTemplate như CheckoutService vì các bước gọi nhau trong cùng bean và CÓ GỌI PROVIDER Ở GIỮA.
 */
@Service
public class RefundService {

    private static final Logger log = LoggerFactory.getLogger(RefundService.class);
    /** Sau bao lâu thì coi một lệnh REQUESTED là "kẹt" và đi tra provider. */
    private static final Duration STUCK_AFTER = Duration.ofMinutes(1);

    private final OrderRepository orders;
    private final TicketRepository tickets;
    private final PaymentRepository payments;
    private final EventRepository events;
    private final RefundRepository refunds;
    private final WebhookEventRepository webhookEvents;
    private final IdempotencyService idempotency;
    private final PaymentGatewayPort gateway;
    private final GatewayAudit audit;
    private final RefundResultHandler results;
    private final WalletService wallet;
    private final TransactionTemplate tx;
    private final RefundPolicy policy;
    private final boolean payoutEnabled;
    private final Duration processingTimeout;
    private final Duration awaitingFundsTimeout;
    private final String merchantBin;
    private final String merchantAccount;

    public RefundService(OrderRepository orders, TicketRepository tickets, PaymentRepository payments,
                         EventRepository events, RefundRepository refunds, WebhookEventRepository webhookEvents,
                         IdempotencyService idempotency, PaymentGatewayPort gateway, GatewayAudit audit,
                         RefundResultHandler results, WalletService wallet, TransactionTemplate tx,
                         @Value("${app.refund.fee-percent:0}") int feePercent,
                         @Value("${app.refund.payout-enabled:true}") boolean payoutEnabled,
                         @Value("${app.refund.processing-timeout:30m}") Duration processingTimeout,
                         @Value("${app.refund.awaiting-funds-timeout:24h}") Duration awaitingFundsTimeout,
                         @Value("${app.refund.merchant-bin:}") String merchantBin,
                         @Value("${app.refund.merchant-account:}") String merchantAccount) {
        this.orders = orders;
        this.tickets = tickets;
        this.payments = payments;
        this.events = events;
        this.refunds = refunds;
        this.webhookEvents = webhookEvents;
        this.idempotency = idempotency;
        this.gateway = gateway;
        this.audit = audit;
        this.results = results;
        this.wallet = wallet;
        this.tx = tx;
        this.policy = new RefundPolicy(feePercent);
        this.payoutEnabled = payoutEnabled;
        this.processingTimeout = processingTimeout;
        this.awaitingFundsTimeout = awaitingFundsTimeout;
        this.merchantBin = merchantBin;
        this.merchantAccount = merchantAccount;
    }

    /**
     * POST /orders/{orderId}/refunds. Cùng Idempotency-Key + cùng body -> trả lại đúng refund cũ, không gọi provider lần hai.
     * {@code actorId} = user đang gọi; phải là chủ đơn, nếu không 404.
     */
    public RefundResponse create(UUID actorId, UUID orderId, String idempotencyKey, CreateRefundRequest req,
                                 RefundInitiator initiator) {
        return idempotency.execute(IdempotencyScope.REFUND, idempotencyKey,
                Map.of("orderId", orderId, "actorId", actorId, "body", req), RefundResponse.class, () -> {
                    try (LogContext.Scope ignored = LogContext.of(gateway.provider().name())) {
                        UUID refundId = tx.execute(s -> open(actorId, orderId, req, initiator));   // TX1
                        LogContext.refundId(refundId);
                        submit(refundId);                                              // gọi provider NGOÀI transaction
                        return get(refundId);
                    }
                });
    }


    private UUID open(UUID actorId, UUID orderId, CreateRefundRequest req, RefundInitiator initiator) {
        Order order = orders.findWithLockById(orderId)
                .orElseThrow(() -> DomainException.notFound("ORDER_NOT_FOUND", "Không tìm thấy đơn hàng"));
        order.requireOwner(actorId);   // kiểm ngay sau khi khóa: hoàn tiền là tiền RA, không dựa vào orderId khó đoán
        LogContext.orderCode(order.getOrderCode());
        Event event = events.findById(order.getEventId())
                .orElseThrow(() -> DomainException.notFound("EVENT_NOT_FOUND", "Không tìm thấy sự kiện của đơn"));
        Payment payment = payments.findFirstByOrderIdOrderByCreatedAtDesc(orderId).filter(Payment::isPaid)
                .orElseThrow(() -> DomainException.conflict("PAYMENT_NOT_FOUND", "Đơn chưa có giao dịch thanh toán thành công"));

        List<UUID> ids = req.ticketIds().stream().distinct().toList();
        List<Ticket> selected = tickets.findAllByOrderIdAndIdIn(orderId, ids);
        if (selected.size() != ids.size()) {
            throw DomainException.badRequest("TICKET_NOT_IN_ORDER", "Có vé không thuộc đơn này");
        }
        Map<UUID, Long> amounts = policy.check(order, event, selected, Instant.now());

        boolean toPayer = req.destination() == null;
        String bin = toPayer ? payment.getPayerBankBin() : req.destination().bin();
        String account = toPayer ? payment.getPayerAccountNumber() : req.destination().accountNumber();
        if (toPayer && (account == null || account.isBlank() || !BankBins.isBin(bin))) {
            // Ví điện tử và vài ngân hàng không gửi counterAccount, hoặc gửi mã CITAD không dùng chi được:
            // khách phải tự CHỌN NGÂN HÀNG + nhập số tài khoản, và refund đó sẽ đi MANUAL_REVIEW.
            throw DomainException.conflict("PAYER_ACCOUNT_UNKNOWN",
                    "Không xác định được ngân hàng/số tài khoản người trả; gửi kèm destination (bin + accountNumber) để admin duyệt");
        }
        if (!toPayer && !BankBins.isBin(bin)) {
            throw DomainException.badRequest("INVALID_BANK_BIN",
                    "bin phải là mã BIN Napas 6 số; lấy danh sách ở GET /api/v1/config");
        }
        if (isMerchantAccount(bin, account)) {
            // Tiền chạy vòng về chính tài khoản thu: chặn ngay, đừng để thành lệnh chi
            throw DomainException.badRequest("DESTINATION_IS_MERCHANT", "Tài khoản nhận không được là tài khoản thu của hệ thống");
        }

        selected.forEach(Ticket::markRefundPending);
        order.startRefund();
        long total = amounts.values().stream().mapToLong(Long::longValue).sum();
        List<RefundItem> items = selected.stream().map(t -> new RefundItem(t.getId(), amounts.get(t.getId()))).toList();
        Refund refund = Refund.request(orderId, payment, items, total, initiator, req.reason(), bin, account, toPayer);
        if (!toPayer) refund.holdForDestinationReview();
        UUID id = refunds.save(refund).getId();
        log.info("Refund {} tạo cho order {}: {} vé, {} VND, {}", id, order.getOrderCode(), items.size(), total, refund.getStatus());
        return id;
    }

    private boolean isMerchantAccount(String bin, String account) {
        if (merchantBin == null || merchantBin.isBlank() || merchantAccount == null || merchantAccount.isBlank()) return false;
        return merchantBin.trim().equals(bin) && merchantAccount.trim().equals(account);
    }

    /**
     * Gửi lệnh chi cho refund REQUESTED/AWAITING_FUNDS. Job cũng gọi. Gọi lặp an toàn:
     * TX_a chốt key -> gọi provider NGOÀI TX -> TX_b ghi kết quả. Chết giữa chừng thì {@link #recoverStuck()} tra lại theo key.
     */
    public void submit(UUID refundId) {
        Refund r = refunds.findById(refundId).orElseThrow();
        if (r.getStatus() != RefundStatus.REQUESTED && r.getStatus() != RefundStatus.AWAITING_FUNDS) return;
        if (!payoutEnabled) {
            log.warn("app.refund.payout-enabled=false: refund {} giữ {}", refundId, r.getStatus());
            return;
        }

        long available = wallet.available();                                 // gọi provider, ngoài TX
        if (r.getAmount() > available) {
            tx.executeWithoutResult(s -> refunds.findWithLockById(refundId)
                    .ifPresent(x -> x.awaitFunds("INSUFFICIENT_PAYOUT_BALANCE")));
            log.warn("Refund {} cần {} > ví còn {}: AWAITING_FUNDS", refundId, r.getAmount(), available);
            return;
        }

        String key;
        try {
            key = tx.execute(s -> refunds.findWithLockById(refundId).orElseThrow().beginAttempt());   // TX_a
        } catch (IllegalStateException raced) {
            return;                                                          // thread khác vừa đổi trạng thái
        }
        long orderCode = orders.findById(r.getOrderId()).map(Order::getOrderCode).orElse(0L);
        RefundCommand cmd = new RefundCommand(key, r.getAmount(), "Hoan ve " + orderCode,
                r.getDestinationBin(), r.getDestinationAccount());

        long t0 = System.currentTimeMillis();
        RefundSubmitResult res;
        try {
            res = gateway.submitRefund(cmd);                                 // NGOÀI transaction
        } catch (GatewayRejectedException ex) {
            audit.record(r.getOrderId(), "OUTBOUND", "submitRefund", cmd.masked(),
                    Map.of("error", ex.getCode(), "message", String.valueOf(ex.getMessage())), 400, System.currentTimeMillis() - t0);
            onRejected(refundId, ex);
            return;
        } catch (RuntimeException ex) {
            // Timeout/5xx/mất mạng: KHÔNG biết provider đã nhận chưa. Tra ngay theo key thay vì chờ job cả phút,
            // vì SDK tự retry với cùng idempotency key nên lỗi này có thể đến SAU khi provider đã nhận lệnh.
            audit.record(r.getOrderId(), "OUTBOUND", "submitRefund", cmd.masked(),
                    Map.of("error", String.valueOf(ex.getMessage())), null, System.currentTimeMillis() - t0);
            log.warn("Refund {} gửi provider không rõ kết quả ({}), tra lại theo key {}", refundId, ex.toString(), key);
            if (!adoptByReference(refundId, key)) {
                log.warn("Refund {} chưa thấy ở provider, giữ REQUESTED + key cho RefundRecoveryJob", refundId);
            }
            return;
        }
        audit.record(r.getOrderId(), "OUTBOUND", "submitRefund", cmd.masked(), res, 200, System.currentTimeMillis() - t0);

        tx.executeWithoutResult(s -> refunds.findWithLockById(refundId)                              // TX_b
                .filter(x -> x.getStatus() == RefundStatus.REQUESTED)
                .ifPresent(x -> x.submitted(res.providerRefundId())));
        if (res.status().isFinal() || res.status() == RefundStatusResult.Status.ON_HOLD) {
            results.apply(refundId, new RefundStatusResult(res.status(), res.providerRefundId(), null, null, Instant.now()), false);
        }
    }

    /**
     * Tra provider theo key: thấy lệnh thì nhận lấy nó (không tạo lệnh thứ hai), rồi chốt luôn nếu đã xong.
     * @return true nếu provider đã có lệnh dưới key này.
     */
    private boolean adoptByReference(UUID refundId, String key) {
        Optional<RefundStatusResult> found;
        try {
            found = gateway.findRefundByReference(key);
        } catch (RuntimeException ex) {
            log.warn("Tra lệnh chi theo key {} thất bại: {}", key, ex.toString());
            return false;
        }
        if (found.isEmpty()) return false;
        RefundStatusResult st = found.get();
        log.info("Refund {} đã có ở provider dưới key {}: {} {}", refundId, key, st.providerRefundId(), st.status());
        tx.executeWithoutResult(s -> refunds.findWithLockById(refundId)
                .filter(x -> x.getStatus() == RefundStatus.REQUESTED)
                .ifPresent(x -> x.submitted(st.providerRefundId())));
        if (st.isFinal() || st.needsHuman()) results.apply(refundId, st, false);
        return true;
    }

    /** 4xx: tiền CHƯA đi. Phân loại theo code chuẩn hóa của adapter. */
    private void onRejected(UUID refundId, GatewayRejectedException ex) {
        switch (ex.getCode()) {
            case "INSUFFICIENT_PAYOUT_BALANCE", "NO_PAYOUT_CREDIT" -> tx.executeWithoutResult(
                    s -> refunds.findWithLockById(refundId).ifPresent(x -> x.awaitFunds(ex.getCode())));
            case "INVALID_DESTINATION" -> results.apply(refundId,
                    new RefundStatusResult(RefundStatusResult.Status.FAILED, null, ex.getCode(), ex.getMessage(), Instant.now()), false);
            // LIMIT_EXCEEDED, PAYOUT_NOT_CONFIGURED, mã lạ: để người xem, đừng tự đoán
            default -> tx.executeWithoutResult(
                    s -> refunds.findWithLockById(refundId).ifPresent(x -> x.manualReview(ex.getCode(), ex.getMessage())));
        }
    }

    @Transactional(readOnly = true)
    public RefundResponse get(UUID refundId) {
        return RefundResponse.from(refunds.findById(refundId)
                .orElseThrow(() -> DomainException.notFound("REFUND_NOT_FOUND", "Không tìm thấy yêu cầu hoàn tiền")));
    }

    /** Cho endpoint của người mua: không phải chủ đơn -> 404. */
    @Transactional(readOnly = true)
    public RefundResponse getOwned(UUID actorId, UUID refundId) {
        Refund refund = refunds.findById(refundId)
                .orElseThrow(() -> DomainException.notFound("REFUND_NOT_FOUND", "Không tìm thấy yêu cầu hoàn tiền"));
        orders.findById(refund.getOrderId())
                .orElseThrow(() -> DomainException.notFound("ORDER_NOT_FOUND", "Không tìm thấy đơn hàng"))
                .requireOwner(actorId);
        return RefundResponse.from(refund);
    }

    /** Cho endpoint của người mua: không phải chủ đơn -> 404. */
    @Transactional(readOnly = true)
    public List<RefundResponse> byOrderOwned(UUID actorId, UUID orderId) {
        orders.findById(orderId)
                .orElseThrow(() -> DomainException.notFound("ORDER_NOT_FOUND", "Không tìm thấy đơn hàng"))
                .requireOwner(actorId);
        return byOrder(orderId);
    }

    /** Không kiểm chủ đơn: dùng nội bộ (admin, job, giả lập provider trong test). */
    @Transactional(readOnly = true)
    public List<RefundResponse> byOrder(UUID orderId) {
        return refunds.findAllByOrderIdOrderByCreatedAt(orderId).stream().map(RefundResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public List<RefundResponse> list(RefundStatus status) {
        List<RefundStatus> statuses = status == null ? List.of(RefundStatus.values()) : List.of(status);
        return refunds.findAllByStatusInOrderByCreatedAtDesc(statuses).stream().map(RefundResponse::from).toList();
    }

    /**
     * POST /webhooks/{provider}/refund. Sai chữ ký -> 401 (vẫn ghi sổ). Trùng -> DUPLICATE nhờ unique (provider, event_id).
     * PayOS không có webhook lệnh chi nên đường này chỉ test double dùng; với PayOS thì RefundPollJob là đường chính.
     */
    public WebhookProcessingResult handleRefundWebhook(PaymentProvider provider, String rawBody, Map<String, String> headers) {
        if (gateway.provider() != provider) {
            throw DomainException.notFound("PROVIDER_INACTIVE", "Provider " + provider + " không hoạt động ở profile này");
        }
        try (LogContext.Scope ignored = LogContext.of(provider.name())) {
            return applyRefundWebhook(provider, rawBody, headers);
        }
    }

    private WebhookProcessingResult applyRefundWebhook(PaymentProvider provider, String rawBody, Map<String, String> headers) {
        RefundEvent e;
        try {
            e = gateway.verifyAndParseRefund(rawBody, headers);
        } catch (InvalidWebhookSignatureException ex) {
            tx.executeWithoutResult(s -> webhookEvents.save(WebhookEvent.rejected(provider, audit.jsonOrWrapped(rawBody))));
            throw ex;
        }
        WebhookEvent saved;
        try {
            saved = tx.execute(s -> webhookEvents.saveAndFlush(
                    WebhookEvent.received(provider, e.eventId(), "refund", e.rawPayload())));
        } catch (DataIntegrityViolationException duplicate) {
            log.info("Webhook refund trùng {} {}: bỏ qua", provider, e.eventId());
            return DUPLICATE;
        }
        Refund refund = refunds.findByProviderAndProviderRefundId(provider, e.providerRefundId())
                .or(() -> refunds.findByIdempotencyKey(e.referenceId())).orElse(null);
        WebhookProcessingResult result;
        if (refund == null) {
            log.warn("Webhook refund cho {} / {} không khớp refund nào: bỏ qua", e.providerRefundId(), e.referenceId());
            result = IGNORED;
        } else {
            LogContext.refundId(refund.getId());
            result = results.apply(refund.getId(),
                    new RefundStatusResult(e.status(), e.providerRefundId(), e.failureCode(), e.failureReason(), Instant.now()), false);
            audit.record(refund.getOrderId(), "INBOUND", "webhook:refund", null, e.rawPayload(), 200, 0);
        }
        UUID webhookId = saved.getId();
        WebhookProcessingResult finalResult = result;
        tx.executeWithoutResult(s -> webhookEvents.findById(webhookId).ifPresent(w -> w.finish(finalResult)));
        return result;
    }

    /**
     * RefundPollJob: hỏi provider các refund PROCESSING. Quá {@code processingTimeout} mà vẫn đang xử lý thì
     * MANUAL_REVIEW chứ KHÔNG phải FAILED — FAILED mời admin bấm RETRY trong khi lệnh cũ còn có thể thành công, tức chi hai lần.
     */
    public void pollProcessing() {
        for (Refund r : refunds.findAllByStatusOrderByCreatedAt(RefundStatus.PROCESSING)) {
            if (r.getProvider() != gateway.provider() || r.getProviderRefundId() == null) continue;
            try (LogContext.Scope ignored = LogContext.refund(r.getProvider().name(), r.getId())) {
                poll(r);
            }
        }
    }

    private void poll(Refund r) {
        long t0 = System.currentTimeMillis();
        RefundStatusResult status;
        try {
            status = gateway.getRefundStatus(r.getProviderRefundId());
        } catch (RuntimeException ex) {
            log.warn("Hỏi trạng thái lệnh chi {} thất bại: {}", r.getProviderRefundId(), ex.toString());
            audit.record(r.getOrderId(), "OUTBOUND", "getRefundStatus", Map.of("providerRefundId", r.getProviderRefundId()),
                    Map.of("error", String.valueOf(ex.getMessage())), null, System.currentTimeMillis() - t0);
            return;
        }
        audit.record(r.getOrderId(), "OUTBOUND", "getRefundStatus", Map.of("providerRefundId", r.getProviderRefundId()),
                status, 200, System.currentTimeMillis() - t0);
        if (status.inFlight()) {
            boolean tooLong = r.getSubmittedAt() != null && r.getSubmittedAt().plus(processingTimeout).isBefore(Instant.now());
            tx.executeWithoutResult(s -> refunds.findWithLockById(r.getId()).ifPresent(x -> {
                x.polled();
                if (tooLong && x.getStatus() == RefundStatus.PROCESSING) {
                    x.manualReview("PROCESSING_TIMEOUT", "Provider chưa chốt sau " + processingTimeout
                            + "; lệnh có thể vẫn đang bay, KHÔNG gửi lại, tra dashboard trước");
                }
            }));
            return;
        }
        results.apply(r.getId(), status, false);
    }

    /** RefundQueueJob: ví đủ thì gửi AWAITING_FUNDS theo thứ tự vào hàng; dừng ở refund đầu tiên không đủ (FIFO, không chen). */
    public void drainQueue() {
        List<Refund> queue = refunds.findAllByStatusOrderByQueuedSince(RefundStatus.AWAITING_FUNDS);
        if (queue.isEmpty()) return;
        long available = wallet.available();
        for (Refund r : queue) {
            if (r.getQueuedSince() != null && r.getQueuedSince().plus(awaitingFundsTimeout).isBefore(Instant.now())) {
                tx.executeWithoutResult(s -> refunds.findWithLockById(r.getId())
                        .ifPresent(x -> x.manualReview("AWAITING_FUNDS_TIMEOUT", "Ví không đủ sau " + awaitingFundsTimeout)));
                continue;
            }
            if (r.getAmount() > available) break;
            try (LogContext.Scope ignored = LogContext.refund(r.getProvider().name(), r.getId())) {
                submit(r.getId());
            }
            available -= r.getAmount();
        }
    }

    /**
     * RefundRecoveryJob: REQUESTED kẹt. Có key và quá 1 phút không kết quả thì tra provider theo key
     * (có → PROCESSING, không → gửi lại CÙNG key); chưa từng gửi thì gửi lần đầu.
     */
    public void recoverStuck() {
        Instant cutoff = Instant.now().minus(STUCK_AFTER);
        for (Refund r : refunds.findAllByStatusAndSubmittedAtBefore(RefundStatus.REQUESTED, cutoff)) {
            if (r.getProvider() != gateway.provider() || r.getIdempotencyKey() == null) continue;
            try (LogContext.Scope ignored = LogContext.refund(r.getProvider().name(), r.getId())) {
                if (!adoptByReference(r.getId(), r.getIdempotencyKey())) {
                    log.info("Refund {} không có ở provider, gửi lại cùng key {}", r.getId(), r.getIdempotencyKey());
                    submit(r.getId());                                       // beginAttempt() giữ nguyên key
                }
            }
        }
        refunds.findAllByStatusAndSubmittedAtIsNullAndCreatedAtBefore(RefundStatus.REQUESTED, cutoff)
                .forEach(r -> {
                    try (LogContext.Scope ignored = LogContext.refund(r.getProvider().name(), r.getId())) {
                        submit(r.getId());
                    }
                });
    }

    /** Chốt refund MANUAL_REVIEW. SUCCEEDED/FAILED đi qua cùng RefundResultHandler như provider; RETRY về REQUESTED rồi gửi lại. */
    public RefundResponse resolve(UUID refundId, ResolveRefundRequest req) {
        Refund r = refunds.findById(refundId)
                .orElseThrow(() -> DomainException.notFound("REFUND_NOT_FOUND", "Không tìm thấy yêu cầu hoàn tiền"));
        if (r.getStatus() != RefundStatus.MANUAL_REVIEW) {
            throw DomainException.conflict("REFUND_NOT_IN_REVIEW",
                    "Chỉ chốt được refund đang MANUAL_REVIEW (hiện tại: " + r.getStatus() + ")");
        }
        try (LogContext.Scope ignored = LogContext.refund(r.getProvider().name(), refundId)) {
            log.info("Admin resolve refund {}: {} ({})", refundId, req.outcome(), req.note());
            return applyResolve(r, refundId, req);
        }
    }

    private RefundResponse applyResolve(Refund r, UUID refundId, ResolveRefundRequest req) {
        switch (req.outcome()) {
            case SUCCEEDED -> results.apply(refundId, new RefundStatusResult(RefundStatusResult.Status.SUCCEEDED,
                    r.getProviderRefundId(), null, "admin: " + req.note(), Instant.now()), true);
            case FAILED -> results.apply(refundId, new RefundStatusResult(RefundStatusResult.Status.FAILED,
                    r.getProviderRefundId(), "ADMIN_REJECTED", req.note(), Instant.now()), true);
            case RETRY -> {
                tx.executeWithoutResult(s -> refunds.findWithLockById(refundId).orElseThrow()
                        .retry(req.destination() == null ? null : req.destination().bin(),
                               req.destination() == null ? null : req.destination().accountNumber()));
                submit(refundId);
            }
        }
        return get(refundId);
    }
}
