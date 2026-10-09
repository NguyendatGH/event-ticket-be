package com.example.demo.application.dto;

import com.example.demo.domain.organizer.Organizer;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record EventOrganizer(UUID id, String slug, String name, String logoUrl, boolean verified,
                             String description, Long eventsCount) {

    public static EventOrganizer summary(Organizer o) {
        return new EventOrganizer(o.getId(), o.getSlug(), o.getName(), o.getLogoUrl(), o.isVerified(), null, null);
    }

    public static EventOrganizer detail(Organizer o, long eventsCount) {
        return new EventOrganizer(o.getId(), o.getSlug(), o.getName(), o.getLogoUrl(), o.isVerified(), o.getDescription(), eventsCount);
    }
}
