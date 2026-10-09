package com.example.demo.application;

import com.example.demo.application.dto.CreateRefundRequest;
import com.example.demo.application.dto.RefundResponse;
import com.example.demo.application.dto.ResolveRefundRequest;
import com.example.demo.application.dto.RefundInstruction;
import com.example.demo.domain.payment.VietQr;
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
import com.example.demo.application.PaymentGatewayRegistry;
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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.example.demo.domain.payment.WebhookProcessingResult.DUPLICATE;
import static com.example.demo.domain.payment.WebhookProcessingResult.IGNORED;

@Service
public class RefundService {

    private static final Logger log = LoggerFactory.getLogger(RefundService.class);
    private static final Duration STUCK_AFTER = Duration.ofMinutes(1);

    private final OrderRepository orders;
    private final TicketRepository tickets;
    private final PaymentRepository payments;
    private final EventRepository events;
    private final RefundRepository refunds;
    private final WebhookEventRepository webhookEvents;
    private final IdempotencyService idempotency;
    private final PaymentGatewayRegistry gateways;
    private final GatewayAudit audit;
    private final RefundResultHandler results;
    private final WalletService wallet;
    private final OrganizerAccess access;
    private final RefundNotifier notifier;
    private final TransactionTemplate tx;
    private final RefundPolicy policy;
    private final boolean payoutEnabled;
    private final Duration processingTimeout;
    private final Duration awaitingFundsTimeout;
    private final String merchantBin;
    private final String merchantAccount;

    public RefundService(OrderRepository orders, TicketRepository tickets, PaymentRepository payments,
                         EventRepository events, RefundRepository refunds, WebhookEventRepository webhookEvents,
                         IdempotencyService idempotency, PaymentGatewayRegistry gateways, GatewayAudit audit,
                         RefundResultHandler results, WalletService wallet, OrganizerAccess access,
                         RefundNotifier notifier, TransactionTemplate tx,
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
        this.gateways = gateways;
        this.audit = audit;
        this.results = results;
        this.wallet = wallet;
        this.access = access;
        this.notifier = notifier;
        this.tx = tx;
        this.policy = new RefundPolicy(feePercent);
        this.payoutEnabled = payoutEnabled;
        this.processingTimeout = processingTimeout;
        this.awaitingFundsTimeout = awaitingFundsTimeout;
        this.merchantBin = merchantBin;
        this.merchantAccount = merchantAccount;
    }

    public RefundResponse create(UUID actorId, UUID orderId, String idempotencyKey, CreateRefundRequest req,
                                 RefundInitiator initiator) {
        return idempotency.execute(IdempotencyScope.REFUND, idempotencyKey,
                Map.of("orderId", orderId, "actorId", actorId, "body", req), RefundResponse.class, () -> {
                    try (LogContext.Scope ignored = LogContext.of("refund")) {
                        UUID refundId = tx.execute(s -> open(actorId, orderId, req, initiator));
                        try (LogContext.Scope refundLog = logScope(refunds.findById(refundId).orElseThrow())) {
                            submit(refundId);
                            mailIfWaitingForDestinationReview(refundId);
                            return get(refundId);
                        }
                    }
                });
    }


    private void mailIfWaitingForDestinationReview(UUID refundId) {
        boolean waiting = refunds.findById(refundId)
                .map(x -> x.getStatus() == RefundStatus.MANUAL_REVIEW && "DESTINATION_REVIEW".equals(x.getFailureCode()))
                .orElse(false);
        if (waiting) sendMailQuietly("NEEDS_REVIEW", refundId, () -> notifier.needsReview(refundId));
    }

