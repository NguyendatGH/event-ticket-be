package com.example.demo.application.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/** Body của POST /api/v1/orders/{orderId}/refunds. Hoàn theo vé; destination chỉ gửi khi muốn nhận ở tài khoản khác. */
public record CreateRefundRequest(
        @NotEmpty List<UUID> ticketIds,
        @Size(max = 500) String reason,
        @Valid Destination destination
) {
    /** Tài khoản nhận khác tài khoản đã trả -> refund vào MANUAL_REVIEW cho admin duyệt (chống chuyển tiền cho người lạ). */
    public record Destination(@NotBlank String bin, @NotBlank String accountNumber) {}
}
