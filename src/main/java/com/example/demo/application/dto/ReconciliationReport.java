package com.example.demo.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ReconciliationReport(
        Instant checkedAt,
        boolean ok,

        @Schema(description = "Sổ bút toán kép: tổng nợ phải bằng tổng có")
        LedgerBalance ledger,

        @Schema(description = "Ví có số dư lệch với log giao dịch của chính nó")
        List<WalletMismatch> walletMismatches,

        @Schema(description = "Nghĩa vụ KHÔNG có bút toán nào trong sổ")
        Unledgered unledgered
) {

    public record LedgerBalance(long totalDebit, long totalCredit, boolean balanced,
                                @Schema(description = "Bút toán theo từng ref bị lệch nợ/có")
                                List<RefImbalance> refImbalances,
                                List<AccountTotal> accounts) {}

    public record AccountTotal(String account, long debit, long credit, long net) {}

    public record RefImbalance(String refType, UUID refId, long debit, long credit) {}

    public record WalletMismatch(String wallet, UUID ownerId, long storedBalance, long derivedBalance,
                                 long lastBalanceAfter, List<AggregateMismatch> aggregates) {}

    public record AggregateMismatch(String name, long stored, long derived) {}

    public record Unledgered(long sellerWalletTotal, long buyerWalletTotal, long total) {}
}
