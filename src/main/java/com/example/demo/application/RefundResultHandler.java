package com.example.demo.application;

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

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.example.demo.domain.payment.WebhookProcessingResult.IGNORED;
import static com.example.demo.domain.payment.WebhookProcessingResult.PROCESSED;

/**
 * Một cửa duy nhất chốt kết quả refund và hoàn kho (poll, webhook, admin resolve đều đổ vào đây).
 * Một transaction: khóa order → khóa refund → chuyển trạng thái có điều kiện. Lần thứ hai thấy refund đã terminal
 * thì IGNORED, nên kho không bao giờ cộng hai lần dù webhook trùng, poll trùng hay admin bấm hai lần.
 */
@Component
public class RefundResultHandler {

    private static final Logger log = LoggerFactory.getLogger(RefundResultHandler.class);

    /** Refund chưa kết thúc: tiền vẫn có thể ra khỏi ví cho những cái này. */
    private static final List<RefundStatus> OPEN = List.of(RefundStatus.REQUESTED, RefundStatus.AWAITING_FUNDS,
            RefundStatus.PROCESSING, RefundStatus.MANUAL_REVIEW);

    private final RefundRepository refunds;
    private final OrderRepository orders;
    private final TicketRepository tickets;
    private final InventoryRepository inventory;

    public RefundResultHandler(RefundRepository refunds, OrderRepository orders, TicketRepository tickets,
                               InventoryRepository inventory) {
        this.refunds = refunds;
        this.orders = orders;
        this.tickets = tickets;
        this.inventory = inventory;
    }

    /**
     * @param byAdmin true khi admin chốt MANUAL_REVIEW: bỏ qua điều kiện "đang PROCESSING",
     *                nhưng vẫn KHÔNG đụng refund đã terminal.
     */
    @Transactional
    public WebhookProcessingResult apply(UUID refundId, RefundStatusResult result, boolean byAdmin) {
        UUID orderId = refunds.findById(refundId).orElseThrow().getOrderId();
        Order order = orders.findWithLockById(orderId).orElseThrow();   // khóa order trước, cùng thứ tự với RefundService.open()
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
                assertNotOverRefunded(order);
                log.info("Refund {} SUCCEEDED: {} vé, {} VND, order {} -> {}",
                        refundId, items.size(), refund.getAmount(), order.getOrderCode(), order.getStatus());
            }
            case FAILED, CANCELLED -> {
                refund.fail(result.failureCode() != null ? result.failureCode() : result.status().name(), result.failureReason());
                items.forEach(Ticket::restoreActive);
                order.onRefundFailed();
                log.warn("Refund {} FAILED ({}): vé về ACTIVE, kho giữ nguyên", refundId, refund.getFailureCode());
            }
            case ON_HOLD, REVERSED -> {
                refund.manualReview(result.status().name(), result.failureReason());
                log.warn("Refund {} {} ở provider: MANUAL_REVIEW", refundId, result.status());
            }
            case RECEIVED, PROCESSING -> {
                return IGNORED;
            }
        }
        return PROCESSED;
    }

    /**
     * Không bao giờ hoàn quá số tiền đã thu của đơn. Ném ra là rollback cả transaction — đúng ý: thà refund
     * không chốt được còn hơn sổ sách sai.
     */
    private void assertNotOverRefunded(Order order) {
        long paid = order.getPaidAmount();
        if (paid <= 0) return;                     // đơn cũ chưa ghi paid_amount: không có gì để so
        long open = refunds.sumAmountByOrderIdAndStatusIn(order.getId(), OPEN);
        long total = order.getRefundedAmount() + open;
        if (total > paid) {
            throw new IllegalStateException("Đơn " + order.getOrderCode() + ": tổng hoàn " + total
                    + " vượt số đã thu " + paid + " (đã hoàn " + order.getRefundedAmount() + ", đang chạy " + open + ")");
        }
    }

    /** Cộng kho theo tier, khóa theo tier id tăng dần như CheckoutService để không deadlock chéo với checkout. */
    private void releaseInventory(List<Ticket> refunded) {
        Map<UUID, Long> byTier = refunded.stream()
                .collect(Collectors.groupingBy(Ticket::getTicketTierId, TreeMap::new, Collectors.counting()));
        byTier.forEach((tierId, n) -> inventory.findWithLockByTicketTierId(tierId).ifPresent(inv -> inv.release(n.intValue())));
    }
}
