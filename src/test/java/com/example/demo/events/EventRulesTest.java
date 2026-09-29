package com.example.demo.events;

import com.example.demo.application.EventDateRange;
import com.example.demo.domain.common.DomainException;
import com.example.demo.domain.event.Event;
import com.example.demo.domain.event.EventStatus;
import com.example.demo.domain.event.TicketTier;
import com.example.demo.domain.event.Venue;
import com.example.demo.domain.inventory.Inventory;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Quy tắc thuần (không Spring, không DB) của sự kiện: khoảng ngày "when", slug, điều kiện publish, khóa giá/số lượng tier. */
class EventRulesTest {

    private static final Instant NOW = Instant.parse("2026-09-30T03:00:00Z");

    @Test
    void whenRangeFollowsVietnamWeek() {
        LocalDate wed = LocalDate.of(2026, 9, 30);
        assertEquals(new EventDateRange(wed, wed), EventDateRange.ofWhen("today", wed));
        assertEquals(new EventDateRange(LocalDate.of(2026, 10, 3), LocalDate.of(2026, 10, 4)), EventDateRange.ofWhen("weekend", wed));
        LocalDate sat = LocalDate.of(2026, 10, 3);
        assertEquals(new EventDateRange(sat, LocalDate.of(2026, 10, 4)), EventDateRange.ofWhen("weekend", sat));
        LocalDate sun = LocalDate.of(2026, 10, 4);
        assertEquals(new EventDateRange(sun, sun), EventDateRange.ofWhen("weekend", sun), "CN thì chỉ CN");
        assertEquals(new EventDateRange(wed, LocalDate.of(2026, 10, 4)), EventDateRange.ofWhen("week", wed));
        assertEquals(new EventDateRange(wed, wed), EventDateRange.ofWhen("month", wed), "30/9 là ngày cuối tháng");
        assertNull(EventDateRange.ofWhen("tomorrow", wed));
    }

    @Test
    void slugStripsVietnameseDiacritics() {
        assertEquals("dem-nhac-da-lat-2026", Event.slugify("Đêm nhạc Đà Lạt 2026!"));
        assertEquals("hamlet-ban-dung-moi", Event.slugify("  Hamlet — Bản dựng mới  "));
        assertEquals("su-kien", Event.slugify("!!!"));
    }

    @Test
    void emptyDraftListsEveryMissingPublishField() {
        Event e = Event.draft(UUID.randomUUID(), "x");
        e.updateDetails("X", null, null, List.of(), null, null, null, null, null, null);

        DomainException ex = assertThrows(DomainException.class, () -> e.publish(List.of(), NOW));

        assertEquals("EVENT_INCOMPLETE", ex.getCode());
        assertEquals(List.of("category", "description", "coverImageUrl", "startsAt", "venue.name", "venue.city", "tiers"),
                ex.getErrors().stream().map(DomainException.FieldError::field).toList());
        assertEquals(EventStatus.DRAFT, e.getStatus());
    }

    @Test
    void completeDraftPublishesOnceAndChecksTierQuantityAndDates() {
        Event e = Event.draft(UUID.randomUUID(), "x");
        e.updateDetails("X", "music", null, List.of("mô tả"), "https://img", null,
                NOW.plusSeconds(3600), NOW, new Venue("Nhà hát", "Hà Nội", null), List.of());
        TicketTier empty = new TicketTier(e.getId(), "GA", null, 100_000, 0, 4);

        DomainException ex = assertThrows(DomainException.class, () -> e.publish(List.of(empty), NOW));
        assertEquals(List.of("endsAt", "tiers[0].totalQuantity"), ex.getErrors().stream().map(DomainException.FieldError::field).toList());

        e.updateDetails("X", "music", null, List.of("mô tả"), "https://img", null,
                NOW.plusSeconds(3600), null, new Venue("Nhà hát", "Hà Nội", null), List.of());
        e.publish(List.of(new TicketTier(e.getId(), "GA", null, 100_000, 10, 4)), NOW);
        assertEquals(EventStatus.PUBLISHED, e.getStatus());
        assertEquals(NOW, e.getPublishedAt());
        assertEquals("EVENT_NOT_DRAFT", assertThrows(DomainException.class, () -> e.publish(List.of(), NOW)).getCode());
    }

    @Test
    void tierPriceLockedOnceTicketsAreHeldAndQuantityNotBelowHeld() {
        TicketTier t = new TicketTier(UUID.randomUUID(), "GA", null, 100_000, 10, 4);
        assertEquals("TIER_PRICE_LOCKED",
                assertThrows(DomainException.class, () -> t.update("GA", null, 90_000, 10, 4, 3)).getCode());
        t.update("GA 2", null, 100_000, 12, 4, 3);   // giữ giá: đổi tên/số lượng được
        assertEquals("GA 2", t.getName());

        Inventory inv = new Inventory(t.getId(), 7);   // total 10, đã bán/giữ 3
        assertEquals("TIER_QUANTITY_BELOW_SOLD", assertThrows(DomainException.class, () -> inv.adjust(-8)).getCode());
        inv.adjust(-7);                                // total 3 = đúng bằng số đã bán/giữ
        assertEquals(0, inv.getAvailable());
        inv.adjust(5);
        assertEquals(5, inv.getAvailable());
    }
}
