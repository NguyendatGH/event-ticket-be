package com.example.demo.infrastructure.web;

import com.example.demo.application.AdminAuditService;
import com.example.demo.application.ReconciliationService;
import com.example.demo.application.WalletService;
import com.example.demo.application.dto.OrderAudit;
import com.example.demo.application.dto.ReconciliationReport;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin", description = "Chỉ ADMIN")
public class AdminController {

    private final AdminAuditService auditService;
    private final WalletService wallet;
    private final ReconciliationService reconciliation;

    public AdminController(AdminAuditService auditService, WalletService wallet,
                          ReconciliationService reconciliation) {
        this.auditService = auditService;
        this.wallet = wallet;
        this.reconciliation = reconciliation;
    }

    @GetMapping("/reconciliation")
    @Operation(summary = "Đối soát sổ với ví",
            description = "Chỉ ĐỌC. ok=false là có lệch, phải xem ngay. Kiểm ba thứ: tổng nợ/có toàn sổ, "
                    + "từng ref có tự cân, và mỗi ví có khớp log của chính nó (balance vs tổng tính lại vs "
                    + "balance_after vs các cột total_*). Kèm số tiền đang nằm NGOÀI sổ (ví BTC + ví người mua).")
    public ReconciliationReport reconciliation() {
        return reconciliation.check();
    }

    @GetMapping("/orders/{id}/audit")
    @Operation(summary = "Audit một đơn", description = "Đơn + webhook_events của payment + gateway_call_logs (mọi lần gọi provider và webhook đã verify)")
    public OrderAudit audit(@PathVariable UUID id) {
        return auditService.audit(id);
    }

    @GetMapping("/refunds/wallet")
    @Operation(summary = "Ba con số của ví chi",
            description = "Chỉ ĐỌC, để trực ca theo dõi kênh chi dùng chung. balance từ provider, "
                    + "committed = refund đang chạy, available = balance - committed, liability = tổng giá vé ACTIVE, "
                    + "coverage = available/liability. Nạp ví vẫn là thao tác tay trên dashboard.")
    public WalletService.Snapshot wallet() {
        return wallet.snapshot();
    }
}
