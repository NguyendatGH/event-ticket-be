package com.example.demo.infrastructure.mail;

public record CustomerRefundMailInfo(
        String customerName,
        long orderCode,
        long amount,
        int ticketCount,
        String orderUrl
) {}
