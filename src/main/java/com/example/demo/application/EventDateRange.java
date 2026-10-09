package com.example.demo.application;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;

public record EventDateRange(LocalDate from, LocalDate to) {

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
