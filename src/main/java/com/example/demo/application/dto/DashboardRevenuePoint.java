package com.example.demo.application.dto;

import java.time.LocalDate;

/** Một bucket của GET /organizer/dashboard/revenue (subtotal, không gồm phí dịch vụ). */
public record DashboardRevenuePoint(LocalDate date, long revenue) {}
