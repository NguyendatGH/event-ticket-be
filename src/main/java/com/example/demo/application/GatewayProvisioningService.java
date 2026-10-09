package com.example.demo.application;

import com.example.demo.domain.gateway.OrganizerGatewayBinding;
import com.example.demo.infrastructure.gateway.banksim.BankSimAdminClient.SettlementAccount;

import java.util.UUID;

public interface GatewayProvisioningService {

    OrganizerGatewayBinding ensureProvisioned(UUID organizerId, String provider);

    void syncSettlement(UUID organizerId, SettlementAccount account);

    boolean enabled();

    void onboardNewOrganizer(UUID organizerId);

    int provisionMissing();

    int syncPendingSettlements();
}
