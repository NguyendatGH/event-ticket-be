package com.example.demo.application;

import com.example.demo.domain.order.TicketStatus;
import com.example.demo.domain.payment.PaymentGatewayPort;
import com.example.demo.domain.refund.RefundStatus;
import com.example.demo.infrastructure.persistence.RefundRepository;
import com.example.demo.infrastructure.persistence.TicketRepository;
import org.springframework.stereotype.Service;

import java.util.List;

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
