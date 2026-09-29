package com.example.demo.application;

import com.example.demo.application.dto.EventUpsertRequest;
import com.example.demo.application.dto.OrganizerEventDetail;

import java.util.UUID;

public interface OrganizerEventService {

    OrganizerEventDetail create(UUID userId, EventUpsertRequest req);

    OrganizerEventDetail update(UUID userId, UUID eventId, EventUpsertRequest req);

    OrganizerEventDetail publish(UUID userId, UUID eventId);

    void delete(UUID userId, UUID eventId);
}
