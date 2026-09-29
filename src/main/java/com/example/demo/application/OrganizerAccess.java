package com.example.demo.application;

import com.example.demo.domain.event.Event;
import com.example.demo.domain.organizer.Organizer;

import java.util.UUID;

public interface OrganizerAccess {

    Organizer currentOrganizer(UUID userId);

    Event ownEvent(UUID userId, UUID eventId);

    Event ownEventLocked(UUID userId, UUID eventId);
}
