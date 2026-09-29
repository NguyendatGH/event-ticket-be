package com.example.demo.application.dto;

import com.example.demo.domain.payment.BankBins;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

public record AppConfigResponse(
        @Schema(description = "Phí dịch vụ mỗi đơn, VND (app.checkout.fee). 0 = không thu.", example = "0") long checkoutFee,
        @Schema(description = "Google OAuth Client ID để FE dựng nút đăng nhập Google; null = chưa cấu hình, FE ẩn nút")
        String googleClientId,
        @Schema(description = "Ngân hàng cho dropdown \"chọn ngân hàng\" khi hoàn tiền về tài khoản khác; phổ biến trước")
        List<BankBins.Bank> banks
) {}
