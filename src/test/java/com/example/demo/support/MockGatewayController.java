package com.example.demo.support;

import com.example.demo.application.OrderQueries;
import com.example.demo.application.PaymentService;
import com.example.demo.application.dto.OrderResponse;
import com.example.demo.domain.common.DomainException;
import com.example.demo.domain.order.Order;
import com.example.demo.domain.payment.Payment;
import com.example.demo.infrastructure.persistence.OrderRepository;
import com.example.demo.infrastructure.persistence.PaymentRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Nút bấm của test double (chỉ có ở src/test): mô phỏng khách trả tiền
 * rồi provider gọi webhook vào POST /webhooks/mock-gateway/payment của chính app này, qua HTTP thật.
 * Công cụ dev/test nên CỐ Ý đọc repository trực tiếp (ngoại lệ duy nhất của quy tắc controller → service → repository).
 */
@RestController
@RequestMapping("/mock-gateway/payments")
@Profile("test")
@Tag(name = "Mock gateway", description = "Chỉ dev/test. Cờ: duplicate=true gửi webhook 2 lần, badSignature=true ký sai, amount=<VND> trả thiếu")
@SecurityRequirements
public class MockGatewayController {

    private final OrderRepository orders;
    private final PaymentRepository payments;
    private final MockPaymentGateway mock;
    private final PaymentService paymentService;
    private final OrderQueries orderQueries;
    private final ObjectMapper json;
    private final Environment env;

    public MockGatewayController(OrderRepository orders, PaymentRepository payments, MockPaymentGateway mock,
                                 PaymentService paymentService, OrderQueries orderQueries, ObjectMapper json, Environment env) {
        this.orders = orders;
        this.payments = payments;
        this.mock = mock;
        this.paymentService = paymentService;
        this.orderQueries = orderQueries;
        this.json = json;
        this.env = env;
    }

    @PostMapping("/{orderId}/{action:succeed|fail|expire}")
    @Operation(summary = "Giả lập kết quả thanh toán của một đơn", description = "succeed/fail gửi webhook có chữ ký; expire đóng link và chạy logic hết hạn ngay")
    public OrderResponse act(@PathVariable UUID orderId, @PathVariable String action,
                             @RequestParam(defaultValue = "false") boolean duplicate,
                             @RequestParam(defaultValue = "false") boolean badSignature,
                             @RequestParam(required = false) Long amount,
                             @RequestParam(required = false) String payerBankBin) {
        Order order = orders.findById(orderId).orElseThrow(() -> DomainException.notFound("ORDER_NOT_FOUND", "Không tìm thấy đơn hàng"));
        Payment payment = payments.findFirstByOrderIdOrderByCreatedAtDesc(orderId)
                .orElseThrow(() -> DomainException.conflict("NO_PAYMENT", "Đơn chưa có link thanh toán"));
        String pid = payment.getProviderPaymentId();

        if (action.equals("expire")) {
            mock.markExpired(pid);
            paymentService.settleExpired(orderId);
            return orderQueries.get(orderId);
        }

        boolean success = action.equals("succeed");
        long paid = amount != null ? amount : order.getTotalAmount();
        String ref = "MOCK" + System.currentTimeMillis();
        Instant now = Instant.now();
        if (success) mock.markPaid(pid, paid, ref, now);   // trước khi gửi webhook: poll thấy PAID kể cả khi webhook bị từ chối

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("eventId", pid + ":" + ref);
        body.put("providerPaymentId", pid);
        body.put("orderCode", order.getOrderCode());
        body.put("success", success);
        body.put("amount", paid);
        body.put("transactionRef", ref);
        if (success) body.put("paidAt", now.toString());   // giao dịch thất bại không có thời điểm thanh toán
        body.put("payerBankBin", payerBankBin != null ? payerBankBin : "970422");
        body.put("payerAccountNumber", "0123456789012");
        String raw = json.writeValueAsString(body);
        String signature = badSignature ? "deadbeef" : mock.sign(raw);
        for (int i = 0; i < (duplicate ? 2 : 1); i++) postWebhook(raw, signature);
        return orderQueries.get(orderId);
    }

    private void postWebhook(String raw, String signature) {
        String port = env.getProperty("local.server.port", env.getProperty("server.port", "8080"));
        RestClient.builder().defaultStatusHandler(s -> true, (req, res) -> { }).build()   // 401 khi ký sai là kết quả mong đợi
                .post().uri("http://localhost:" + port + "/webhooks/mock-gateway/payment")
                .contentType(MediaType.APPLICATION_JSON).header("X-Mock-Signature", signature)
                .body(raw).retrieve().toBodilessEntity();
    }
}
