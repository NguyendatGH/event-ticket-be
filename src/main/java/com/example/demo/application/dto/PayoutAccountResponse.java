package com.example.demo.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

public record PayoutAccountResponse(
        String bankBin,
        String bankName,
        String accountName,
        String maskedAccountNumber,
        Instant updatedAt,
        @Schema(description = "true = khách đã thanh toán được cho sự kiện của BTC này (có hay chưa có tài khoản)")
        boolean acceptingPayments,
        @Schema(description = "true = tài khoản này đã được cổng thanh toán ghi nhận; false = đang cập nhật")
        boolean payoutSynced,
        @Schema(description = "Phương thức khách trả được lúc này (gộp mọi kênh; chưa có kênh = cấu hình mặc định); null = không biết")
        List<String> paymentMethods,
        @Schema(description = "Ngân hàng BTC chọn làm kênh được, kèm phương thức từng ngân hàng hỗ trợ; null = cổng không có kênh (PayOS)")
        List<PayoutBankOption> banks,
        @Schema(description = "Các kênh nhận tiền, kênh chính đứng đầu; null = cổng không có kênh (PayOS)")
        List<PaymentChannelResponse> channels
) {}
