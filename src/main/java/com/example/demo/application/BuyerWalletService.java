package com.example.demo.application;

import com.example.demo.application.dto.BuyerWalletResponse;
import com.example.demo.application.dto.BuyerWalletTopUpRequest;
import com.example.demo.domain.order.Order;
import com.example.demo.domain.refund.Refund;

import java.util.UUID;

public interface BuyerWalletService {

    BuyerWalletResponse mine(UUID userId);

    BuyerWalletResponse topUp(UUID userId, String idempotencyKey, BuyerWalletTopUpRequest request);

    void charge(UUID userId, Order order);

    void recordRefund(Order order, Refund refund);
}
