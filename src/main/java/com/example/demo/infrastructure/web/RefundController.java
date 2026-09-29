package com.example.demo.infrastructure.web;

import com.example.demo.application.RefundService;
import com.example.demo.application.dto.CreateRefundRequest;
import com.example.demo.application.dto.RefundResponse;
import com.example.demo.domain.refund.RefundInitiator;
import com.example.demo.infrastructure.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * /api/v1: yêu cầu hoàn tiền theo vé. Trả 202 vì tiền đi bất đồng bộ — client đọc GET /refunds/{id} để biết kết quả.
 * PHẢI đăng nhập và phải là chủ đơn: hoàn tiền là tiền RA + hủy vé, không thể chỉ dựa vào orderId khó đoán
 * như endpoint chỉ đọc. Không phải chủ đơn -> 404 (không tiết lộ đơn có tồn tại).
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Refunds", description = "Hoàn tiền theo vé, chạy bằng lệnh chi ở cổng thanh toán")
public class RefundController {

    private final RefundService refunds;

    public RefundController(RefundService refunds) {
        this.refunds = refunds;
    }

    @PostMapping("/orders/{orderId}/refunds")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Yêu cầu hoàn tiền cho một số vé của đơn",
            description = "202 kèm refund đang chạy; tiền đi bất đồng bộ nên đọc GET /refunds/{id} để biết kết quả. "
                    + "Không gửi destination = hoàn về đúng tài khoản đã trả (tự chạy). Gửi destination = tài khoản khác "
                    + "→ vào MANUAL_REVIEW chờ admin duyệt. Lỗi: 404 ORDER_NOT_FOUND, 409 ORDER_NOT_REFUNDABLE, "
                    + "409 REFUND_DEADLINE_PASSED, 409 TICKET_NOT_REFUNDABLE, 400 TICKET_NOT_IN_ORDER, "
                    + "409 PAYER_ACCOUNT_UNKNOWN, 400 DESTINATION_IS_MERCHANT.")
    public RefundResponse create(@PathVariable UUID orderId,
                                 @Parameter(description = "UUID do client sinh, mỗi lần bấm Hoàn tiền một key")
                                 @RequestHeader("Idempotency-Key") String idempotencyKey,
                                 @Valid @RequestBody CreateRefundRequest req) {
        return refunds.create(CurrentUser.require(), orderId, idempotencyKey, req, RefundInitiator.USER);
    }

    @GetMapping("/orders/{orderId}/refunds")
    @Operation(summary = "Các lần hoàn tiền của một đơn")
    public List<RefundResponse> byOrder(@PathVariable UUID orderId) {
        return refunds.byOrderOwned(CurrentUser.require(), orderId);
    }

    @GetMapping("/refunds/{id}")
    @Operation(summary = "Trạng thái một yêu cầu hoàn tiền")
    public RefundResponse get(@PathVariable UUID id) {
        return refunds.getOwned(CurrentUser.require(), id);
    }
}