    private UUID open(UUID actorId, UUID orderId, CreateRefundRequest req, RefundInitiator initiator) {
        Order order = orders.findWithLockById(orderId)
                .orElseThrow(() -> DomainException.notFound("ORDER_NOT_FOUND", "Không tìm thấy đơn hàng"));
        order.requireOwner(actorId);
        LogContext.orderCode(order.getOrderCode());
        Event event = events.findById(order.getEventId())
                .orElseThrow(() -> DomainException.notFound("EVENT_NOT_FOUND", "Không tìm thấy sự kiện của đơn"));
        Payment payment = payments.findFirstByOrderIdOrderByCreatedAtDesc(orderId).filter(Payment::isPaid)
                .orElseThrow(() -> DomainException.conflict("PAYMENT_NOT_FOUND", "Đơn chưa có giao dịch thanh toán thành công"));
        LogContext.trade(payment.getGatewayMerchantNo(), null, null);

        List<UUID> ids = req.ticketIds().stream().distinct().toList();
        List<Ticket> selected = tickets.findAllByOrderIdAndIdIn(orderId, ids);
        if (selected.size() != ids.size()) {
            throw DomainException.badRequest("TICKET_NOT_IN_ORDER", "Có vé không thuộc đơn này");
        }
        Map<UUID, Long> amounts = policy.check(order, event, selected, Instant.now());

        String bin = req.destination() == null ? payment.getPayerBankBin() : req.destination().bin();
        String account = req.destination() == null ? payment.getPayerAccountNumber() : req.destination().accountNumber();

        boolean toPayer = account != null && !account.isBlank() && account.equals(payment.getPayerAccountNumber());

        if (payment.isWallet()) {
            bin = null;
            account = null;
            toPayer = true;
        } else if (req.destination() == null && !BankBins.isBin(bin)) {
            throw DomainException.conflict("PAYER_ACCOUNT_UNKNOWN",
                    "Chưa xác định được ngân hàng của tài khoản đã thanh toán; chọn ngân hàng rồi gửi kèm destination (bin + accountNumber)");
        }
        if (!payment.isWallet() && req.destination() != null && !BankBins.isBin(bin)) {
            throw DomainException.badRequest("INVALID_BANK_BIN",
                    "bin phải là mã BIN Napas 6 số; lấy danh sách ở GET /api/v1/config");
        }
        if (isMerchantAccount(bin, account)) {
            throw DomainException.badRequest("DESTINATION_IS_MERCHANT", "Tài khoản nhận không được là tài khoản thu của hệ thống");
        }

        selected.forEach(Ticket::markRefundPending);
        order.startRefund();
        long total = amounts.values().stream().mapToLong(Long::longValue).sum();
        List<RefundItem> items = selected.stream().map(t -> new RefundItem(t.getId(), amounts.get(t.getId()))).toList();
        String contactEmail = req.contactEmail() == null ? null : AuthService.normalizeEmail(req.contactEmail());
        Refund refund = Refund.request(orderId, payment, items, total, initiator, req.reason(), contactEmail,
                bin, account, toPayer);
        if (!toPayer) refund.holdForDestinationReview();
        UUID id = refunds.save(refund).getId();
        log.info("Refund {} tạo cho order {}: {} vé, {} VND, {}", id, order.getOrderCode(), items.size(), total, refund.getStatus());
        return id;
    }

    private LogContext.Scope logScope(Refund r) {
        LogContext.Scope scope = LogContext.refund(r.getProvider().name(), r.getId(), r.getProviderRefundId());
        tagLog(r);
        return scope;
    }

    private void tagLog(Refund r) {
        payments.findById(r.getPaymentId())
                .ifPresent(p -> LogContext.trade(p.getGatewayMerchantNo(), null, null));
        orders.findById(r.getOrderId()).ifPresent(o -> LogContext.orderCode(o.getOrderCode()));
    }

    private boolean isMerchantAccount(String bin, String account) {
        if (merchantBin == null || merchantBin.isBlank() || merchantAccount == null || merchantAccount.isBlank()) return false;
        return merchantBin.trim().equals(bin) && merchantAccount.trim().equals(account);
    }

