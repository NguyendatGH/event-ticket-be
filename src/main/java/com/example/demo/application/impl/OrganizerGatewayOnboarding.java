package com.example.demo.application.impl;

import com.example.demo.application.GatewayProvisioningService;
import com.example.demo.domain.organizer.OrganizerCreated;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class OrganizerGatewayOnboarding {

    private final GatewayProvisioningService provisioning;

    public OrganizerGatewayOnboarding(GatewayProvisioningService provisioning) {
        this.provisioning = provisioning;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOrganizerCreated(OrganizerCreated event) {
        provisioning.onboardNewOrganizer(event.organizerId());
    }
}
