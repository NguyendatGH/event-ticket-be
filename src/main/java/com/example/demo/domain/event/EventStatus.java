package com.example.demo.domain.event;


import java.util.List;

public enum EventStatus {
    DRAFT,
    PUBLISHED,
    UPCOMING,
    CANCELLED;

    public static final List<EventStatus> LISTED = List.of(PUBLISHED, UPCOMING);

    public boolean isListed() {
        return this == PUBLISHED || this == UPCOMING;
    }
}
