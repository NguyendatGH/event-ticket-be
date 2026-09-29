package com.example.demo.domain.payment;

/** Kết quả tạo link. {@code providerPaymentId} là khóa để hỏi trạng thái/hủy sau này (PayOS: paymentLinkId). */
public record PaymentLink(String providerPaymentId, String checkoutUrl, String qrCode) {}
