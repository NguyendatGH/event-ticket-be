package com.example.demo.application.dto;

import com.example.demo.domain.payment.BankBins;
import com.example.demo.domain.payment.Payment;
import com.example.demo.domain.payment.PaymentProvider;
import com.example.demo.domain.payment.PaymentStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

public record PaymentInfo(PaymentProvider provider, PaymentStatus status, String checkoutUrl, String paymentLinkId,
                          String qrCode, String transactionRef, Instant paidAt,
                          @Schema(nullable = true, description = "Số tài khoản đã thanh toán, để điền sẵn khi hoàn vé")
                          String payerAccountNumber,
                          @Schema(nullable = true, description = "BIN Napas 6 số; null khi PayOS trả mã CITAD hoặc khách trả bằng ví")
                          String payerBankBin,
                          @Schema(nullable = true, description = "Tên ngân hàng tương ứng payerBankBin")
                          String payerBankName) {

    static PaymentInfo from(Payment p) {
        return p == null ? null : new PaymentInfo(p.getProvider(), p.getStatus(), p.getCheckoutUrl(),
                p.getPaymentLinkId(), p.getQrCode(), p.getProviderTransactionRef(), p.getPaidAt(),
                p.getPayerAccountNumber(), p.getPayerBankBin(), BankBins.nameOf(p.getPayerBankBin()));
    }
}
