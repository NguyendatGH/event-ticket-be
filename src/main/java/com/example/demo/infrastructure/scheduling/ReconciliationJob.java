package com.example.demo.infrastructure.scheduling;

import com.example.demo.application.ReconciliationService;
import com.example.demo.application.dto.ReconciliationReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public class ReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationJob.class);

    private final ReconciliationService reconciliation;

    public ReconciliationJob(ReconciliationService reconciliation) {
        this.reconciliation = reconciliation;
    }

    @Scheduled(fixedDelayString = "${app.reconciliation.interval:PT10M}", initialDelayString = "PT2M")
    public void run() {
        try {
            ReconciliationReport report = reconciliation.check();
            if (report.ok()) {
                log.info("Đối soát sạch: nợ {} = có {}, {} đồng nằm ngoài sổ (ví BTC + ví người mua)",
                        report.ledger().totalDebit(), report.ledger().totalCredit(), report.unledgered().total());
                return;
            }
            log.error("ĐỐI SOÁT LỆCH — nợ {} vs có {}, {} nghiệp vụ lệch, {} ví lệch",
                    report.ledger().totalDebit(), report.ledger().totalCredit(),
                    report.ledger().refImbalances().size(), report.walletMismatches().size());
            report.ledger().refImbalances().forEach(i ->
                    log.error("  sổ lệch {} {}: nợ {} vs có {}", i.refType(), i.refId(), i.debit(), i.credit()));
            report.walletMismatches().forEach(m ->
                    log.error("  ví {} {}: balance {} vs tính lại {} vs balance_after {}; cột tổng lệch {}",
                            m.wallet(), m.ownerId(), m.storedBalance(), m.derivedBalance(),
                            m.lastBalanceAfter(), m.aggregates()));
        } catch (RuntimeException ex) {
            log.error("Job đối soát chạy lỗi", ex);
        }
    }
}
