package com.example.demo.application;

import com.example.demo.domain.common.DomainException;
import com.example.demo.domain.common.DomainException.FieldError;
import com.example.demo.domain.common.VietnamTime;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Tham số chung của dashboard: khoảng ngày [from, to] gồm cả hai đầu theo giờ VN,
 * bucket day|week|month và eventId tùy chọn. Tham số sai → 400 VALIDATION kèm {@code errors} theo field.
 */
public record DashboardRange(LocalDate from, LocalDate to, Interval interval, UUID eventId) {

    static final int DEFAULT_DAYS = 30;
    static final int MAX_DAYS = 366;

    /** Tên hằng trùng đơn vị của date_trunc trong Postgres, nên nối thẳng vào SQL được (không lấy từ input thô). */
    public enum Interval { day, week, month }

    public static DashboardRange parse(String from, String to, String interval, String eventId, LocalDate today) {
        List<FieldError> errors = new ArrayList<>();
        LocalDate t = date("to", to, today, errors);
        LocalDate f = date("from", from, (t != null ? t : today).minusDays(DEFAULT_DAYS - 1), errors);
        Interval iv = Interval.day;
        if (interval != null && !interval.isBlank()) {
            try {
                iv = Interval.valueOf(interval.trim().toLowerCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                errors.add(new FieldError("interval", "interval phải là day, week hoặc month"));
            }
        }
        UUID ev = null;
        if (eventId != null && !eventId.isBlank()) {
            try {
                ev = UUID.fromString(eventId.trim());
            } catch (IllegalArgumentException e) {
                errors.add(new FieldError("eventId", "eventId không hợp lệ"));
            }
        }
        if (f != null && t != null) {
            if (f.isAfter(t)) errors.add(new FieldError("from", "from phải trước hoặc bằng to"));
            else if (ChronoUnit.DAYS.between(f, t) + 1 > MAX_DAYS)
                errors.add(new FieldError("to", "Khoảng thời gian tối đa " + MAX_DAYS + " ngày"));
        }
        if (!errors.isEmpty()) {
            throw DomainException.badRequest("VALIDATION", "Tham số dashboard không hợp lệ").withErrors(errors);
        }
        return new DashboardRange(f, t, iv, ev);
    }

    private static LocalDate date(String field, String raw, LocalDate fallback, List<FieldError> errors) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException e) {
            errors.add(new FieldError(field, field + " phải có dạng YYYY-MM-DD"));
            return null;
        }
    }

    public long days() {
        return ChronoUnit.DAYS.between(from, to) + 1;
    }

    /** Khoảng liền trước cùng độ dài, kết thúc ngay trước {@code from}. */
    public DashboardRange previous() {
        return new DashboardRange(from.minusDays(days()), from.minusDays(1), interval, eventId);
    }

    /** Mốc bắt đầu (00:00 giờ VN của from) và mốc kết thúc loại trừ (00:00 giờ VN của to + 1). */
    public OffsetDateTime start() {
        return from.atStartOfDay(VietnamTime.ZONE).toOffsetDateTime();
    }

    public OffsetDateTime end() {
        return to.plusDays(1).atStartOfDay(VietnamTime.ZONE).toOffsetDateTime();
    }
}
