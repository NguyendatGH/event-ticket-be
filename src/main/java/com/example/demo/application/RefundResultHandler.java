package com.example.demo.application;

import com.example.demo.domain.ledger.LedgerAccount;
import com.example.demo.domain.order.Order;
import com.example.demo.domain.order.Ticket;
import com.example.demo.domain.order.TicketStatus;
import com.example.demo.domain.payment.RefundStatusResult;
import com.example.demo.domain.payment.WebhookProcessingResult;
import com.example.demo.domain.refund.Refund;
import com.example.demo.domain.refund.RefundStatus;
import com.example.demo.infrastructure.persistence.InventoryRepository;
import com.example.demo.infrastructure.persistence.OrderRepository;
import com.example.demo.infrastructure.persistence.RefundRepository;
import com.example.demo.infrastructure.persistence.TicketRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.example.demo.domain.payment.WebhookProcessingResult.IGNORED;
import static com.example.demo.domain.payment.WebhookProcessingResult.PROCESSED;

@Component
public class RefundResultHandler {

    private static final Logger log = LoggerFactory.getLogger(RefundResultHandler.class);

    private static final String CANCELLED_BY_ORGANIZER = "CANCELLED_BY_ORGANIZER";

    private static final List<RefundStatus> OPEN = List.of(RefundStatus.REQUESTED, RefundStatus.AWAITING_FUNDS,
            RefundStatus.PROCESSING, RefundStatus.MANUAL_REVIEW);

    private final RefundRepository refunds;
    private final OrderRepository orders;
    private final TicketRepository tickets;
    private final InventoryRepository inventory;
    private final LedgerService ledger;
    private final SellerWalletService sellerWallet;
    private final BuyerWalletService buyerWallet;
    private final RefundNotifier notifier;

    public RefundResultHandler(RefundRepository refunds, OrderRepository orders, TicketRepository tickets,
                               InventoryRepository inventory, LedgerService ledger, SellerWalletService sellerWallet,
                               BuyerWalletService buyerWallet, RefundNotifier notifier) {
        this.refunds = refunds;
        this.orders = orders;
        this.tickets = tickets;
        this.inventory = inventory;
        this.ledger = ledger;
        this.sellerWallet = sellerWallet;
        this.buyerWallet = buyerWallet;
        this.notifier = notifier;
    }

    @Transactional
    public WebhookProcessingResult apply(UUID refundId, RefundStatusResult result, boolean byAdmin) {
        UUID orderId = refunds.findById(refundId).orElseThrow().getOrderId();
        Order order = orders.findWithLockById(orderId).orElseThrow();
        Refund refund = refunds.findWithLockById(refundId).orElseThrow();

        if (refund.isTerminal()) {
            if (result.status() == RefundStatusResult.Status.REVERSED) {
                log.warn("Refund {} đã {} nhưng provider báo REVERSED: KHÔNG tự trừ kho lại, cần người xem",
                        refundId, refund.getStatus());
            } else {
                log.info("Refund {} đã {}, bỏ qua kết quả {}", refundId, refund.getStatus(), result.status());
            }
            return IGNORED;
        }
        if (!byAdmin && !refund.acceptsProviderResult()) {
            log.warn("Refund {} đang {}, bỏ qua kết quả {} từ provider (chờ admin)", refundId, refund.getStatus(), result.status());
            return IGNORED;
        }
        if (result.providerRefundId() != null) refund.attachProvider(result.providerRefundId());
        refund.polled();

        List<Ticket> items = tickets.findAllById(refund.ticketIds());
        switch (result.status()) {
            case SUCCEEDED -> {
                refund.succeed();
                items.forEach(Ticket::markRefunded);
                long remaining = tickets.countByOrderIdAndStatusNot(order.getId(), TicketStatus.REFUNDED);
                order.onRefundSucceeded(refund.getAmount(), remaining);
                releaseInventory(items);
                ledger.recordRefundSettled(refund);
                sellerWallet.recordRefund(order, refund);
                buyerWallet.recordRefund(order, refund);
                assertNotOverRefunded(order);
                log.info("Refund {} SUCCEEDED: {} vé, {} VND, order {} -> {}",
                        refundId, items.size(), refund.getAmount(), order.getOrderCode(), order.getStatus());
                mailAfterCommit("SUCCEEDED_TO_CUSTOMER", refundId, () -> notifier.succeededToCustomer(refundId));
            }
            case FAILED, CANCELLED -> {
                refund.fail(result.failureCode() != null ? result.failureCode() : result.status().name(), result.failureReason());
                items.forEach(Ticket::restoreActive);
                order.onRefundFailed();
                log.warn("Refund {} FAILED ({}): vé về ACTIVE, kho giữ nguyên", refundId, refund.getFailureCode());
                if (!CANCELLED_BY_ORGANIZER.equals(refund.getFailureCode()))
                    mailAfterCommit("FAILED_TO_CUSTOMER", refundId, () -> notifier.failedToCustomer(refundId));
            }
            case ON_HOLD, REVERSED -> {
                refund.manualReview(result.status().name(), result.failureReason());
                log.warn("Refund {} {} ở provider: MANUAL_REVIEW", refundId, result.status());
                mailAfterCommit("NEEDS_REVIEW", refundId, () -> notifier.needsReview(refundId));
            }
            case RECEIVED, PROCESSING -> {
                return IGNORED;
            }
        }
        return PROCESSED;
    }

    private static void mailAfterCommit(String kind, UUID refundId, Runnable send) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    send.run();
                } catch (RuntimeException ex) {
                    log.warn("Không gửi được mail {} cho refund {}: {}", kind, refundId, ex.toString());
                }
            }
        });
    }

    private void assertNotOverRefunded(Order order) {
        long paidIn = ledger.customerLiabilityOf(order.getId());
        if (paidIn <= 0) return;
        long refunded = ledger.refundedOf(order.getId());
        long inFlight = refunds.sumAmountByOrderIdAndStatusIn(order.getId(), OPEN);
        if (refunded + inFlight > paidIn) {
            throw new IllegalStateException("Đơn " + order.getOrderCode() + ": tổng hoàn " + (refunded + inFlight)
                    + " vượt số đã thu " + paidIn + " (đã hoàn " + refunded + ", đang chạy " + inFlight + ")");
        }
    }

    private void releaseInventory(List<Ticket> refunded) {
        Map<UUID, Long> byTier = refunded.stream()
                .collect(Collectors.groupingBy(Ticket::getTicketTierId, TreeMap::new, Collectors.counting()));
        byTier.forEach((tierId, n) -> inventory.findWithLockByTicketTierId(tierId).ifPresent(inv -> inv.release(n.intValue())));
    }
}
