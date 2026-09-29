package com.example.demo.application;

import com.example.demo.application.dto.DashboardRevenuePoint;
import com.example.demo.application.dto.DashboardSalesPoint;
import com.example.demo.application.dto.DashboardSummary;
import com.example.demo.application.dto.DashboardTopEvent;

import java.util.List;
import java.util.UUID;

public interface DashboardService {

    DashboardSummary summary(UUID userId, DashboardRange range);

    List<DashboardSalesPoint> sales(UUID userId, DashboardRange range);

    List<DashboardRevenuePoint> revenue(UUID userId, DashboardRange range);

    List<DashboardTopEvent> topEvents(UUID userId, DashboardRange range, int limit);
}
