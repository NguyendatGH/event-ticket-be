package com.example.demo.infrastructure.mail;

/** Dữ liệu dựng mail refund. amount tính bằng đồng (VND, không phần thập phân). */
public record RefundMailInfo(
        String organizerName,
        long orderCode,
        long amount,
        int ticketCount,
        String reasonCode,
        String customerName,
        String customerEmail,
        String refundsUrl
) {}
