package com.example.demo.infrastructure.web;

import com.example.demo.application.PaymentService;
import com.example.demo.application.RefundService;
import com.example.demo.domain.payment.PaymentProvider;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/webhooks")
@Tag(name = "Webhooks", description = "Provider gọi vào; không dùng token")
@SecurityRequirements
public class WebhookController {

    private final PaymentService payments;
    private final RefundService refunds;

    public WebhookController(PaymentService payments, RefundService refunds) {
        this.payments = payments;
        this.refunds = refunds;
    }

    @PostMapping("/{provider:payos|mock-gateway}/payment")
    @Operation(summary = "Webhook thanh toán", description = "Sai chữ ký → 401. Còn lại luôn 200, body {result: PROCESSED|DUPLICATE|IGNORED}")
    public Map<String, Object> payment(@PathVariable String provider, @RequestBody String rawBody, HttpServletRequest request) {
        return Map.of("result", payments.handleWebhook(providerOf(provider), rawBody, headers(request)));
    }

    @PostMapping("/{provider:payos|mock-gateway}/refund")
    @Operation(summary = "Webhook kết quả hoàn tiền", description = "Sai chữ ký → 401. Còn lại 200, body {result: PROCESSED|DUPLICATE|IGNORED}")
    public Map<String, Object> refund(@PathVariable String provider, @RequestBody String rawBody, HttpServletRequest request) {
        return Map.of("result", refunds.handleRefundWebhook(providerOf(provider), rawBody, headers(request)));
    }

    private static PaymentProvider providerOf(String provider) {
        return provider.equals("payos") ? PaymentProvider.PAYOS : PaymentProvider.MOCK;
    }

    private static Map<String, String> headers(HttpServletRequest request) {
        Map<String, String> headers = new HashMap<>();
        for (String name : Collections.list(request.getHeaderNames())) {
            headers.put(name.toLowerCase(Locale.ROOT), request.getHeader(name));
        }
        return headers;
    }
}
