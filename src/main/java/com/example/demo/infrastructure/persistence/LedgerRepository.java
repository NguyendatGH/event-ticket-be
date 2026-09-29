package com.example.demo.infrastructure.persistence;

import com.example.demo.domain.ledger.LedgerAccount;
import com.example.demo.domain.ledger.LedgerDirection;
import com.example.demo.domain.ledger.LedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface LedgerRepository extends JpaRepository<LedgerEntry, UUID> {

    @Query("""
            select coalesce(sum(e.amount), 0) from LedgerEntry e
            where e.account = :account and e.direction = :direction
              and e.refType = :refType and e.refId = :refId
            """)
    long sumFor(@Param("account") LedgerAccount account, @Param("direction") LedgerDirection direction,
                @Param("refType") String refType, @Param("refId") UUID refId);

    /** Tổng tiền đã hoàn của một đơn: các vế DEBIT CUSTOMER_LIABILITY của mọi refund thuộc đơn đó. */
    @Query(value = """
            select coalesce(sum(l.amount), 0) from ledger_entries l
            join refunds r on r.id = l.ref_id
            where l.ref_type = 'REFUND' and l.account = 'CUSTOMER_LIABILITY' and l.direction = 'DEBIT'
              and r.order_id = :orderId
            """, nativeQuery = true)
    long sumRefundedByOrder(@Param("orderId") UUID orderId);
}
