package com.example.demo.application.dto;

import java.util.List;

public record GatewayOrganizersResponse(boolean gatewayReachable, List<GatewayOrganizerRow> organizers) {}
