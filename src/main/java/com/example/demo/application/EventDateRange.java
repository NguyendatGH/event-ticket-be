package com.example.demo.application;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;

/**
 * Khoảng ngày [from, to] theo giờ VN, gồm cả hai đầu. Dùng cho tham số {@code when} của GET /events.
 * Hàm thuần (không đọc đồng hồ, không DB) nên test trực tiếp được, xem EventRulesTest.
 */
public record EventDateRange(LocalDate from, LocalDate to) {

    /**
     * today = hôm nay; weekend = thứ 7 + CN của tuần hiện tại (hôm nay là CN thì chỉ CN);
     * week = hôm nay → CN tuần này; month = hôm nay → cuối tháng. Giá trị lạ → null.
     */
    public static EventDateRange ofWhen(String when, LocalDate today) {
        LocalDate sunday = today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY));
        return switch (when) {
            case "today" -> new EventDateRange(today, today);
            case "weekend" -> today.getDayOfWeek() == DayOfWeek.SUNDAY
                    ? new EventDateRange(today, today)
                    : new EventDateRange(today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY)), sunday);
            case "week" -> new EventDateRange(today, sunday);
            case "month" -> new EventDateRange(today, today.with(TemporalAdjusters.lastDayOfMonth()));
            default -> null;
        };
    }
}
