package com.example.demo.application.dto;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/** GET /organizer/dashboard/summary. Ba chỉ số so với khoảng liền trước; events/tickets là số hiện tại (không theo khoảng ngày). */
public record DashboardSummary(LocalDate from, LocalDate to, Metric revenue, Metric ticketsSold, Metric orders,
                               EventCounts events, TicketCounts tickets) {

    /** {@code changePct} làm tròn 1 chữ số thập phân, null khi previous = 0 (không chia được). */
    public record Metric(long value, long previous, Double changePct) {
        public static Metric of(long value, long previous) {
            Double pct = previous == 0 ? null : BigDecimal.valueOf(value - previous).multiply(BigDecimal.valueOf(100))
                    .divide(BigDecimal.valueOf(previous), 1, RoundingMode.HALF_UP).doubleValue();
            return new Metric(value, previous, pct);
        }
    }

    public record EventCounts(long total, long draft, long published, long upcoming, long ended, long cancelled) {}

    public record TicketCounts(long total, long sold, long available) {}
}
