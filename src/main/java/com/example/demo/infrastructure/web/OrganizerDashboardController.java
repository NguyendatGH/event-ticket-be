package com.example.demo.infrastructure.web;

import com.example.demo.application.DashboardRange;
import com.example.demo.application.DashboardService;
import com.example.demo.application.dto.DashboardRevenuePoint;
import com.example.demo.application.dto.DashboardSalesPoint;
import com.example.demo.application.dto.DashboardSummary;
import com.example.demo.application.dto.DashboardTopEvent;
import com.example.demo.domain.common.DomainException;
import com.example.demo.domain.common.VietnamTime;
import com.example.demo.infrastructure.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1/organizer/dashboard")
@Tag(name = "Organizer dashboard", description = "Số liệu bán vé của organizer hiện tại")
public class OrganizerDashboardController {

    private static final int DEFAULT_TOP_EVENTS = 5;
    private static final int MAX_TOP_EVENTS = 50;

    private final DashboardService dashboard;

    public OrganizerDashboardController(DashboardService dashboard) {
        this.dashboard = dashboard;
    }

    @GetMapping("/summary")
    @Operation(summary = "Tổng quan", description = "Doanh thu, vé bán, số đơn so với khoảng liền trước; đếm sự kiện và vé")
    public DashboardSummary summary(@RequestParam(required = false) String from, @RequestParam(required = false) String to,
                                    @RequestParam(required = false) String interval,
                                    @RequestParam(required = false) String eventId) {
        return dashboard.summary(CurrentUser.require(), range(from, to, interval, eventId));
    }

    @GetMapping("/sales")
    @Operation(summary = "Vé và đơn theo bucket", description = "Đủ mọi bucket trong khoảng, bucket trống = 0")
    public List<DashboardSalesPoint> sales(@RequestParam(required = false) String from, @RequestParam(required = false) String to,
                                           @RequestParam(required = false) String interval,
                                           @RequestParam(required = false) String eventId) {
        return dashboard.sales(CurrentUser.require(), range(from, to, interval, eventId));
    }

    @GetMapping("/revenue")
    @Operation(summary = "Doanh thu theo bucket", description = "Doanh thu = subtotal (không gồm phí dịch vụ)")
    public List<DashboardRevenuePoint> revenue(@RequestParam(required = false) String from, @RequestParam(required = false) String to,
                                               @RequestParam(required = false) String interval,
                                               @RequestParam(required = false) String eventId) {
        return dashboard.revenue(CurrentUser.require(), range(from, to, interval, eventId));
    }

    @GetMapping("/top-events")
    @Operation(summary = "Sự kiện bán chạy", description = "Theo doanh thu trong khoảng, limit 1–50 (mặc định 5)")
    public List<DashboardTopEvent> topEvents(@RequestParam(required = false) String from, @RequestParam(required = false) String to,
                                             @RequestParam(required = false) String interval,
                                             @RequestParam(required = false) String eventId,
                                             @RequestParam(required = false) String limit) {
        return dashboard.topEvents(CurrentUser.require(), range(from, to, interval, eventId), limit(limit));
    }

    private static DashboardRange range(String from, String to, String interval, String eventId) {
        return DashboardRange.parse(from, to, interval, eventId, LocalDate.now(VietnamTime.ZONE));
    }

    private static int limit(String raw) {
        if (raw == null || raw.isBlank()) return DEFAULT_TOP_EVENTS;
        try {
            int n = Integer.parseInt(raw.trim());
            if (n >= 1 && n <= MAX_TOP_EVENTS) return n;
        } catch (NumberFormatException ignored) {
        }
        throw DomainException.badRequest("VALIDATION", "Tham số dashboard không hợp lệ")
                .withError("limit", "limit phải từ 1 đến 50");
    }
}
