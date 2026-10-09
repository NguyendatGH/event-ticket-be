package com.example.demo.support;

import com.example.demo.application.RefundService;
import com.example.demo.application.dto.RefundResponse;
import com.example.demo.domain.common.DomainException;
import com.example.demo.domain.payment.RefundStatusResult;
import com.example.demo.domain.refund.Refund;
import com.example.demo.infrastructure.persistence.RefundRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/mock-gateway")
@Profile("test")
@Tag(name = "Mock gateway", description = "Chỉ test")
@SecurityRequirements
public class MockRefundGatewayController {

    private final RefundRepository refunds;
    private final RefundService refundService;
    private final MockPaymentGateway mock;
    private final ObjectMapper json;
    private final Environment env;

    public MockRefundGatewayController(RefundRepository refunds, RefundService refundService, MockPaymentGateway mock,
                                       ObjectMapper json, Environment env) {
        this.refunds = refunds;
        this.refundService = refundService;
        this.mock = mock;
        this.json = json;
        this.env = env;
    }

    @PostMapping("/refunds/{refundId}/{action:succeed|fail|hold}")
    @Operation(summary = "Giả lập kết quả một lệnh chi",
            description = "succeed/fail/hold rồi gửi webhook ký HMAC. duplicate=true gửi 2 lần cùng eventId, badSignature=true ký sai")
    public RefundResponse act(@PathVariable UUID refundId, @PathVariable String action,
                             @RequestParam(defaultValue = "false") boolean duplicate,
                             @RequestParam(defaultValue = "false") boolean badSignature) {
        Refund refund = refunds.findById(refundId)
                .orElseThrow(() -> DomainException.notFound("REFUND_NOT_FOUND", "Không tìm thấy refund"));
        if (refund.getProviderRefundId() == null) {
            throw DomainException.conflict("REFUND_NOT_AT_PROVIDER",
                    "Refund đang " + refund.getStatus() + ", provider chưa nhận lệnh nên không có gì để chốt");
        }
        RefundStatusResult.Status next = switch (action) {
            case "succeed" -> RefundStatusResult.Status.SUCCEEDED;
            case "fail" -> RefundStatusResult.Status.FAILED;
            default -> RefundStatusResult.Status.ON_HOLD;
        };
        String failureCode = next == RefundStatusResult.Status.FAILED ? "BANK_REJECTED" : null;
        MockPaymentGateway.MockRefund r = mock.settleRefund(refund.getProviderRefundId(), next, failureCode);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("eventId", r.id + ":" + next.name());
        body.put("providerRefundId", r.id);
        body.put("referenceId", r.referenceId);
        body.put("state", next.name());
        body.put("failureCode", failureCode);
        body.put("failureReason", failureCode == null ? null : "mock: ngân hàng thụ hưởng từ chối");
        String raw = json.writeValueAsString(body);
        String signature = badSignature ? "deadbeef" : mock.sign(raw);
        for (int i = 0; i < (duplicate ? 2 : 1); i++) postWebhook(raw, signature);
        return refundService.get(refundId);
    }

    @PostMapping("/refunds/next-timeout")
    @Operation(summary = "Lần submitRefund kế tiếp: provider nhận lệnh nhưng response rớt")
    public Map<String, Object> timeoutNext() {
        mock.timeoutNextSubmit();
        return Map.of("armed", true);
    }

    @GetMapping("/balance")
    public Map<String, Object> balance() {
        return Map.of("balance", mock.getPayoutBalance(), "reserved", mock.getPayoutReserved());
    }

    @PostMapping("/balance")
    @Operation(summary = "Đặt số dư ví chi giả lập (mô phỏng nạp ví tay)")
    public Map<String, Object> setBalance(@RequestParam long amount) {
        mock.setPayoutBalance(amount);
        return balance();
    }

    private void postWebhook(String raw, String signature) {
        String port = env.getProperty("local.server.port", env.getProperty("server.port", "8080"));
        RestClient.builder().defaultStatusHandler(s -> true, (req, res) -> { }).build()
                .post().uri("http://localhost:" + port + "/webhooks/mock-gateway/refund")
                .contentType(MediaType.APPLICATION_JSON).header("X-Mock-Signature", signature)
                .body(raw).retrieve().toBodilessEntity();
    }
}
