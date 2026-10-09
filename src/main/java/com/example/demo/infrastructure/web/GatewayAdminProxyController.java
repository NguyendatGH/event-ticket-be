package com.example.demo.infrastructure.web;

import com.example.demo.application.GatewayAdminService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/gateway")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Gateway admin", description = "Proxy quản trị BankSim Gateway (chỉ ADMIN)")
public class GatewayAdminProxyController {

    private final GatewayAdminService service;

    public GatewayAdminProxyController(GatewayAdminService service) {
        this.service = service;
    }

    @GetMapping("/organizers")
    public Object organizers() { return service.listOrganizers(); }

    @PostMapping("/organizers/{organizerId}/provision")
    public Object provision(@PathVariable java.util.UUID organizerId) { return service.provision(organizerId); }

    @GetMapping("/merchants")
    public Object merchants() { return service.listMerchants(); }

    @GetMapping("/merchants/{merNo}")
    public Object merchant(@PathVariable String merNo) { return service.merchant(merNo); }

    @PatchMapping("/merchants/{merNo}")
    public Object updateMerchant(@PathVariable String merNo, @RequestBody Map<String, Object> body) {
        return service.updateMerchant(merNo, body);
    }

    @PostMapping("/merchants/{merNo}/credentials/rotate")
    public Object rotate(@PathVariable String merNo) { return service.rotateCredential(merNo); }

    @GetMapping("/merchants/{merNo}/terminals")
    public Object terminals(@PathVariable String merNo) { return service.terminals(merNo); }

    @PostMapping("/merchants/{merNo}/terminals")
    public Object createTerminal(@PathVariable String merNo, @RequestParam(defaultValue = "false") boolean activate,
                                 @RequestBody Map<String, Object> body) {
        return service.createTerminal(merNo, body, activate);
    }

    @GetMapping("/terminals/{terminalId}")
    public Object terminal(@PathVariable String terminalId) { return service.terminal(terminalId); }

    @PatchMapping("/terminals/{terminalId}")
    public Object terminalStatus(@PathVariable String terminalId, @RequestBody Map<String, Object> body) {
        return service.setTerminalStatus(terminalId, String.valueOf(body.get("status")));
    }

    @PutMapping("/merchants/{merNo}/active-terminal")
    public Object activeTerminal(@PathVariable String merNo, @RequestBody Map<String, Object> body) {
        return service.setActiveTerminal(merNo, body.get("terminalId") == null ? null : String.valueOf(body.get("terminalId")));
    }

    @PutMapping("/terminals/{terminalId}/configuration")
    public Object configure(@PathVariable String terminalId, @RequestBody Map<String, Object> body) {
        return service.updateTerminal(terminalId, "/configuration", body);
    }

    @PutMapping("/terminals/{terminalId}/payment-methods")
    public Object methods(@PathVariable String terminalId, @RequestBody Map<String, Object> body) {
        return service.updateTerminal(terminalId, "/payment-methods", body);
    }

    @PutMapping("/terminals/{terminalId}/three-ds-policy")
    public Object threeDs(@PathVariable String terminalId, @RequestBody Map<String, Object> body) {
        return service.updateTerminal(terminalId, "/three-ds-policy", body);
    }

    @PutMapping("/terminals/{terminalId}/routing-profile")
    public Object routing(@PathVariable String terminalId, @RequestBody Map<String, Object> body) {
        return service.updateTerminal(terminalId, "/routing-profile", body);
    }

    @GetMapping("/merchants/{merNo}/acquirer-configs")
    public Object acquirerConfigs(@PathVariable String merNo) { return service.acquirerConfigs(merNo); }

    @PostMapping("/merchants/{merNo}/acquirer-configs")
    public Object addAcquirerConfig(@PathVariable String merNo, @RequestBody Map<String, Object> body) {
        return service.addAcquirerConfig(merNo, body);
    }

    @GetMapping("/routing-profiles")
    public Object routingProfiles() { return service.routingProfiles(); }

    @GetMapping("/routing-profiles/{code}")
    public Object routingProfile(@PathVariable String code) { return service.routingProfile(code); }

    @PostMapping("/routing-profiles")
    public Object createRoutingProfile(@RequestBody Map<String, Object> body) { return service.createRoutingProfile(body); }

    @PostMapping("/routing-profiles/{code}/rules")
    public Object addRoutingRule(@PathVariable String code, @RequestBody Map<String, Object> body) {
        return service.addRoutingRule(code, body);
    }

    @GetMapping("/acquirers")
    public Object acquirers() { return service.acquirers(); }

    @PostMapping("/acquirers")
    public Object createAcquirer(@RequestBody Map<String, Object> body) { return service.createAcquirer(body); }

    @PatchMapping("/acquirers/{code}")
    public Object updateAcquirer(@PathVariable String code, @RequestBody Map<String, Object> body) {
        return service.updateAcquirer(code, body);
    }
}
