package com.example.demo.application;

import com.example.demo.application.dto.OrganizerProfileRequest;
import com.example.demo.application.dto.OrganizerResponse;
import com.example.demo.domain.organizer.Organizer;

import java.util.List;
import java.util.UUID;

public interface OrganizerService {

    Organizer create(UUID userId, OrganizerProfileRequest req);

    OrganizerResponse mine(UUID userId);

    OrganizerResponse updateMine(UUID userId, OrganizerProfileRequest req);

    OrganizerResponse publicProfile(String idOrSlug);

    List<OrganizerResponse> featured(int size);
}
