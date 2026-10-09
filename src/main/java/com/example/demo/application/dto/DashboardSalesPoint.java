package com.example.demo.application.dto;

import java.time.LocalDate;

public record DashboardSalesPoint(LocalDate date, long tickets, long orders) {}
