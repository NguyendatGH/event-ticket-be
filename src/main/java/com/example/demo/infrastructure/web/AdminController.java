package com.example.demo.infrastructure.web;

import com.example.demo.application.AdminAuditService;
import com.example.demo.application.WalletService;
import com.example.demo.application.dto.OrderAudit;
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

    public AdminController(AdminAuditService auditService, WalletService wallet) {
        this.auditService = auditService;
        this.wallet = wallet;
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