    public void submit(UUID refundId) {
        Refund r = refunds.findById(refundId).orElseThrow();
        if (r.getStatus() != RefundStatus.REQUESTED && r.getStatus() != RefundStatus.AWAITING_FUNDS) return;
        if (r.getProvider() == PaymentProvider.WALLET) {
            results.apply(refundId, new RefundStatusResult(RefundStatusResult.Status.SUCCEEDED,
                    "wallet-refund-" + refundId, null, null, Instant.now()), false);
            return;
        }
        PaymentGatewayPort gateway = gateways.forProvider(r.getProvider());
        if (!payoutEnabled) {
            toManualTransfer(refundId, "PAYOUT_DISABLED", "app.refund.payout-enabled=false, chuyển tay");
            return;
        }

        long available;
        try {
            available = wallet.availableFor(r);
        } catch (RuntimeException ex) {
            toManualTransfer(refundId, "PAYOUT_UNAVAILABLE", ex.toString());
            return;
        }
        if (r.getAmount() > available) {
            boolean justQueued = Boolean.TRUE.equals(tx.execute(s -> refunds.findWithLockById(refundId)
                    .map(x -> {
                        boolean firstTime = x.getStatus() == RefundStatus.REQUESTED;
                        x.awaitFunds("INSUFFICIENT_PAYOUT_BALANCE");
                        return firstTime;
                    })
                    .orElse(false)));
            log.warn("Refund {} cần {} > ví còn {}: AWAITING_FUNDS", refundId, r.getAmount(), available);
            if (justQueued) sendMailQuietly("AWAITING_FUNDS", refundId, () -> notifier.awaitingFunds(refundId, available));
            return;
        }

        String key;
        try {
            key = tx.execute(s -> refunds.findWithLockById(refundId).orElseThrow().beginAttempt());
        } catch (IllegalStateException raced) {
            return;
        }
        long orderCode = orders.findById(r.getOrderId()).map(Order::getOrderCode).orElse(0L);
        RefundCommand cmd = new RefundCommand(key, r.getAmount(), "Hoan ve " + orderCode,
                r.getDestinationBin(), r.getDestinationAccount());

        long t0 = System.currentTimeMillis();
        RefundSubmitResult res;
        try {
            res = gateway.submitRefund(cmd);
        } catch (GatewayRejectedException ex) {
            audit.record(r.getOrderId(), "OUTBOUND", "submitRefund", cmd.masked(),
                    Map.of("error", ex.getCode(), "message", String.valueOf(ex.getMessage())), 400, System.currentTimeMillis() - t0);
            onRejected(refundId, ex);
            return;
        } catch (RuntimeException ex) {
            audit.record(r.getOrderId(), "OUTBOUND", "submitRefund", cmd.masked(),
                    Map.of("error", String.valueOf(ex.getMessage())), null, System.currentTimeMillis() - t0);
            log.warn("Refund {} gửi provider không rõ kết quả ({}), tra lại theo key {}", refundId, ex.toString(), key);
            if (!adoptByReference(refundId, key)) {
                log.warn("Refund {} chưa thấy ở provider, giữ REQUESTED + key cho RefundRecoveryJob", refundId);
            }
            return;
        }
        audit.record(r.getOrderId(), "OUTBOUND", "submitRefund", cmd.masked(), res, 200, System.currentTimeMillis() - t0);
        LogContext.trade(null, null, res.providerRefundId());

        tx.executeWithoutResult(s -> refunds.findWithLockById(refundId)
                .filter(x -> x.getStatus() == RefundStatus.REQUESTED)
                .ifPresent(x -> x.submitted(res.providerRefundId())));
        if (res.status().isFinal() || res.status() == RefundStatusResult.Status.ON_HOLD) {
            results.apply(refundId, new RefundStatusResult(res.status(), res.providerRefundId(), null, null, Instant.now()), false);
        }
    }

    @Transactional(readOnly = true)
    public List<RefundResponse> byOrganizer(UUID actorId, RefundStatus status) {
        UUID organizerId = access.currentOrganizer(actorId).getId();
        List<String> statuses = (status == null ? List.of(RefundStatus.values()) : List.of(status))
                .stream().map(Enum::name).toList();
        return refunds.findAllByOrganizer(organizerId, statuses).stream().map(RefundResponse::from).toList();
    }

    public RefundResponse resolveOwned(UUID actorId, UUID refundId, ResolveRefundRequest req) {
        UUID organizerId = access.currentOrganizer(actorId).getId();
        if (refunds.countByOrganizerAndId(organizerId, refundId) == 0) {
            throw DomainException.notFound("REFUND_NOT_FOUND", "Không tìm thấy yêu cầu hoàn tiền");
        }
        return resolve(refundId, req);
    }

    @Transactional(readOnly = true)
    public RefundInstruction instructionOwned(UUID actorId, UUID refundId) {
        UUID organizerId = access.currentOrganizer(actorId).getId();
        if (refunds.countByOrganizerAndId(organizerId, refundId) == 0) {
            throw DomainException.notFound("REFUND_NOT_FOUND", "Không tìm thấy yêu cầu hoàn tiền");
        }
        return instruction(refundId);
    }

