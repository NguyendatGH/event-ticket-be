package com.example.demo.infrastructure.mail;

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
