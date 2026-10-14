package com.example.demo.application.impl;

import com.example.demo.application.CheckoutService;
import com.example.demo.application.GatewayAudit;
import com.example.demo.application.LedgerService;
import com.example.demo.application.OrderFulfilment;
import com.example.demo.application.PaymentGatewayRegistry;
import com.example.demo.application.PaymentService;
import com.example.demo.application.SellerWalletService;
import com.example.demo.application.dto.OrderResponse;
import com.example.demo.domain.common.DomainException;
import com.example.demo.domain.common.LogContext;
import com.example.demo.domain.order.Order;
import com.example.demo.domain.order.OrderStatus;
import com.example.demo.domain.payment.InvalidWebhookSignatureException;
import com.example.demo.domain.payment.Payment;
import com.example.demo.domain.payment.PaymentEvent;
import com.example.demo.domain.payment.PaymentGatewayPort;
import com.example.demo.domain.payment.PaymentProvider;
import com.example.demo.domain.payment.PaymentStatusResult;
import com.example.demo.domain.payment.WebhookEvent;
import com.example.demo.domain.payment.WebhookProcessingResult;
import com.example.demo.infrastructure.persistence.OrderRepository;
import com.example.demo.infrastructure.persistence.PaymentRepository;
import com.example.demo.infrastructure.persistence.WebhookEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static com.example.demo.domain.payment.WebhookProcessingResult.*;


