package com.example.demo.application.impl;

import com.example.demo.application.CheckoutService;
import com.example.demo.application.GatewayAudit;
import com.example.demo.application.IdempotencyService;
import com.example.demo.application.OrderFulfilment;
import com.example.demo.application.OrderQueries;
import com.example.demo.application.dto.CreateOrderRequest;
import com.example.demo.application.dto.OrderResponse;

import com.example.demo.domain.common.DomainException;
import com.example.demo.domain.common.LogContext;
import com.example.demo.domain.event.Event;
import com.example.demo.domain.event.TicketTier;
import com.example.demo.domain.idempotency.IdempotencyScope;
import com.example.demo.domain.inventory.Inventory;
import com.example.demo.domain.order.Order;
import com.example.demo.domain.order.OrderItem;
import com.example.demo.domain.payment.CreatePaymentCommand;
import com.example.demo.domain.payment.Payment;
import com.example.demo.domain.payment.PaymentGatewayPort;
import com.example.demo.domain.payment.PaymentLink;
import com.example.demo.domain.payment.PaymentStatus;
import com.example.demo.infrastructure.persistence.EventRepository;
import com.example.demo.infrastructure.persistence.InventoryRepository;
import com.example.demo.infrastructure.persistence.OrderRepository;
import com.example.demo.infrastructure.persistence.PaymentRepository;
import com.example.demo.infrastructure.persistence.TicketTierRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class CheckoutServiceImpl implements CheckoutService {

    private static final Logger log = LoggerFactory.getLogger(CheckoutServiceImpl.class);

    private final EventRepository events;
    private final TicketTierRepository tiers;
    private final InventoryRepository inventory;
    private final OrderRepository orders;
    private final PaymentRepository payments;
    private final OrderFulfilment fulfilment;
    private final OrderQueries orderQueries;
    private final IdempotencyService idempotency;
    private final PaymentGatewayPort gateway;
    private final GatewayAudit audit;
    private final TransactionTemplate tx;
    private final Duration orderTtl;
    private final long fee;
    private final String returnUrl;
    private final String cancelUrl;

    public CheckoutServiceImpl(EventRepository events, TicketTierRepository tiers, InventoryRepository inventory,
                               OrderRepository orders, PaymentRepository payments, OrderFulfilment fulfilment,
                               OrderQueries orderQueries, IdempotencyService idempotency, PaymentGatewayPort gateway,
                               GatewayAudit audit, TransactionTemplate tx,
                               @Value("${app.checkout.order-ttl}") Duration orderTtl,
                               @Value("${app.checkout.fee}") long fee,
                               @Value("${app.payment.return-url}") String returnUrl,
                               @Value("${app.payment.cancel-url}") String cancelUrl) {
        this.events = events;
        this.tiers = tiers;
        this.inventory = inventory;
        this.orders = orders;
        this.payments = payments;
        this.fulfilment = fulfilment;
        this.orderQueries = orderQueries;
        this.idempotency = idempotency;
        this.gateway = gateway;
        this.audit = audit;
        this.tx = tx;
        this.orderTtl = orderTtl;
        this.fee = fee;
        this.returnUrl = returnUrl;
        this.cancelUrl = cancelUrl;
    }

    @Override
    public OrderResponse create(UUID userId, String idempotencyKey, CreateOrderRequest req) {
        return idempotency.execute(IdempotencyScope.CHECKOUT, idempotencyKey, req, OrderResponse.class,
                () -> checkout(req, idempotencyKey, userId));
    }

    private OrderResponse checkout(CreateOrderRequest req, String idempotencyKey, UUID userId) {

        log.info("running checkout process --");
        Order order = tx.execute(s -> reserveTiers(req, idempotencyKey, userId));
        Event event = events.findById(order.getEventId()).orElseThrow();

        CreatePaymentCommand command = new CreatePaymentCommand(order.getOrderCode(), order.getTotalAmount(),
                "Ve " + order.getOrderCode(),
                order.getItems().stream().map(i -> new CreatePaymentCommand.Item(i.getTierName(), i.getQuantity(), i.getUnitPrice())).toList(),
                returnUrl + "?orderId=" + order.getId(), cancelUrl + "?orderId=" + order.getId(), order.getExpiresAt());
        long startedAt = System.currentTimeMillis();
        try (LogContext.Scope ignored = LogContext.order(gateway.provider().name(), order.getOrderCode())) {
            try {
                log.info("Sending create payment request");
                PaymentLink link = gateway.createPaymentLink(command);
                log.info("Payment link created: {}", link);
                audit.record(order.getId(), "OUTBOUND", "createPaymentLink", command, link, 200, System.currentTimeMillis() - startedAt);
                return tx.execute(s -> {
                    Payment payment = payments.save(Payment.pending(order.getId(), gateway.provider(), link, order.getTotalAmount()));
                    return OrderResponse.from(orders.findById(order.getId()).orElseThrow(), event, List.of(), payment);
                });
            } catch (RuntimeException ex) {
                log.error("Payment link creation failed", ex);
                audit.record(order.getId(), "OUTBOUND", "createPaymentLink", command,
                        Map.of("error", String.valueOf(ex.getMessage())), null, System.currentTimeMillis() - startedAt);
                tx.executeWithoutResult(s -> orders.findWithLockById(order.getId()).ifPresent(o -> {
                    o.cancel();
                    fulfilment.release(o);
                }));
                throw new DomainException(HttpStatus.BAD_GATEWAY, "PAYMENT_LINK_FAILED", "Cổng thanh toán không phản hồi, đơn đã hủy và trả vé. Thử lại sau.");
            }
        }
    }

    private Order reserveTiers(CreateOrderRequest req, String idempotencyKey, UUID userId) {
        Event event = events.findById(req.eventId())
                .orElseThrow(() -> DomainException.notFound("EVENT_NOT_FOUND", "Không tìm thấy sự kiện"));
        if (!event.isOnSale(Instant.now()))
            throw DomainException.conflict("EVENT_NOT_ON_SALE", "Sự kiện chưa mở bán hoặc đã kết thúc");
        if (req.items() == null || req.items().isEmpty())
            throw DomainException.badRequest("ORDER_EMPTY", "Đơn hàng chưa có vé");

        Map<UUID, TicketTier> tierById = tiers.findAllByEventIdOrderByPriceAsc(event.getId()).stream()
                .collect(Collectors.toMap(TicketTier::getId, Function.identity()));

        Map<UUID, Integer> qtyByTier = new TreeMap<>();
        for (CreateOrderRequest.Item item : req.items()) {
            if (!tierById.containsKey(item.tierId()))
                throw DomainException.badRequest("TIER_NOT_FOUND", "Hạng vé " + item.tierId() + " không thuộc sự kiện");
            if (item.quantity() < 1) throw DomainException.badRequest("QUANTITY_INVALID", "Số lượng vé phải lớn hơn 0");
            qtyByTier.merge(item.tierId(), item.quantity(), Integer::sum);
        }

        List<OrderItem> items = new ArrayList<>();
        for (Map.Entry<UUID, Integer> e : qtyByTier.entrySet()) {
            TicketTier tier = tierById.get(e.getKey());
            int qty = e.getValue();
            if (qty > tier.getMaxPerOrder()) {
                throw DomainException.badRequest("QUANTITY_EXCEEDED", "Tối đa " + tier.getMaxPerOrder() + " vé " + tier.getName() + " mỗi đơn");
            }
            Inventory inv = inventory.findWithLockByTicketTierId(tier.getId())
                    .orElseThrow(() -> DomainException.conflict("TIER_SOLD_OUT", "Hạng vé " + tier.getName() + " chưa mở bán"));
            if (!inv.canReserve(qty)) {
                throw DomainException.conflict("TIER_SOLD_OUT", "Hạng vé " + tier.getName() + " không đủ số lượng (còn " + inv.getAvailable() + ")");
            }
            inv.reserve(qty);
            items.add(new OrderItem(tier.getId(), tier.getName(), qty, tier.getPrice()));
        }

        return orders.save(Order.create(event.getId(), items, req.customer().name().trim(), req.customer().email().trim(),
                req.customer().phone(), fee, Instant.now().plus(orderTtl), idempotencyKey, userId));
    }

    @Override
    public OrderResponse cancel(UUID userId, UUID orderId) {
        Order order = tx.execute(s -> {
            Order o = orders.findWithLockById(orderId)
                    .orElseThrow(() -> DomainException.notFound("ORDER_NOT_FOUND", "Không tìm thấy đơn hàng"));
            o.requireOwner(userId);
            o.cancel();
            fulfilment.release(o);
            closePayment(o);
            return o;
        });
        payments.findFirstByOrderIdOrderByCreatedAtDesc(orderId).ifPresent(p -> cancelLinkQuietly(p, "customer cancelled"));
        return tx.execute(s -> orderQueries.toResponse(orders.findById(order.getId()).orElseThrow()));
    }


    @Override
    @Transactional
    public boolean expire(UUID orderId) {
        Order order = orders.findWithLockById(orderId).orElse(null);
        if (order == null || !order.isPending()) return false;
        order.expire();
        fulfilment.release(order);
        closePayment(order);
        return true;
    }

    private void closePayment(Order order) {
        payments.findFirstByOrderIdOrderByCreatedAtDesc(order.getId()).ifPresent(p -> {
            if (p.getStatus() == PaymentStatus.UNDERPAID) {
                log.warn("Order {} đóng khi đã nhận thiếu tiền: chuyển MANUAL_REVIEW", order.getOrderCode());
                order.markManualReview();
            } else {
                p.expire();
            }
        });
    }


    @Override
    public void cancelLinkQuietly(Payment payment, String reason) {
        if (payment.isPaid() || payment.getProviderPaymentId() == null) return;
        Map<String, String> request = Map.of("providerPaymentId", payment.getProviderPaymentId(), "reason", reason);
        long startedAt = System.currentTimeMillis();
        try {
            gateway.cancelPaymentLink(payment.getProviderPaymentId(), reason);
            audit.record(payment.getOrderId(), "OUTBOUND", "cancelPaymentLink", request,
                    Map.of("ok", true), 200, System.currentTimeMillis() - startedAt);
        } catch (RuntimeException ex) {
            log.warn("Hủy link {} thất bại: {}", payment.getProviderPaymentId(), ex.toString());
            audit.record(payment.getOrderId(), "OUTBOUND", "cancelPaymentLink", request,
                    Map.of("error", String.valueOf(ex.getMessage())), null, System.currentTimeMillis() - startedAt);
        }
    }
}
