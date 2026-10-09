package com.example.demo.application.dto;

import com.example.demo.domain.event.Event;
import com.example.demo.domain.event.Venue;

import java.time.Instant;
import java.util.UUID;

public record OrganizerEventSummary(UUID id, String slug, String name, String category, String status,
                                    Instant startsAt, Instant endsAt, Venue venue, String coverImageUrl,
                                    long ticketsSold, long ticketsTotal, long revenue,
                                    Instant createdAt, Instant updatedAt, Instant publishedAt) {

    public static OrganizerEventSummary from(Event e, String status, long ticketsSold, long ticketsTotal, long revenue) {
        return new OrganizerEventSummary(e.getId(), e.getSlug(), e.getName(), e.getCategory(), status,
                e.getStartsAt(), e.getEndsAt(), e.getVenue(), e.getCoverImageUrl(),
                ticketsSold, ticketsTotal, revenue,
                e.getCreatedAt(), e.getUpdatedAt(), e.getPublishedAt());
    }
}
