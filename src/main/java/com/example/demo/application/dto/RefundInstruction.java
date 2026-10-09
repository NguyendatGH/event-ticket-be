package com.example.demo.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Hướng dẫn chuyển khoản tay cho một refund")
public record RefundInstruction(
        @Schema(example = "https://img.vietqr.io/image/970436-1234567890-compact2.png?amount=200000") String qrImageUrl,
        @Schema(description = "Chuỗi EMVCo VietQR, dùng khi tự render QR") String qrPayload,
        String bankBin,
        String bankName,
        String accountNumber,
        long amount,
        @Schema(description = "Nội dung chuyển khoản, tối đa 25 ký tự") String content) {
}
