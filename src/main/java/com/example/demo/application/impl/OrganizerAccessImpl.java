package com.example.demo.application.impl;

import com.example.demo.application.OrganizerAccess;
import com.example.demo.domain.common.DomainException;
import com.example.demo.domain.event.Event;
import com.example.demo.domain.organizer.Organizer;
import com.example.demo.infrastructure.persistence.EventRepository;
import com.example.demo.infrastructure.persistence.OrganizerRepository;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class OrganizerAccessImpl implements OrganizerAccess {

    private final OrganizerRepository organizers;
    private final EventRepository events;

    public OrganizerAccessImpl(OrganizerRepository organizers, EventRepository events) {
        this.organizers = organizers;
        this.events = events;
    }

    @Override
    public Organizer currentOrganizer(UUID userId) {
        return organizers.findByUserId(userId)
                .orElseThrow(() -> DomainException.notFound("ORGANIZER_NOT_FOUND", "Tài khoản chưa có hồ sơ ban tổ chức"));
    }

    @Override
    public Event ownEvent(UUID userId, UUID eventId) {
        UUID organizerId = currentOrganizer(userId).getId();
        return events.findById(eventId)
                .filter(e -> organizerId.equals(e.getOrganizerId()))
                .orElseThrow(() -> eventNotFound(eventId));
    }

    @Override
    public Event ownEventLocked(UUID userId, UUID eventId) {
        UUID organizerId = currentOrganizer(userId).getId();
        return events.findWithLockById(eventId)
                .filter(e -> organizerId.equals(e.getOrganizerId()))
                .orElseThrow(() -> eventNotFound(eventId));
    }

    private static DomainException eventNotFound(UUID eventId) {
        return DomainException.notFound("EVENT_NOT_FOUND", "Không tìm thấy sự kiện " + eventId);
    }
}
