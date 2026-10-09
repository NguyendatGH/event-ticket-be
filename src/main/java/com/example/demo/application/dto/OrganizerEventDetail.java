package com.example.demo.application.dto;

import com.example.demo.domain.event.Event;
import com.example.demo.domain.event.ScheduleItem;
import com.example.demo.domain.event.Venue;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OrganizerEventDetail(
        UUID id, String slug, String name, String category, String tagline, List<String> description,
        String coverImageUrl, String coverImageAlt, Instant startsAt, Instant endsAt, Venue venue,
        List<ScheduleItem> schedule, String status, boolean featured,
        Instant createdAt, Instant updatedAt, Instant publishedAt,
        List<Tier> tiers, Stats stats
) {
    public record Tier(UUID id, String name, String description, long price, int totalQuantity, int maxPerOrder,
                       long sold, long reserved, long available, long revenue) {}

    public record Stats(long ticketsSold, long ticketsTotal, long revenue, long ordersPaid, long ordersPending) {}

    public static OrganizerEventDetail from(Event e, String status, List<Tier> tiers, Stats stats) {
        return new OrganizerEventDetail(e.getId(), e.getSlug(), e.getName(), e.getCategory(), e.getTagline(),
                e.getDescription(), e.getCoverImageUrl(), e.getCoverImageAlt(), e.getStartsAt(), e.getEndsAt(),
                e.getVenue(), e.getSchedule(), status, e.isFeatured(),
                e.getCreatedAt(), e.getUpdatedAt(), e.getPublishedAt(), tiers, stats);
    }
}
