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
    @Operation(summary = "Chốt hoặc hủy một yêu cầu hoàn tiền",
            description = "SUCCEEDED/FAILED đi qua cùng đường với kết quả từ provider (hoàn kho đúng một lần). "
                    + "RETRY chỉ hợp lệ khi provider CHƯA nhận lệnh. Ba outcome này chỉ nhận refund đang "
                    + "MANUAL_REVIEW, khác → 409 REFUND_NOT_IN_REVIEW.\n\n"
                    + "CANCELLED = hủy yêu cầu hoàn tiền: hủy được từ MANUAL_REVIEW hoặc AWAITING_FUNDS, "
                    + "BẮT BUỘC có note (lý do hủy), thiếu → 400 REFUND_CANCEL_NOTE_REQUIRED. "
                    + "Trạng thái khác → 409 REFUND_NOT_CANCELLABLE (REQUESTED: lệnh có thể đang bay ở provider; "
                    + "PROCESSING: tiền đang chuyển). Lệnh đã nằm ở provider (providerRefundId != null, ví dụ "
                    + "MANUAL_REVIEW do PROCESSING_TIMEOUT hay ON_HOLD) → 409 REFUND_ALREADY_AT_PROVIDER: "
                    + "tra dashboard PayOS rồi chốt SUCCEEDED/FAILED, không hủy. "
                    + "Hủy xong refund ở FAILED + failureCode=CANCELLED_BY_ORGANIZER, vé về ACTIVE nên khách "
                    + "giữ vé và xin hoàn lại được; kho không đổi vì vé chưa từng nhả.")
    public RefundResponse resolve(@PathVariable UUID id, @Valid @RequestBody ResolveRefundRequest req) {
        return refunds.resolveOwned(CurrentUser.require(), id, req);
    }
}
