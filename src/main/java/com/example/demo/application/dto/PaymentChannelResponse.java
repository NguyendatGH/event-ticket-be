package com.example.demo.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

public record PaymentChannelResponse(
        UUID id,
        String bankCode,
        String bankName,
        String bankBin,
        @Schema(description = "Phương thức BTC bật cho kênh này")
        List<String> paymentMethods,
        @Schema(description = "Phương thức khách TRẢ ĐƯỢC lúc này (bật + ngân hàng còn nhận); thiếu so với paymentMethods = có gì đó hỏng phía cổng")
        List<String> routableMethods,
        String accountName,
        String maskedAccountNumber,
        @Schema(description = "Kênh chính: tài khoản của nó cũng nhận tiền cổng đang giữ (bán trước khi có kênh)")
        boolean primary
) {}
