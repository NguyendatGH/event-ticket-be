package com.example.demo.infrastructure.web;

import com.example.demo.application.CheckoutService;
import com.example.demo.application.OrderQueries;
import com.example.demo.application.PaymentService;
import com.example.demo.application.dto.CreateOrderRequest;
import com.example.demo.application.dto.OrderResponse;
import com.example.demo.infrastructure.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
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

import java.util.UUID;


@RestController
@RequestMapping("/api/v1/orders")
@Tag(name = "Orders", description = "Checkout: giữ vé, tạo đơn chờ thanh toán, hủy đơn")
public class OrderController {

    private final CheckoutService checkoutService;
    private final OrderQueries orderQueries;
    private final PaymentService payments;

    public OrderController(CheckoutService checkoutService, OrderQueries orderQueries, PaymentService payments) {
        this.checkoutService = checkoutService;
        this.orderQueries = orderQueries;
        this.payments = payments;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Tạo đơn (giữ vé 15 phút)",
            description = "Trừ kho có khóa, tạo order PENDING_PAYMENT. Gửi lại cùng Idempotency-Key và cùng body thì nhận lại đúng đơn cũ; "
                    + "cùng key khác body → 422 IDEMPOTENCY_KEY_REUSED. Lỗi: 404 EVENT_NOT_FOUND, 409 EVENT_NOT_ON_SALE, "
                    + "400 ORDER_EMPTY/TIER_NOT_FOUND/QUANTITY_INVALID/QUANTITY_EXCEEDED, 409 TIER_SOLD_OUT, 502 PAYMENT_LINK_FAILED. Có Bearer hợp lệ thì gắn user vào đơn (vé cấp ra vào \"Vé của tôi\").")
    public OrderResponse create(@Parameter(description = "UUID do client sinh, mỗi lần bấm Thanh toán một key")
                                @RequestHeader("Idempotency-Key") String idempotencyKey,
                                @Valid @RequestBody CreateOrderRequest req) {
        return checkoutService.create(CurrentUser.require(), idempotencyKey, req);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Trạng thái đơn và vé đã cấp")
    public OrderResponse get(@PathVariable UUID id) {
        return orderQueries.getOwned(CurrentUser.require(), id);
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Hủy đơn chưa thanh toán",
            description = "Chỉ PENDING_PAYMENT, trả vé về kho. Khác → 409 ORDER_NOT_CANCELLABLE. "
                    + "Hỏi cổng thanh toán trước khi hủy: khách đã chuyển tiền mà webhook chưa về thì đơn được cấp vé "
                    + "và trả 409 ORDER_ALREADY_PAID thay vì hủy mất tiền.")
    public OrderResponse cancel(@PathVariable UUID id) {
        return payments.cancelOrder(CurrentUser.require(), id);
    }
}
