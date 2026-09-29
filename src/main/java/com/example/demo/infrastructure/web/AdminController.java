package com.example.demo.infrastructure.web;

import com.example.demo.application.AdminAuditService;
import com.example.demo.application.RefundService;
import com.example.demo.application.WalletService;
import com.example.demo.application.dto.OrderAudit;
import com.example.demo.application.dto.RefundInstruction;
import com.example.demo.application.dto.RefundResponse;
import com.example.demo.application.dto.ResolveRefundRequest;
import com.example.demo.domain.refund.RefundStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** /api/v1/admin: công cụ tra soát cho ADMIN. Gọi AdminAuditService. */
@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin", description = "Chỉ ADMIN")
public class AdminController {

    private final AdminAuditService auditService;
    private final RefundService refunds;
    private final WalletService wallet;

    public AdminController(AdminAuditService auditService, RefundService refunds, WalletService wallet) {
        this.auditService = auditService;
        this.refunds = refunds;
        this.wallet = wallet;
    }

    @GetMapping("/orders/{id}/audit")
    @Operation(summary = "Audit một đơn", description = "Đơn + webhook_events của payment + gateway_call_logs (mọi lần gọi provider và webhook đã verify)")
    public OrderAudit audit(@PathVariable UUID id) {
        return auditService.audit(id);
    }

    @GetMapping("/refunds")
    @Operation(summary = "Danh sách refund", description = "Không truyền status thì trả tất cả, mới nhất trước. Lọc MANUAL_REVIEW để xem hàng cần duyệt.")
    public List<RefundResponse> refunds(@RequestParam(required = false) RefundStatus status) {
        return refunds.list(status);
    }

    @PostMapping("/refunds/{id}/resolve")
    @Operation(summary = "Chốt một refund MANUAL_REVIEW",
            description = "SUCCEEDED/FAILED đi qua cùng đường với kết quả từ provider (hoàn kho đúng một lần). "
                    + "RETRY chỉ hợp lệ khi provider CHƯA nhận lệnh, nếu không → 409 REFUND_ALREADY_AT_PROVIDER. "
                    + "Khác MANUAL_REVIEW → 409 REFUND_NOT_IN_REVIEW.")
    public RefundResponse resolve(@PathVariable UUID id, @Valid @RequestBody ResolveRefundRequest req) {
        return refunds.resolve(id, req);
    }

    @GetMapping("/refunds/{id}/instruction")
    @Operation(summary = "Thông tin chuyển khoản tay cho một refund",
            description = "Dùng khi refund vào MANUAL_REVIEW vì không chi tự động được (chưa cấu hình kênh chi, "
                    + "ví hỏng, hoặc payout-enabled=false). Quét qrImageUrl bằng app ngân hàng, chuyển xong thì "
                    + "POST /admin/refunds/{id}/resolve với outcome=SUCCEEDED. 409 REFUND_DESTINATION_UNKNOWN nếu "
                    + "refund chưa có ngân hàng/số tài khoản hợp lệ.")
    public RefundInstruction instruction(@PathVariable UUID id) {
        return refunds.instruction(id);
    }

    @GetMapping("/refunds/wallet")
    @Operation(summary = "Ba con số của ví chi",
            description = "balance từ provider, committed = refund đang chạy, available = balance - committed, "
                    + "liability = tổng giá vé ACTIVE, coverage = available/liability. Nạp ví vẫn là thao tác tay trên dashboard.")
    public WalletService.Snapshot wallet() {
        return wallet.snapshot();
    }
}