    @Transactional(readOnly = true)
    public RefundInstruction instruction(UUID refundId) {
        Refund r = refunds.findById(refundId)
                .orElseThrow(() -> DomainException.notFound("REFUND_NOT_FOUND", "Không tìm thấy yêu cầu hoàn tiền"));
        String bin = r.getDestinationBin();
        String account = r.getDestinationAccount();
        if (!BankBins.isBin(bin) || account == null || account.isBlank()) {
            throw DomainException.conflict("REFUND_DESTINATION_UNKNOWN",
                    "Refund chưa có ngân hàng/số tài khoản hợp lệ để dựng QR");
        }
        long orderCode = orders.findById(r.getOrderId()).map(Order::getOrderCode).orElse(0L);
        String content = VietQr.sanitizeContent("HOAN VE " + orderCode);
        String qrImage = "https://img.vietqr.io/image/" + bin + "-" + account + "-compact2.png?amount=" + r.getAmount()
                + "&addInfo=" + URLEncoder.encode(content, StandardCharsets.UTF_8);
        return new RefundInstruction(qrImage, VietQr.build(bin, account, r.getAmount(), content),
                bin, BankBins.nameOf(bin), account, r.getAmount(), content);
    }

    private void toManualTransfer(UUID refundId, String code, String reason) {
        boolean movedToReview = Boolean.TRUE.equals(tx.execute(s -> refunds.findWithLockById(refundId)
                .filter(x -> x.getStatus() == RefundStatus.REQUESTED || x.getStatus() == RefundStatus.AWAITING_FUNDS)
                .map(x -> {
                    x.useManualTransfer();
                    x.manualReview(code, reason);
                    return true;
                })
                .orElse(false)));
        log.warn("Refund {} không chi tự động được ({}): chuyển MANUAL_REVIEW để BTC chuyển tay", refundId, code);
        if (movedToReview) sendMailQuietly("NEEDS_REVIEW", refundId, () -> notifier.needsReview(refundId));
    }

    private void sendMailQuietly(String kind, UUID refundId, Runnable send) {
        try {
            send.run();
        } catch (RuntimeException ex) {
            log.warn("Không gửi được mail {} cho refund {}: {}", kind, refundId, ex.toString());
        }
    }

