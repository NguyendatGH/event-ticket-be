package com.example.demo.application;

import com.example.demo.domain.gateway.OrganizerGatewayBinding;

import java.util.UUID;

public interface GatewayProvisioningService {

    OrganizerGatewayBinding ensureProvisioned(UUID organizerId, String provider);

    boolean enabled();

    void onboardNewOrganizer(UUID organizerId);

    int provisionMissing();
}
