package com.example.demo.application;

import com.example.demo.application.dto.OrderResponse;
import com.example.demo.domain.payment.PaymentProvider;
import com.example.demo.domain.payment.WebhookProcessingResult;

import java.util.Map;
import java.util.UUID;

public interface PaymentService {

    WebhookProcessingResult handleWebhook(PaymentProvider provider, String rawBody, Map<String, String> headers);

    String settleExpired(UUID orderId);

    boolean reconcile(UUID orderId);

    OrderResponse cancelOrder(UUID userId, UUID orderId);
}
