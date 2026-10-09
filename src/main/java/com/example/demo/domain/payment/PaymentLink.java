package com.example.demo.domain.payment;

public record PaymentLink(String providerPaymentId, String checkoutUrl, String qrCode,
                          String gatewayMerchantNo, String gatewayTerminalId) {

    public PaymentLink(String providerPaymentId, String checkoutUrl, String qrCode) {
        this(providerPaymentId, checkoutUrl, qrCode, null, null);
    }
}
