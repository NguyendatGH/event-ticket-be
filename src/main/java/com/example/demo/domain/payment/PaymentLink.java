package com.example.demo.domain.payment;

public record PaymentLink(String providerPaymentId, String checkoutUrl, String qrCode) {}