    private boolean adoptByReference(UUID refundId, String key) {
        Refund refund = refunds.findById(refundId).orElseThrow();
        PaymentGatewayPort gateway = gateways.forProvider(refund.getProvider());
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

    private void onRejected(UUID refundId, GatewayRejectedException ex) {
        switch (ex.getCode()) {
            case "INSUFFICIENT_PAYOUT_BALANCE", "NO_PAYOUT_CREDIT" -> {
                boolean firstTime = Boolean.TRUE.equals(tx.execute(s -> refunds.findWithLockById(refundId)
                        .map(x -> {
                            boolean fresh = !isInsufficientFunds(x.getFailureCode());
                            x.awaitFunds(ex.getCode());
                            return fresh;
                        })
                        .orElse(false)));
                if (firstTime) sendMailQuietly("AWAITING_FUNDS", refundId, () -> notifier.awaitingFunds(refundId, -1));
            }
            case "INVALID_DESTINATION" -> results.apply(refundId,
                    new RefundStatusResult(RefundStatusResult.Status.FAILED, null, ex.getCode(), ex.getMessage(), Instant.now()), false);
            default -> {
                boolean movedToReview = Boolean.TRUE.equals(tx.execute(s -> refunds.findWithLockById(refundId)
                        .map(x -> {
                            boolean was = x.getStatus() == RefundStatus.MANUAL_REVIEW;
                            x.manualReview(ex.getCode(), ex.getMessage());
                            return !was;
                        })
                        .orElse(false)));
                if (movedToReview) sendMailQuietly("NEEDS_REVIEW", refundId, () -> notifier.needsReview(refundId));
            }
        }
    }

    private static boolean isInsufficientFunds(String failureCode) {
        return "INSUFFICIENT_PAYOUT_BALANCE".equals(failureCode) || "NO_PAYOUT_CREDIT".equals(failureCode);
    }

    @Transactional(readOnly = true)
    public RefundResponse get(UUID refundId) {
        return RefundResponse.from(refunds.findById(refundId)
                .orElseThrow(() -> DomainException.notFound("REFUND_NOT_FOUND", "Không tìm thấy yêu cầu hoàn tiền")));
    }

    @Transactional(readOnly = true)
    public RefundResponse getOwned(UUID actorId, UUID refundId) {
        Refund refund = refunds.findById(refundId)
                .orElseThrow(() -> DomainException.notFound("REFUND_NOT_FOUND", "Không tìm thấy yêu cầu hoàn tiền"));
        orders.findById(refund.getOrderId())
                .orElseThrow(() -> DomainException.notFound("ORDER_NOT_FOUND", "Không tìm thấy đơn hàng"))
                .requireOwner(actorId);
        return RefundResponse.from(refund);
    }

    @Transactional(readOnly = true)
    public List<RefundResponse> byOrderOwned(UUID actorId, UUID orderId) {
        orders.findById(orderId)
                .orElseThrow(() -> DomainException.notFound("ORDER_NOT_FOUND", "Không tìm thấy đơn hàng"))
                .requireOwner(actorId);
        return byOrder(orderId);
    }

    @Transactional(readOnly = true)
    public List<RefundResponse> byOrder(UUID orderId) {
        return refunds.findAllByOrderIdOrderByCreatedAt(orderId).stream().map(RefundResponse::from).toList();
    }

    public WebhookProcessingResult handleRefundWebhook(PaymentProvider provider, String rawBody, Map<String, String> headers) {
        PaymentGatewayPort gateway = gateways.forProvider(provider);
        try (LogContext.Scope ignored = LogContext.of(provider.name())) {
            return applyRefundWebhook(provider, rawBody, headers);
        }
    }

    private WebhookProcessingResult applyRefundWebhook(PaymentProvider provider, String rawBody, Map<String, String> headers) {
        PaymentGatewayPort gateway = gateways.forProvider(provider);
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
            if (webhookEvents.findByProviderAndEventId(provider, e.eventId()).isPresent()) {
                log.info("Webhook refund trùng {} {}: bỏ qua", provider, e.eventId());
                return DUPLICATE;
            }
            throw duplicate;
        }
        Refund refund = refunds.findByProviderAndProviderRefundId(provider, e.providerRefundId())
                .or(() -> refunds.findByIdempotencyKey(e.referenceId())).orElse(null);
        WebhookProcessingResult result;
        if (refund == null) {
            log.warn("Webhook refund cho {} / {} không khớp refund nào: bỏ qua", e.providerRefundId(), e.referenceId());
            result = IGNORED;
        } else {
            LogContext.refundId(refund.getId());
            tagLog(refund);
            result = results.apply(refund.getId(),
                    new RefundStatusResult(e.status(), e.providerRefundId(), e.failureCode(), e.failureReason(), Instant.now()), false);
            audit.record(refund.getOrderId(), "INBOUND", "webhook:refund", null, e.rawPayload(), 200, 0);
        }
        UUID webhookId = saved.getId();
        WebhookProcessingResult finalResult = result;
        tx.executeWithoutResult(s -> webhookEvents.findById(webhookId).ifPresent(w -> w.finish(finalResult)));
        return result;
    }

    public void pollProcessing() {
        for (Refund r : refunds.findAllByStatusOrderByCreatedAt(RefundStatus.PROCESSING)) {
            if (r.getProviderRefundId() == null) continue;
            try (LogContext.Scope ignored = logScope(r)) {
                poll(r);
            }
        }
    }

