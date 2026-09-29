package com.example.demo.application;

import com.example.demo.application.dto.OrganizerEventDetail;
import com.example.demo.application.dto.OrganizerEventSummary;
import com.example.demo.application.dto.OrganizerOrderRow;
import com.example.demo.application.dto.PageResponse;
import com.example.demo.domain.event.Event;
import com.example.demo.domain.event.TicketTier;

import java.util.List;
import java.util.UUID;

public interface OrganizerEventQueries {

    PageResponse<OrganizerEventSummary> list(UUID userId, String status, String q, int page, int size);

    OrganizerEventDetail get(UUID userId, UUID eventId);

    PageResponse<OrganizerOrderRow> orders(UUID userId, UUID eventId, String status, String q, int page, int size);

    OrganizerEventDetail detail(Event e);

    List<TicketTier> tiersOf(UUID eventId);
}
