package com.example.demo.application.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

public record SellerWalletTopUpRequest(
        @Min(value = 1_000, message = "Số tiền nạp tối thiểu là 1.000đ")
        @Max(value = 1_000_000_000_000L, message = "Số tiền nạp vượt giới hạn mô phỏng")
        long amount,
        @Size(max = 500, message = "Ghi chú tối đa 500 ký tự")
        String note
) {}