    private void poll(Refund r) {
        PaymentGatewayPort gateway = gateways.forProvider(r.getProvider());
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
            boolean timedOut = Boolean.TRUE.equals(tx.execute(s -> refunds.findWithLockById(r.getId()).map(x -> {
                x.polled();
                if (tooLong && x.getStatus() == RefundStatus.PROCESSING) {
                    x.manualReview("PROCESSING_TIMEOUT", "Provider chưa chốt sau " + processingTimeout
                            + "; lệnh có thể vẫn đang bay, KHÔNG gửi lại, tra dashboard trước");
                    return true;
                }
                return false;
            }).orElse(false)));
            if (timedOut) sendMailQuietly("NEEDS_REVIEW", r.getId(), () -> notifier.needsReview(r.getId()));
            return;
        }
        results.apply(r.getId(), status, false);
    }

    public void drainQueue() {
        List<Refund> queue = refunds.findAllByStatusOrderByQueuedSince(RefundStatus.AWAITING_FUNDS);
        if (queue.isEmpty()) return;
        for (Refund r : queue) {
            long available = wallet.available(r.getProvider());
            if (r.getQueuedSince() != null && r.getQueuedSince().plus(awaitingFundsTimeout).isBefore(Instant.now())) {
                boolean movedToReview = Boolean.TRUE.equals(tx.execute(s -> refunds.findWithLockById(r.getId())
                        .map(x -> {
                            boolean wasQueued = x.getStatus() == RefundStatus.AWAITING_FUNDS;
                            x.manualReview("AWAITING_FUNDS_TIMEOUT", "Ví không đủ sau " + awaitingFundsTimeout);
                            return wasQueued;
                        })
                        .orElse(false)));
                if (movedToReview) sendMailQuietly("NEEDS_REVIEW", r.getId(), () -> notifier.needsReview(r.getId()));
                continue;
            }
            if (r.getAmount() > available) break;
            try (LogContext.Scope ignored = logScope(r)) {
                submit(r.getId());
            }
            available -= r.getAmount();
        }
    }

    public void recoverStuck() {
        Instant cutoff = Instant.now().minus(STUCK_AFTER);
        for (Refund r : refunds.findAllByStatusAndSubmittedAtBefore(RefundStatus.REQUESTED, cutoff)) {
            if (r.getIdempotencyKey() == null) continue;
            try (LogContext.Scope ignored = logScope(r)) {
                if (!adoptByReference(r.getId(), r.getIdempotencyKey())) {
                    log.info("Refund {} không có ở provider, gửi lại cùng key {}", r.getId(), r.getIdempotencyKey());
                    submit(r.getId());
                }
            }
        }
        refunds.findAllByStatusAndSubmittedAtIsNullAndCreatedAtBefore(RefundStatus.REQUESTED, cutoff)
                .forEach(r -> {
                    try (LogContext.Scope ignored = logScope(r)) {
                        submit(r.getId());
                    }
                });
    }

    public RefundResponse resolve(UUID refundId, ResolveRefundRequest req) {
        Refund r = refunds.findById(refundId)
                .orElseThrow(() -> DomainException.notFound("REFUND_NOT_FOUND", "Không tìm thấy yêu cầu hoàn tiền"));
        if (req.outcome() == ResolveRefundRequest.Outcome.CANCELLED) {
            requireCancellable(r, req);
        } else if (r.getStatus() != RefundStatus.MANUAL_REVIEW) {
            throw DomainException.conflict("REFUND_NOT_IN_REVIEW",
                    "Chỉ chốt được refund đang MANUAL_REVIEW (hiện tại: " + r.getStatus() + ")");
        }
        try (LogContext.Scope ignored = logScope(r)) {
            log.info("Admin resolve refund {}: {} ({})", refundId, req.outcome(), req.note());
            return applyResolve(r, refundId, req);
        }
    }

    private void requireCancellable(Refund r, ResolveRefundRequest req) {
        if (req.note() == null || req.note().isBlank()) {
            throw DomainException.badRequest("REFUND_CANCEL_NOTE_REQUIRED",
                    "Hủy yêu cầu hoàn tiền phải ghi note (lý do hủy)");
        }
        if (r.getStatus() != RefundStatus.MANUAL_REVIEW && r.getStatus() != RefundStatus.AWAITING_FUNDS) {
            throw DomainException.conflict("REFUND_NOT_CANCELLABLE",
                    "Chỉ hủy được refund đang MANUAL_REVIEW hoặc AWAITING_FUNDS (hiện tại: " + r.getStatus()
                            + "). REQUESTED: lệnh chi có thể đang bay ở provider; PROCESSING: tiền đang chuyển"
                            + "; SUCCEEDED/FAILED: đã chốt xong rồi");
        }
        if (r.getProviderRefundId() != null) {
            throw DomainException.conflict("REFUND_ALREADY_AT_PROVIDER",
                    "Lệnh đã nằm ở provider, tra dashboard PayOS rồi chốt SUCCEEDED hoặc FAILED, không hủy");
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
            case CANCELLED -> {
                WebhookProcessingResult applied = results.apply(refundId, new RefundStatusResult(
                        RefundStatusResult.Status.CANCELLED, null, "CANCELLED_BY_ORGANIZER", req.note(), Instant.now()), true);
                if (applied == WebhookProcessingResult.PROCESSED) {
                    sendMailQuietly("CANCELLED_TO_CUSTOMER", refundId, () -> notifier.cancelledByOrganizer(refundId));
                }
            }
        }
        return get(refundId);
    }
}
