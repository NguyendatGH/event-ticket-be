package com.example.demo.application;

import com.example.demo.domain.order.TicketStatus;
import com.example.demo.domain.payment.PaymentProvider;
import com.example.demo.domain.payment.MerchantGateway;
import com.example.demo.domain.refund.Refund;
import com.example.demo.domain.refund.RefundStatus;
import com.example.demo.infrastructure.persistence.RefundRepository;
import com.example.demo.infrastructure.persistence.TicketRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class WalletService {

    public record Snapshot(long balance, long committed, long available, long liability, double coverage, long queued) {}

    private static final List<RefundStatus> COMMITTED = List.of(RefundStatus.REQUESTED, RefundStatus.PROCESSING);

    private final PaymentGatewayRegistry gateways;
    private final RefundRepository refunds;
    private final TicketRepository tickets;
    private final MerchantGateway configuredDefault;

    public WalletService(PaymentGatewayRegistry gateways, RefundRepository refunds, TicketRepository tickets,
                         @Value("${app.payment.default-gateway:PAYOS}") MerchantGateway configuredDefault) {
        this.gateways = gateways;
        this.refunds = refunds;
        this.tickets = tickets;
        this.configuredDefault = configuredDefault;
    }

    public long available() {
        return available(gateways.defaultGateway(configuredDefault).provider());
    }

    public long available(PaymentProvider provider) {
        return gateways.forProvider(provider).getPayoutBalance()
                - refunds.sumAmountByProviderAndStatusIn(provider, COMMITTED);
    }

    public long availableFor(Refund refund) {
        long own = COMMITTED.contains(refund.getStatus()) ? refund.getAmount() : 0;
        return available(refund.getProvider()) + own;
    }

    public Snapshot snapshot() {
        PaymentProvider provider = gateways.defaultGateway(configuredDefault).provider();
        long balance = gateways.forProvider(provider).getPayoutBalance();
        long committed = refunds.sumAmountByProviderAndStatusIn(provider, COMMITTED);
        long liability = tickets.sumPriceByStatus(TicketStatus.ACTIVE);
        long available = balance - committed;
        double coverage = liability == 0 ? 1.0 : (double) available / liability;
        return new Snapshot(balance, committed, available, liability, coverage,
                refunds.countByStatus(RefundStatus.AWAITING_FUNDS));
    }
}
