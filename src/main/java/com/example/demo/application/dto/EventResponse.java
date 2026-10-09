package com.example.demo.application.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.example.demo.domain.event.Event;
import com.example.demo.domain.event.EventStatus;
import com.example.demo.domain.event.ScheduleItem;
import com.example.demo.domain.event.TicketTier;
import com.example.demo.domain.event.Venue;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record EventResponse(
        UUID id,
        String slug,
        String name,
        String category,
        Instant startsAt,
        Instant endsAt,
        Venue venue,
        String coverImageUrl,
        String coverImageAlt,
        long priceFrom,
        @Schema(description = "PUBLISHED | UPCOMING | SOLD_OUT (PUBLISHED nhưng mọi tier hết vé) | ENDED (đã qua giờ kết thúc) | DRAFT | CANCELLED") String status,
        boolean featured,
        String tagline,
        List<String> description,
        List<ScheduleItem> schedule,
        @Schema(nullable = true, description = "null khi sự kiện chưa gắn BTC") EventOrganizer organizer,
        List<TierResponse> tiers
) {
    public static EventResponse of(Event e, List<TicketTier> tiers, Map<UUID, Integer> availableByTier,
                                   EventOrganizer organizer, boolean detail) {
        List<TierResponse> tierDtos = tiers.stream()
                .map(t -> new TierResponse(t.getId(), t.getName(), t.getDescription(), t.getPrice(),
                        availableByTier.getOrDefault(t.getId(), 0), t.getMaxPerOrder()))
                .toList();
        boolean soldOut = e.getStatus() == EventStatus.PUBLISHED && !tierDtos.isEmpty()
                && tierDtos.stream().allMatch(t -> t.available() == 0);
        return new EventResponse(
                e.getId(), e.getSlug(), e.getName(), e.getCategory(), e.getStartsAt(), e.getEndsAt(), e.getVenue(),
                e.getCoverImageUrl(), e.getCoverImageAlt(),
                tiers.stream().mapToLong(TicketTier::getPrice).min().orElse(0),
                e.displayStatus(soldOut, Instant.now()),
                e.isFeatured(), e.getTagline(),
                detail ? e.getDescription() : null,
                detail ? e.getSchedule() : null,
                organizer,
                detail ? tierDtos : null);
    }
}
