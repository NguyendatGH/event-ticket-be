package com.example.demo.infrastructure.web;

import com.example.demo.application.RefundService;
import com.example.demo.application.dto.RefundInstruction;
import com.example.demo.application.dto.RefundResponse;
import com.example.demo.application.dto.ResolveRefundRequest;
import com.example.demo.domain.refund.RefundStatus;
import com.example.demo.infrastructure.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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

/**
 * Hoàn tiền phía ban tổ chức. Tiền nằm ở tài khoản nhận của BTC chứ không phải của sàn, nên refund tới
 * tài khoản khác tài khoản đã thanh toán do CHÍNH BTC duyệt. Quyền theo role ở SecurityConfig
 * (/api/v1/organizer/**), quyền trên từng refund kiểm trong service: refund của BTC khác trả 404.
 */
@RestController
@RequestMapping("/api/v1/organizer/refunds")
@Tag(name = "Organizer refunds", description = "Ban tổ chức duyệt và theo dõi hoàn tiền của sự kiện mình")
public class OrganizerRefundController {

    private final RefundService refunds;

    public OrganizerRefundController(RefundService refunds) {
        this.refunds = refunds;
    }

    @GetMapping
    @Operation(summary = "Refund của sự kiện mình", description = "Không truyền status thì trả tất cả, mới nhất trước. Lọc MANUAL_REVIEW để xem hàng cần duyệt.")
    public List<RefundResponse> list(@RequestParam(required = false) RefundStatus status) {
        return refunds.byOrganizer(CurrentUser.require(), status);
    }

    @GetMapping("/{id}/instruction")
    @Operation(summary = "Thông tin chuyển khoản tay",
            description = "Quét qrImageUrl bằng app ngân hàng, chuyển xong thì gọi resolve với outcome=SUCCEEDED. "
                    + "409 REFUND_DESTINATION_UNKNOWN nếu refund chưa có ngân hàng/số tài khoản hợp lệ.")
    public RefundInstruction instruction(@PathVariable UUID id) {
        return refunds.instructionOwned(CurrentUser.require(), id);
    }

    @PostMapping("/{id}/resolve")
    @Operation(summary = "Chốt một refund MANUAL_REVIEW",
            description = "SUCCEEDED/FAILED đi qua cùng đường với kết quả từ provider (hoàn kho đúng một lần). "
                    + "RETRY chỉ hợp lệ khi provider CHƯA nhận lệnh. Khác MANUAL_REVIEW → 409 REFUND_NOT_IN_REVIEW.")
    public RefundResponse resolve(@PathVariable UUID id, @Valid @RequestBody ResolveRefundRequest req) {
        return refunds.resolveOwned(CurrentUser.require(), id, req);
    }
}
