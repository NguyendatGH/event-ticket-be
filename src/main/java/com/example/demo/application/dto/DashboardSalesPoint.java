package com.example.demo.application.dto;

import java.time.LocalDate;

/** Một bucket của GET /organizer/dashboard/sales: số vé và số đơn PAID trong bucket. */
public record DashboardSalesPoint(LocalDate date, long tickets, long orders) {}
