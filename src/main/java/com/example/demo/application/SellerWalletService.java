package com.example.demo.application;

import com.example.demo.application.dto.SellerWalletResponse;
import com.example.demo.application.dto.SellerWalletTopUpRequest;
import com.example.demo.domain.order.Order;
import com.example.demo.domain.refund.Refund;

import java.util.UUID;

public interface SellerWalletService {

    SellerWalletResponse mine(UUID userId);

    SellerWalletResponse topUp(UUID userId, String idempotencyKey, SellerWalletTopUpRequest request);

    void recordPayment(Order order);

    void recordRefund(Order order, Refund refund);
}
