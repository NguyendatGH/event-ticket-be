package com.example.demo.commerce;

import com.example.demo.application.ReconciliationService;
import com.example.demo.application.dto.ReconciliationReport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "app.jwt.secret=test-secret-test-secret-test-secret-1234",
        "DB_URL=unused", "DB_USERNAME=unused", "DB_PASSWORD=unused"
})
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReconciliationTests {

    @TestConfiguration(proxyBeanMethods = false)
    static class Containers {
        @Bean
        @ServiceConnection
        PostgreSQLContainer postgres() {
            return new PostgreSQLContainer("postgres:16-alpine");
        }
    }

    @Autowired ReconciliationService reconciliation;
    @Autowired JdbcTemplate jdbc;

    @BeforeAll
    void seed() throws Exception {
        jdbc.execute(new ClassPathResource("seed/seed-dev.sql").getContentAsString(StandardCharsets.UTF_8));
    }

    @Test
    void cleanDatabaseReconciles() {
        ReconciliationReport report = reconciliation.check();

        assertTrue(report.ledger().balanced(), "Tổng nợ phải bằng tổng có");
        assertEquals(report.ledger().totalDebit(), report.ledger().totalCredit());
        assertTrue(report.ledger().refImbalances().isEmpty(), "Mỗi nghiệp vụ phải tự cân");
        assertTrue(report.walletMismatches().isEmpty(), "Không ví nào được lệch với log của nó");
        assertTrue(report.ok());
    }

    @Test
    void unbalancedLedgerEntryIsDetected() {
        UUID ref = UUID.randomUUID();
        jdbc.update("""
                insert into ledger_entries (id, account, direction, amount, ref_type, ref_id)
                values (?, 'BANK_COLLECTION', 'DEBIT', 500000, 'ORDER', ?)
                """, UUID.randomUUID(), ref);
        try {
            ReconciliationReport report = reconciliation.check();

            assertFalse(report.ledger().balanced(), "Một vế không có vế đối ứng thì sổ phải lệch");
            assertFalse(report.ok());
            assertTrue(report.ledger().refImbalances().stream().anyMatch(i -> i.refId().equals(ref)),
                    "Phải chỉ ra đúng ref bị lệch");
        } finally {
            jdbc.update("delete from ledger_entries where ref_id = ?", ref);
        }
        assertTrue(reconciliation.check().ok(), "Dọn xong phải sạch lại");
    }

    @Test
    void sellerWalletBalanceDriftingFromItsLogIsDetected() {
        UUID organizerId = jdbc.queryForObject("select id from organizers limit 1", UUID.class);
        jdbc.update("""
                insert into seller_wallets (organizer_id, balance, total_topups, total_sales, total_refunds,
                                            created_at, updated_at, version)
                values (?, 0, 0, 0, 0, now(), now(), 0)
                on conflict (organizer_id) do nothing
                """, organizerId);
        long before = jdbc.queryForObject("select balance from seller_wallets where organizer_id = ?",
                Long.class, organizerId);

        jdbc.update("update seller_wallets set balance = balance + 777000 where organizer_id = ?", organizerId);
        try {
            ReconciliationReport report = reconciliation.check();

            assertFalse(report.ok());
            var drift = report.walletMismatches().stream()
                    .filter(m -> m.ownerId().equals(organizerId)).findFirst().orElse(null);
            assertNotNull(drift, "Phải chỉ ra đúng ví bị lệch");
            assertEquals("SELLER", drift.wallet());
            assertEquals(before + 777000, drift.storedBalance());
            assertEquals(before, drift.derivedBalance(), "Số dư tính lại từ log không đổi");
        } finally {
            jdbc.update("update seller_wallets set balance = ? where organizer_id = ?", before, organizerId);
        }
        assertTrue(reconciliation.check().ok(), "Dọn xong phải sạch lại");
    }

    @Test
    void reportShowsHowMuchMoneySitsOutsideTheLedger() {
        ReconciliationReport report = reconciliation.check();

        long seller = report.unledgered().sellerWalletTotal();
        long buyer = report.unledgered().buyerWalletTotal();
        assertEquals(seller + buyer, report.unledgered().total());
        assertTrue(seller >= 0 && buyer >= 0);
    }
}
