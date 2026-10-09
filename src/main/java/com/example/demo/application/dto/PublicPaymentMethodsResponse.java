package com.example.demo.application.dto;

import com.example.demo.domain.payment.MerchantGateway;

import java.util.List;

public record PublicPaymentMethodsResponse(MerchantGateway gateway, List<String> paymentMethods) {}
