package com.example.demo.application;

import com.example.demo.domain.ledger.LedgerAccount;
import com.example.demo.domain.ledger.LedgerEntry;
import com.example.demo.domain.order.Order;
import com.example.demo.domain.refund.Refund;
import com.example.demo.infrastructure.persistence.LedgerRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

import static com.example.demo.domain.ledger.LedgerDirection.CREDIT;
import static com.example.demo.domain.ledger.LedgerDirection.DEBIT;

@Service
public class LedgerService {

    private final LedgerRepository ledger;

    public LedgerService(LedgerRepository ledger) {
        this.ledger = ledger;
    }

    public void recordOrderPaid(Order order) {
        long paid = order.getPaidAmount();
        if (paid <= 0) return;
        long fee = Math.min(order.getFeeAmount(), paid);
        save(List.of(
                LedgerEntry.of(LedgerAccount.BANK_COLLECTION, DEBIT, paid, "ORDER", order.getId()),
                LedgerEntry.of(LedgerAccount.CUSTOMER_LIABILITY, CREDIT, paid - fee, "ORDER", order.getId()),
                LedgerEntry.of(LedgerAccount.FEES, CREDIT, fee, "ORDER", order.getId())));
    }

    public void recordRefundSettled(Refund refund) {
        long amount = refund.getAmount();
        if (amount <= 0) return;
        save(List.of(
                LedgerEntry.of(LedgerAccount.CUSTOMER_LIABILITY, DEBIT, amount, "REFUND", refund.getId()),
                LedgerEntry.of(LedgerAccount.PAYOUT_WALLET, CREDIT, amount, "REFUND", refund.getId())));
    }

    public long customerLiabilityOf(UUID orderId) {
        return ledger.sumFor(LedgerAccount.CUSTOMER_LIABILITY, CREDIT, "ORDER", orderId);
    }

    public long refundedOf(UUID orderId) {
        return ledger.sumRefundedByOrder(orderId);
    }

    private void save(List<LedgerEntry> entries) {
        long debit = entries.stream().filter(e -> e.getDirection() == DEBIT).mapToLong(LedgerEntry::getAmount).sum();
        long credit = entries.stream().filter(e -> e.getDirection() == CREDIT).mapToLong(LedgerEntry::getAmount).sum();
        if (debit != credit) {
            throw new IllegalStateException("Bút toán lệch: nợ " + debit + " != có " + credit);
        }
        ledger.saveAll(entries.stream().filter(e -> e.getAmount() > 0).toList());
    }
}
