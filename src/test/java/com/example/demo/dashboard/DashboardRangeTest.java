package com.example.demo.dashboard;

import com.example.demo.application.DashboardRange;
import com.example.demo.application.DashboardRange.Interval;
import com.example.demo.application.dto.DashboardSummary.Metric;
import com.example.demo.domain.common.DomainException;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.*;

class DashboardRangeTest {

    static final LocalDate TODAY = LocalDate.of(2026, 9, 27);

    @Test
    void defaultsToLast30DaysUntilTodayByDay() {
        DashboardRange r = DashboardRange.parse(null, "", null, null, TODAY);
        assertEquals(LocalDate.of(2026, 8, 29), r.from());
        assertEquals(TODAY, r.to());
        assertEquals(Interval.day, r.interval());
        assertEquals(30, r.days());
        assertNull(r.eventId());
    }

    @Test
    void previousPeriodHasSameLengthRightBeforeFrom() {
        DashboardRange p = DashboardRange.parse("2026-09-01", "2026-09-07", "WEEK", null, TODAY).previous();
        assertEquals(LocalDate.of(2026, 8, 25), p.from());
        assertEquals(LocalDate.of(2026, 8, 31), p.to());
    }

    @Test
    void boundsAreVietnamMidnightWithExclusiveEnd() {
        DashboardRange r = DashboardRange.parse("2026-09-01", "2026-09-01", null, null, TODAY);
        assertEquals(OffsetDateTime.parse("2026-09-01T00:00+07:00"), r.start());
        assertEquals(OffsetDateTime.parse("2026-09-02T00:00+07:00"), r.end());
    }

    @Test
    void rejectsBadInputWithFieldErrors() {
        DomainException e = assertThrows(DomainException.class,
                () -> DashboardRange.parse("2026-09-10", "2026-09-01", "year", "x", TODAY));
        assertEquals("VALIDATION", e.getCode());
        assertEquals(400, e.getStatus().value());
        assertEquals(3, e.getErrors().size(), String.valueOf(e.getErrors()));

        assertDoesNotThrow(() -> DashboardRange.parse("2025-09-27", "2026-09-27", null, null, TODAY), "366 ngày là trần");
        DomainException tooLong = assertThrows(DomainException.class,
                () -> DashboardRange.parse("2025-09-26", "2026-09-27", null, null, TODAY));
        assertEquals("to", tooLong.getErrors().getFirst().field());
        assertEquals("from", assertThrows(DomainException.class,
                () -> DashboardRange.parse("2026-9-1", null, null, null, TODAY)).getErrors().getFirst().field());
    }

    @Test
    void changePctRoundsToOneDecimalAndIsNullWithoutPrevious() {
        assertEquals(-23.9, Metric.of(25_100_000, 33_000_000).changePct());
        assertEquals(33.3, Metric.of(8, 6).changePct());
        assertEquals(-100.0, Metric.of(0, 5).changePct());
        assertNull(Metric.of(10, 0).changePct());
    }
}
