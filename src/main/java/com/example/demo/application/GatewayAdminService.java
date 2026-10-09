package com.example.demo.application;

import java.util.Map;

public interface GatewayAdminService {
    com.example.demo.application.dto.GatewayOrganizersResponse listOrganizers();
    com.example.demo.application.dto.GatewayOrganizerRow provision(java.util.UUID organizerId);
    Object listMerchants();
    Object merchant(String merNo);
    Object createMerchant(Map<String, Object> body);
    Object updateMerchant(String merNo, Map<String, Object> body);
    Object rotateCredential(String merNo);
    Object terminals(String merNo);
    Object createTerminal(String merNo, Map<String, Object> body, boolean activate);
    Object terminal(String terminalId);
    Object updateTerminal(String terminalId, String suffix, Map<String, Object> body);
    Object setTerminalStatus(String terminalId, String status);
    Object setActiveTerminal(String merNo, String terminalId);
    Object acquirerConfigs(String merNo);
    Object addAcquirerConfig(String merNo, Map<String, Object> body);
    Object routingProfiles();
    Object routingProfile(String code);
    Object createRoutingProfile(Map<String, Object> body);
    Object addRoutingRule(String code, Map<String, Object> body);
    Object acquirers();
    Object createAcquirer(Map<String, Object> body);
    Object updateAcquirer(String code, Map<String, Object> body);
}
