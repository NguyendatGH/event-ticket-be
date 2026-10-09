package com.example.demo.application;

import com.example.demo.application.dto.EventFacets;
import com.example.demo.application.dto.EventResponse;
import com.example.demo.application.dto.PageResponse;
import com.example.demo.domain.event.Event;

import java.time.LocalDate;
import java.util.List;

public interface EventService {

    record Filter(String q, String category, String city, LocalDate from, LocalDate to, String when,
                  Long priceMin, Long priceMax, String organizer, boolean includePast) {}

    PageResponse<EventResponse> list(Filter f, String sort, int page, int size);

    List<EventResponse> featured();

    List<EventResponse> upcoming(int limit);

    EventFacets facets();

    EventResponse get(String idOrSlug);

    List<EventResponse> related(String idOrSlug, int limit);

    List<EventResponse> moreFromOrganizer(String idOrSlug, int limit);

    PageResponse<EventResponse> byOrganizer(String idOrSlug, String scope, int page, int size);

    Event find(String idOrSlug);
}