@Service
public class PaymentServiceImpl implements PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentServiceImpl.class);

    private final OrderRepository orders;
    private final PaymentRepository payments;
    private final OrderFulfilment fulfilment;
    private final WebhookEventRepository webhookEvents;
    private final PaymentGatewayRegistry gateways;
    private final GatewayAudit audit;
    private final LedgerService ledger;
    private final SellerWalletService sellerWallet;
    private final CheckoutService checkout;
    private final TransactionTemplate tx;
    private final ObjectMapper json;

    public PaymentServiceImpl(OrderRepository orders, PaymentRepository payments, OrderFulfilment fulfilment,
                              WebhookEventRepository webhookEvents, PaymentGatewayRegistry gateways, GatewayAudit audit, LedgerService ledger,
                              SellerWalletService sellerWallet, CheckoutService checkout, TransactionTemplate tx, ObjectMapper json) {
        this.orders = orders;
        this.payments = payments;
        this.fulfilment = fulfilment;
        this.webhookEvents = webhookEvents;
        this.gateways = gateways;
        this.audit = audit;
        this.ledger = ledger;
        this.sellerWallet = sellerWallet;
        this.checkout = checkout;
        this.tx = tx;
        this.json = json;
    }

    @Override
    public WebhookProcessingResult handleWebhook(PaymentProvider provider, String rawBody, Map<String, String> headers) {
        PaymentGatewayPort gateway = gateways.forProvider(provider);
        try (LogContext.Scope ignored = LogContext.of(provider.name())) {
            PaymentEvent event;
            try {
                log.info("running handling webhook --");
                event = gateway.verifyAndParse(rawBody, headers);
            } catch (InvalidWebhookSignatureException ex) {
                log.warn("Webhook {} sai chữ ký", provider);
                tx.executeWithoutResult(s -> webhookEvents.save(WebhookEvent.rejected(provider, audit.jsonOrWrapped(rawBody))));
                throw ex;
            }
            LogContext.orderCode(event.orderCode());
            LogContext.trade(null, null, event.providerPaymentId());
            return record(provider, "payment", event);
        }
    }


    WebhookProcessingResult record(PaymentProvider provider, String eventType, PaymentEvent event) {
        WebhookEvent saved;
        try {
            saved = tx.execute(s -> webhookEvents.saveAndFlush(WebhookEvent.received(provider, event.eventId(), eventType, event.rawPayload())));
        } catch (DataIntegrityViolationException duplicate) {
            if (webhookEvents.findByProviderAndEventId(provider, event.eventId()).isPresent()) {
                log.info("Webhook trùng {} {}: bỏ qua", provider, event.eventId());
                return DUPLICATE;
            }
            throw duplicate;
        }
        try {
            return tx.execute(s -> apply(event, saved.getId()));
        } catch (RuntimeException ex) {
            tx.executeWithoutResult(s -> webhookEvents.findById(saved.getId()).ifPresent(w -> w.finish(FAILED)));
            throw ex;
        }
    }

    private WebhookProcessingResult apply(PaymentEvent e, UUID webhookId) {
        WebhookEvent webhook = webhookEvents.findById(webhookId).orElseThrow();
        Order order = orders.findWithLockByOrderCode(e.orderCode()).orElse(null);
        Payment payment = order == null ? null : payments.findFirstByOrderIdOrderByCreatedAtDesc(order.getId()).orElse(null);
        if (payment != null) LogContext.trade(payment.getGatewayMerchantNo(), null, null);

        WebhookProcessingResult result;
        if (order == null || payment == null) {
            log.warn("Webhook cho orderCode {} không khớp đơn nào: bỏ qua", e.orderCode());
            result = IGNORED;
        } else if (!Objects.equals(e.providerPaymentId(), payment.getProviderPaymentId())
                || e.providerPaymentId() == null || e.providerPaymentId().isBlank()) {
            log.warn("Webhook {} có providerPaymentId không khớp payment của order {}: bỏ qua", e.eventId(), order.getOrderCode());
            result = IGNORED;
        } else if (order.getStatus() == OrderStatus.PAID) {
            log.info("[+] Trạng thái đơn hàng : PAID ");
            result = IGNORED;
        } else if (!e.success()) {
            if (order.isPending() && payment.isAwaitingMoney()) {
                payment.markFailed(e);
                result = PROCESSED;
            } else {
                result = IGNORED;
            }
        } else if (order.isPending() && payment.getStatus() == com.example.demo.domain.payment.PaymentStatus.OVERPAID) {
            log.warn("Order {} đã nhận OVERPAID; giữ nguyên để manual review, không tự động settle lại", order.getOrderCode());
            result = IGNORED;
        } else if (order.isPending()) {
            if (e.amount() < order.getTotalAmount()) {
                log.warn("Order {} nhận thiếu tiền: {} / {}", order.getOrderCode(), e.amount(), order.getTotalAmount());
                payment.markUnderpaid(e);
            } else if (e.amount() > order.getTotalAmount()) {
                log.error("Order {} nhận thừa tiền: {} / {}. Không tự động settle, chuyển manual review khi đóng đơn.",
                        order.getOrderCode(), e.amount(), order.getTotalAmount());
                payment.markOverpaid(e);
            } else {
                payment.confirmPaid(e);
                order.markPaid(e.amount());
                ledger.recordOrderPaid(order);
                sellerWallet.recordPayment(order);
                int n = fulfilment.fulfil(order);
                log.info("Order {} PAID, {} vé", order.getOrderCode(), n);
            }
            result = PROCESSED;
        } else if (order.getStatus() == OrderStatus.EXPIRED || order.getStatus() == OrderStatus.CANCELLED) {
            log.warn("Order {} nhận tiền sau khi {}: chuyển MANUAL_REVIEW, kho đã trả nên không cấp vé", order.getOrderCode(), order.getStatus());
            payment.markLate(e);
            order.markManualReview();
            result = PROCESSED;
        } else {
            result = IGNORED;
        }
        webhook.finish(result);
        if (order != null) audit.record(order.getId(), "INBOUND", "webhook:" + webhook.getEventType(), null, e.rawPayload(), 200, 0);
        return result;
    }

    @Override
    public String settleExpired(UUID orderId) {
        Order order = orders.findById(orderId).orElse(null);
        if (order == null || !order.isPending()) return "SKIPPED";
        Payment payment = payments.findFirstByOrderIdOrderByCreatedAtDesc(orderId).orElse(null);
        PaymentGatewayPort gateway = payment == null ? gateways.defaultGateway() : gateways.forProvider(payment.getProvider());
        try (LogContext.Scope ignored = LogContext.order(gateway.provider().name(), order.getOrderCode())) {
            if (payment != null && pollAndApply(order, payment)) return "PAID";
            if (!checkout.expire(orderId)) return "SKIPPED";
            if (payment != null) checkout.cancelLinkQuietly(payment, "order expired");
            return orders.findById(orderId)
                    .map(o -> o.getStatus() == OrderStatus.MANUAL_REVIEW ? "MANUAL_REVIEW" : "EXPIRED")
                    .orElse("SKIPPED");
        }
    }

    @Override
    public boolean reconcile(UUID orderId) {
        Order order = orders.findById(orderId).orElse(null);
        if (order == null || !order.isPending()) return false;
        Payment payment = payments.findFirstByOrderIdOrderByCreatedAtDesc(orderId).orElse(null);
        PaymentGatewayPort gateway = payment == null ? gateways.defaultGateway() : gateways.forProvider(payment.getProvider());
        try (LogContext.Scope ignored = LogContext.order(gateway.provider().name(), order.getOrderCode())) {
            return payment != null && pollAndApply(order, payment);
        }
    }

    @Override
    public OrderResponse cancelOrder(UUID userId, UUID orderId) {
        Order order = orders.findById(orderId)
                .orElseThrow(() -> DomainException.notFound("ORDER_NOT_FOUND", "Không tìm thấy đơn hàng"));
        order.requireOwner(userId);
        Payment payment = payments.findFirstByOrderIdOrderByCreatedAtDesc(orderId).orElse(null);
        PaymentGatewayPort gateway = payment == null ? gateways.defaultGateway() : gateways.forProvider(payment.getProvider());
        try (LogContext.Scope ignored = LogContext.order(gateway.provider().name(), order.getOrderCode())) {
            if (order.isPending() && reconcile(orderId)) {
                log.warn("Order {} bấm hủy nhưng provider báo đã thanh toán: giữ đơn, đã cấp vé", order.getOrderCode());
                throw DomainException.conflict("ORDER_ALREADY_PAID",
                        "Đơn đã được thanh toán nên không hủy được. Vé đã được cấp; dùng chức năng hoàn vé nếu bạn muốn hoàn tiền.");
            }
            return checkout.cancel(userId, orderId);
        }
    }

    private boolean pollAndApply(Order order, Payment payment) {
        LogContext.trade(payment.getGatewayMerchantNo(), null, payment.getProviderPaymentId());
        PaymentGatewayPort gateway = gateways.forProvider(payment.getProvider());
        long t0 = System.currentTimeMillis();
        PaymentStatusResult status;
        try {
            status = gateway.getPaymentStatus(payment.getProviderPaymentId());
        } catch (RuntimeException ex) {
            log.warn("Hỏi trạng thái {} thất bại: {}", payment.getProviderPaymentId(), ex.toString());
            audit.record(order.getId(), "OUTBOUND", "getPaymentStatus", Map.of("providerPaymentId", payment.getProviderPaymentId()), Map.of("error", String.valueOf(ex.getMessage())), null, System.currentTimeMillis() - t0);
            return false;
        }
        audit.record(order.getId(), "OUTBOUND", "getPaymentStatus", Map.of("providerPaymentId", payment.getProviderPaymentId()), status, 200, System.currentTimeMillis() - t0);
        boolean underpaid = (status.status() == PaymentStatusResult.Status.UNDERPAID
                && status.amountPaid() > 0 && status.amountPaid() < order.getTotalAmount())
                || (status.status() == PaymentStatusResult.Status.PENDING
                && status.amountPaid() > 0 && status.amountPaid() < order.getTotalAmount());
        if (status.status() != PaymentStatusResult.Status.PAID && !underpaid) return false;

        String transactionRef = status.transactionRef() == null || status.transactionRef().isBlank()
                ? "poll-" + status.status() + "-" + status.amountPaid() : status.transactionRef();
        PaymentEvent event = new PaymentEvent(payment.getProviderPaymentId() + ":poll:" + transactionRef,
                payment.getProviderPaymentId(), order.getOrderCode(), true, status.amountPaid(), transactionRef,
                status.paidAt(), null, null, json.writeValueAsString(status));
        record(payment.getProvider(), "poll", event);
        return orders.findById(order.getId()).map(o -> o.getStatus() == OrderStatus.PAID).orElse(false);
    }
}
