package com.example.demo.application.dto;

import java.util.UUID;

public record GatewayOrganizerRow(
        UUID organizerId,
        String organizerName,
        String contactEmail,
        String payoutAccount,
        String gatewayMerchantNo,
        String gatewayTerminalId,
        String bindingStatus,
        String provisioningError,
        Long terminalCount,
        boolean needsProvisioning
) {}
