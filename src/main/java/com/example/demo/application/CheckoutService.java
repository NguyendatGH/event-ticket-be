package com.example.demo.application;

import com.example.demo.application.dto.CreateOrderRequest;
import com.example.demo.application.dto.OrderResponse;
import com.example.demo.domain.payment.Payment;

import java.util.UUID;

public interface CheckoutService {

    OrderResponse create(UUID userId, String idempotencyKey, CreateOrderRequest req);

    OrderResponse cancel(UUID userId, UUID orderId);

    boolean expire(UUID orderId);

    void cancelLinkQuietly(Payment payment, String reason);
}
