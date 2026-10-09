package com.example.demo.application.impl;

import com.example.demo.application.ReconciliationService;
import com.example.demo.application.dto.ReconciliationReport;
import com.example.demo.application.dto.ReconciliationReport.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class ReconciliationServiceImpl implements ReconciliationService {

    private final JdbcClient jdbc;

    public ReconciliationServiceImpl(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public ReconciliationReport check() {
        LedgerBalance ledger = ledger();
        List<WalletMismatch> mismatches = new java.util.ArrayList<>();
        mismatches.addAll(sellerMismatches());
        mismatches.addAll(buyerMismatches());
        Unledgered unledgered = unledgered();
        boolean ok = ledger.balanced() && ledger.refImbalances().isEmpty() && mismatches.isEmpty();
        return new ReconciliationReport(Instant.now(), ok, ledger, List.copyOf(mismatches), unledgered);
    }

    private LedgerBalance ledger() {
        List<AccountTotal> accounts = jdbc.sql("""
                select account,
                       coalesce(sum(case when direction = 'DEBIT' then amount else 0 end), 0) as debit,
                       coalesce(sum(case when direction = 'CREDIT' then amount else 0 end), 0) as credit
                from ledger_entries group by account order by account
                """).query((rs, row) -> new AccountTotal(rs.getString("account"),
                rs.getLong("debit"), rs.getLong("credit"),
                rs.getLong("debit") - rs.getLong("credit"))).list();

        long debit = accounts.stream().mapToLong(AccountTotal::debit).sum();
        long credit = accounts.stream().mapToLong(AccountTotal::credit).sum();

        List<RefImbalance> imbalances = jdbc.sql("""
                select ref_type, ref_id,
                       coalesce(sum(case when direction = 'DEBIT' then amount else 0 end), 0) as debit,
                       coalesce(sum(case when direction = 'CREDIT' then amount else 0 end), 0) as credit
                from ledger_entries where ref_id is not null
                group by ref_type, ref_id
                having coalesce(sum(case when direction = 'DEBIT' then amount else 0 end), 0)
                     <> coalesce(sum(case when direction = 'CREDIT' then amount else 0 end), 0)
                order by ref_type, ref_id
                """).query((rs, row) -> new RefImbalance(rs.getString("ref_type"),
                rs.getObject("ref_id", UUID.class), rs.getLong("debit"), rs.getLong("credit"))).list();

        return new LedgerBalance(debit, credit, debit == credit, imbalances, accounts);
    }

    private List<WalletMismatch> sellerMismatches() {
        return jdbc.sql("""
                select w.organizer_id as owner_id, w.balance, w.total_topups, w.total_sales, w.total_refunds,
                       t.top_up, t.sales, t.refunds, coalesce(t.last_after, w.balance) as last_after
                from seller_wallets w
                join lateral (
                    select coalesce(sum(case when x.type = 'TOP_UP'         then x.amount else 0 end), 0) as top_up,
                           coalesce(sum(case when x.type = 'PAYMENT_EARNED' then x.amount else 0 end), 0) as sales,
                           coalesce(sum(case when x.type = 'REFUND_DEBIT'   then x.amount else 0 end), 0) as refunds,
                           (select x2.balance_after from seller_wallet_transactions x2
                            where x2.organizer_id = w.organizer_id
                            order by x2.created_at desc, x2.id desc limit 1) as last_after
                    from seller_wallet_transactions x where x.organizer_id = w.organizer_id
                ) t on true
                """).query((rs, row) -> {
            long topUp = rs.getLong("top_up");
            long sales = rs.getLong("sales");
            long refunds = rs.getLong("refunds");
            return mismatch("SELLER", rs.getObject("owner_id", UUID.class), rs.getLong("balance"),
                    topUp + sales - refunds, rs.getLong("last_after"),
                    List.of(new AggregateMismatch("total_topups", rs.getLong("total_topups"), topUp),
                            new AggregateMismatch("total_sales", rs.getLong("total_sales"), sales),
                            new AggregateMismatch("total_refunds", rs.getLong("total_refunds"), refunds)));
        }).list().stream().filter(java.util.Objects::nonNull).toList();
    }

    private List<WalletMismatch> buyerMismatches() {
        return jdbc.sql("""
                select w.user_id as owner_id, w.balance, w.total_topups, w.total_spent, w.total_refunds,
                       t.top_up, t.spent, t.refunds, coalesce(t.last_after, w.balance) as last_after
                from buyer_wallets w
                join lateral (
                    select coalesce(sum(case when x.type = 'TOP_UP'        then x.amount else 0 end), 0) as top_up,
                           coalesce(sum(case when x.type = 'ORDER_PAYMENT' then x.amount else 0 end), 0) as spent,
                           coalesce(sum(case when x.type = 'REFUND_CREDIT' then x.amount else 0 end), 0) as refunds,
                           (select x2.balance_after from buyer_wallet_transactions x2
                            where x2.user_id = w.user_id
                            order by x2.created_at desc, x2.id desc limit 1) as last_after
                    from buyer_wallet_transactions x where x.user_id = w.user_id
                ) t on true
                """).query((rs, row) -> {
            long topUp = rs.getLong("top_up");
            long spent = rs.getLong("spent");
            long refunds = rs.getLong("refunds");
            return mismatch("BUYER", rs.getObject("owner_id", UUID.class), rs.getLong("balance"),
                    topUp + refunds - spent, rs.getLong("last_after"),
                    List.of(new AggregateMismatch("total_topups", rs.getLong("total_topups"), topUp),
                            new AggregateMismatch("total_spent", rs.getLong("total_spent"), spent),
                            new AggregateMismatch("total_refunds", rs.getLong("total_refunds"), refunds)));
        }).list().stream().filter(java.util.Objects::nonNull).toList();
    }

    private static WalletMismatch mismatch(String label, UUID ownerId, long stored, long derived,
                                          long lastAfter, List<AggregateMismatch> aggregates) {
        List<AggregateMismatch> off = aggregates.stream()
                .filter(a -> a.stored() != a.derived()).toList();
        if (stored == derived && stored == lastAfter && off.isEmpty()) return null;
        return new WalletMismatch(label, ownerId, stored, derived, lastAfter, off);
    }

    private Unledgered unledgered() {
        long seller = jdbc.sql("select coalesce(sum(balance), 0) from seller_wallets").query(Long.class).single();
        long buyer = jdbc.sql("select coalesce(sum(balance), 0) from buyer_wallets").query(Long.class).single();
        return new Unledgered(seller, buyer, seller + buyer);
    }
}
