package com.example.demo.application;

import com.example.demo.domain.order.TicketStatus;
import com.example.demo.domain.payment.PaymentGatewayPort;
import com.example.demo.domain.refund.RefundStatus;
import com.example.demo.infrastructure.persistence.RefundRepository;
import com.example.demo.infrastructure.persistence.TicketRepository;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Không phải ví của mình. Chỉ là ba phép tính trên số dư ví chi của provider (refund-implementation-plan 1.9):
 * <pre>
 *   committed = refund đang REQUESTED/PROCESSING (tiền sắp đi hoặc đang đi)
 *   available = balance - committed             (được phép gửi lệnh mới không)
 *   liability = tổng giá vé ACTIVE               (tối đa có thể bị đòi hoàn)
 * </pre>
 * AWAITING_FUNDS KHÔNG tính vào committed, nếu không hàng chờ tự chặn chính nó mãi mãi.
 */
@Service
public class WalletService {

    public record Snapshot(long balance, long committed, long available, long liability, double coverage, long queued) {}

    private static final List<RefundStatus> COMMITTED = List.of(RefundStatus.REQUESTED, RefundStatus.PROCESSING);

    private final PaymentGatewayPort gateway;
    private final RefundRepository refunds;
    private final TicketRepository tickets;

    public WalletService(PaymentGatewayPort gateway, RefundRepository refunds, TicketRepository tickets) {
        this.gateway = gateway;
        this.refunds = refunds;
        this.tickets = tickets;
    }

    /** Gọi provider: KHÔNG gọi bên trong transaction. */
    public long available() {
        return gateway.getPayoutBalance() - refunds.sumAmountByStatusIn(COMMITTED);
    }

    public Snapshot snapshot() {
        long balance = gateway.getPayoutBalance();
        long committed = refunds.sumAmountByStatusIn(COMMITTED);
        long liability = tickets.sumPriceByStatus(TicketStatus.ACTIVE);
        long available = balance - committed;
        double coverage = liability == 0 ? 1.0 : (double) available / liability;
        return new Snapshot(balance, committed, available, liability, coverage,
                refunds.countByStatus(RefundStatus.AWAITING_FUNDS));
    }
}
