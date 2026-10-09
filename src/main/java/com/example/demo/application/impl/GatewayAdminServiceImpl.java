package com.example.demo.application.impl;

import com.example.demo.application.GatewayAdminService;
import com.example.demo.application.GatewayProvisioningService;
import com.example.demo.application.dto.GatewayOrganizerRow;
import com.example.demo.domain.common.DomainException;
import com.example.demo.application.dto.GatewayOrganizersResponse;
import com.example.demo.domain.gateway.OrganizerGatewayBinding;
import com.example.demo.infrastructure.gateway.banksim.BankSimAdminClient;
import com.example.demo.infrastructure.gateway.banksim.GatewaySecretCipher;
import com.example.demo.infrastructure.persistence.OrganizerGatewayBindingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class GatewayAdminServiceImpl implements GatewayAdminService {

    private static final Logger log = LoggerFactory.getLogger(GatewayAdminServiceImpl.class);

    private final BankSimAdminClient admin;
    private final OrganizerGatewayBindingRepository bindings;
    private final GatewaySecretCipher cipher;
    private final GatewayProvisioningService provisioning;
    private final JdbcClient jdbc;

    public GatewayAdminServiceImpl(BankSimAdminClient admin, OrganizerGatewayBindingRepository bindings,
                                   GatewaySecretCipher cipher, GatewayProvisioningService provisioning,
                                   JdbcClient jdbc) {
        this.admin = admin;
        this.bindings = bindings;
        this.cipher = cipher;
        this.provisioning = provisioning;
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public GatewayOrganizersResponse listOrganizers() {
        Map<String, Long> terminalCount;
        boolean reachable = true;
        try {
            terminalCount = admin.listMerchants().stream()
                    .collect(Collectors.toMap(BankSimAdminClient.MerchantListItem::merNo,
                            BankSimAdminClient.MerchantListItem::terminalCount, (a, b) -> a));
        } catch (RuntimeException ex) {
            log.warn("Không lấy được danh sách merchant của gateway: {}", ex.toString());
            terminalCount = Map.of();
            reachable = false;
        }
        Map<String, Long> counts = terminalCount;
        boolean gatewayUp = reachable;

        List<GatewayOrganizerRow> rows = jdbc.sql("""
                        SELECT o.id, o.name, o.contact_email,
                               a.bank_name, a.account_number, b.gateway_merchant_no, b.gateway_terminal_id,
                               b.status, b.provisioning_error
                        FROM organizers o
                        LEFT JOIN organizer_bank_accounts a ON a.organizer_id = o.id AND a.is_default
                        LEFT JOIN organizer_gateway_bindings b
                               ON b.organizer_id = o.id AND b.provider = 'BANKSIM'
                        ORDER BY o.name
                        """)
                .query((rs, n) -> {
                    String number = rs.getString("account_number");
                    String payout = number == null ? null
                            : rs.getString("bank_name") + " ••••" + number.substring(Math.max(0, number.length() - 4));
                    String merNo = rs.getString("gateway_merchant_no");
                    String status = rs.getString("status");
                    return new GatewayOrganizerRow(rs.getObject("id", UUID.class), rs.getString("name"),
                            rs.getString("contact_email"), payout, merNo, rs.getString("gateway_terminal_id"),
                            status, rs.getString("provisioning_error"),
                            merNo == null ? null : (gatewayUp ? counts.getOrDefault(merNo, 0L) : null),
                            !"ACTIVE".equals(status));
                })
                .list();
        return new GatewayOrganizersResponse(gatewayUp, rows);
    }

    @Override
    public GatewayOrganizerRow provision(UUID organizerId) {
        provisioning.ensureProvisioned(organizerId, GatewayProvisioningServiceImpl.BANKSIM);
        return listOrganizers().organizers().stream().filter(r -> r.organizerId().equals(organizerId))
                .findFirst().orElseThrow();
    }

    @Override public Object listMerchants() { return admin.listMerchants(); }
    @Override
    @Transactional(readOnly = true)
    public Object merchant(String merNo) {
        Object result = admin.merchant(merNo);
        if (!(result instanceof Map<?, ?> map)) return result;
        Map<String, Object> merged = new java.util.LinkedHashMap<>();
        map.forEach((k, v) -> merged.put(String.valueOf(k), v));
        merged.put("activeTerminalId", boundTerminal(merNo));
        merged.put("channelTerminalIds", channelTerminals(merNo));
        return merged;
    }

    private List<String> channelTerminals(String merNo) {
        return jdbc.sql("""
                        SELECT c.gateway_terminal_id FROM organizer_payment_channels c
                        JOIN organizer_gateway_bindings b ON b.organizer_id = c.organizer_id AND b.provider = 'BANKSIM'
                        WHERE b.gateway_merchant_no = ? AND c.status = 'ACTIVE' ORDER BY c.opened_at
                        """)
                .param(merNo).query(String.class).list();
    }

    private String boundTerminal(String merNo) {
        return bindings.findByGatewayMerchantNoAndProvider(merNo, GatewayProvisioningServiceImpl.BANKSIM)
                .map(OrganizerGatewayBinding::getGatewayTerminalId).orElse(null);
    }

    @Override public Object terminals(String merNo) { return admin.terminals(merNo); }
    @Override public Object terminal(String terminalId) { return admin.terminal(terminalId); }
    @Override public Object acquirerConfigs(String merNo) { return admin.acquirerConfigs(merNo); }
    @Override public Object routingProfiles() { return admin.routingProfiles(); }
    @Override public Object routingProfile(String code) { return admin.routingProfile(code); }

    @Override
    public Object createRoutingProfile(Map<String, Object> body) {
        Object r = admin.createRoutingProfile(body);
        log.info("[ADMIN][ROUTING_PROFILE_CREATED] actor=ADMIN code={}", body.get("code"));
        return r;
    }

    @Override
    public Object addRoutingRule(String code, Map<String, Object> body) {
        Object r = admin.addRoutingRule(code, body);
        log.info("[ADMIN][ROUTING_RULE_ADDED] actor=ADMIN profile={} method={} acquirer={} priority={}",
                code, body.get("paymentMethod"), body.get("acquirerCode"), body.get("priority"));
        return r;
    }
    @Override public Object acquirers() { return admin.acquirers(); }

    @Override
    public Object createAcquirer(Map<String, Object> body) {
        Object result = admin.createAcquirer(body);
        log.info("[ADMIN][ACQUIRER_CREATED] actor=ADMIN code={} methods={} threeDs={}",
                body.get("code"), body.get("paymentMethods"), body.get("threeDsSupported"));
        return result;
    }

    @Override
    public Object updateAcquirer(String code, Map<String, Object> body) {
        Object result = admin.updateAcquirer(code, body);
        log.info("[ADMIN][ACQUIRER_UPDATED] actor=ADMIN code={} changes={}", code, body.keySet());
        return result;
    }

    @Override
    public Object createMerchant(Map<String, Object> body) {
        Object result = admin.createMerchantRaw(body);
        log.info("[ADMIN][MERCHANT_CREATED] actor=ADMIN name={}", body.get("name"));
        return result;
    }

    @Override
    public Object updateMerchant(String merNo, Map<String, Object> body) {
        Object result = admin.updateMerchant(merNo, body);
        log.info("[ADMIN][MERCHANT_UPDATED] actor=ADMIN merchantNo={} status={}", merNo, body.get("status"));
        return result;
    }

    @Override
    @Transactional
    public Object rotateCredential(String merNo) {
        Object result = admin.rotateCredential(merNo);
        String newSecret = result instanceof Map<?, ?> m && m.get("merchantSecret") != null
                ? m.get("merchantSecret").toString() : null;
        OrganizerGatewayBinding binding = bindings
                .findByGatewayMerchantNoAndProvider(merNo, GatewayProvisioningServiceImpl.BANKSIM).orElse(null);
        if (binding != null && newSecret != null) {
            binding.replaceSecret(cipher.encrypt(newSecret));
            bindings.saveAndFlush(binding);
            log.info("[ADMIN][MERCHANT_CREDENTIAL_ROTATED] actor=ADMIN merchantNo={} binding đã đồng bộ", merNo);
        } else if (binding != null) {
            binding.fail("Rotate không trả về merchantSecret, binding đang giữ secret cũ");
            bindings.saveAndFlush(binding);
            log.error("[ADMIN][MERCHANT_CREDENTIAL_ROTATED] actor=ADMIN merchantNo={} KHÔNG lấy được secret mới"
                    + " -> binding FAILED, organizer sẽ không thanh toán được", merNo);
        } else {
            log.info("[ADMIN][MERCHANT_CREDENTIAL_ROTATED] actor=ADMIN merchantNo={} (không gắn organizer nào)", merNo);
        }
        return result;
    }

    @Override
    public Object createTerminal(String merNo, Map<String, Object> body, boolean activate) {
        Object result = admin.createTerminalRaw(merNo, body);
        log.info("[ADMIN][TERMINAL_CREATED] actor=ADMIN merchantNo={} channel={} activate={}",
                merNo, body.get("channel"), activate);
        if (!(result instanceof Map<?, ?> created)) return result;
        Map<String, Object> out = new LinkedHashMap<>();
        created.forEach((k, v) -> out.put(String.valueOf(k), v));
        out.put("usedByEncore", bindCreated(merNo, created, activate));
        return out;
    }

    private boolean bindCreated(String merNo, Map<?, ?> created, boolean activate) {
        if (!"ACTIVE".equals(String.valueOf(created.get("status")))) return false;
        OrganizerGatewayBinding binding = bindings
                .findByGatewayMerchantNoAndProvider(merNo, GatewayProvisioningServiceImpl.BANKSIM).orElse(null);
        if (binding == null) return false;
        String previous = binding.getGatewayTerminalId();
        if (!activate && previous != null && previousStillUsable(previous)) return false;

        String terminalId = String.valueOf(created.get("terminalId"));
        binding.useTerminal(terminalId);
        bindings.saveAndFlush(binding);
        log.info("[ADMIN][BINDING_TERMINAL_CHANGED] actor=ADMIN merchantNo={} terminalId={} (trước đó {}{})",
                merNo, terminalId, previous, activate ? "" : " không còn dùng được");
        return true;
    }

    private boolean previousStillUsable(String terminalId) {
        try {
            return terminalUsable(terminalId);
        } catch (DomainException ex) {
            log.warn("[ADMIN] không kiểm được terminal {} ({}), giữ nguyên binding", terminalId, ex.getCode());
            return true;
        }
    }

    private boolean terminalUsable(String terminalId) {
        try {
            return admin.terminal(terminalId) instanceof Map<?, ?> t && "ACTIVE".equals(String.valueOf(t.get("status")));
        } catch (DomainException ex) {
            if (ex.getStatus() == HttpStatus.NOT_FOUND) return false;
            throw ex;
        }
    }

    @Override
    public Object updateTerminal(String terminalId, String suffix, Map<String, Object> body) {
        Object result = admin.updateTerminalRaw(terminalId, suffix, body);
        log.info("[ADMIN][{}] actor=ADMIN terminalId={}", switch (suffix) {
            case "/payment-methods" -> "TERMINAL_PAYMENT_METHODS_UPDATED";
            case "/three-ds-policy" -> "TERMINAL_3DS_POLICY_UPDATED";
            case "/routing-profile" -> "TERMINAL_ROUTING_UPDATED";
            case "/configuration" -> "TERMINAL_CONFIGURED";
            default -> "TERMINAL_UPDATED";
        }, terminalId);
        return result;
    }

    @Override
    @Transactional
    public Object setTerminalStatus(String terminalId, String status) {
        if (!"ACTIVE".equals(status) && !"INACTIVE".equals(status))
            throw DomainException.badRequest("INVALID_STATUS", "status phải là ACTIVE hoặc INACTIVE");
        if ("INACTIVE".equals(status)) {
            bindings.findByGatewayTerminalIdAndProvider(terminalId, GatewayProvisioningServiceImpl.BANKSIM)
                    .ifPresent(b -> {
                        throw DomainException.conflict("TERMINAL_IN_USE",
                                "Ban tổ chức đang dùng terminal này để thu tiền. Chọn terminal khác cho họ rồi mới tắt.");
                    });
            boolean channel = jdbc.sql("SELECT count(*) FROM organizer_payment_channels WHERE gateway_terminal_id = ? AND status = 'ACTIVE'")
                    .param(terminalId).query(Long.class).single() > 0;
            if (channel)
                throw DomainException.conflict("TERMINAL_IN_USE",
                        "Terminal này là một kênh nhận tiền ban tổ chức đang mở. Ban tổ chức phải xóa kênh đó trước.");
        }
        Object result = admin.patchTerminalRaw(terminalId, Map.of("status", status));
        log.info("[ADMIN][TERMINAL_STATUS_CHANGED] actor=ADMIN terminalId={} status={}", terminalId, status);
        return result;
    }

    @Override
    @Transactional
    public Object setActiveTerminal(String merNo, String terminalId) {
        if (terminalId == null || terminalId.isBlank())
            throw DomainException.badRequest("TERMINAL_REQUIRED", "Thiếu terminalId");
        OrganizerGatewayBinding binding = bindings
                .findByGatewayMerchantNoAndProvider(merNo, GatewayProvisioningServiceImpl.BANKSIM)
                .orElseThrow(() -> DomainException.conflict("GATEWAY_MERCHANT_NOT_PROVISIONED",
                        "Merchant " + merNo + " chưa gắn với ban tổ chức nào"));
        if (!(admin.terminal(terminalId) instanceof Map<?, ?> t))
            throw DomainException.conflict("GATEWAY_ADMIN_REJECTED", "Gateway trả về dữ liệu terminal không đọc được");
        if (!merNo.equals(String.valueOf(t.get("merNo"))))
            throw DomainException.forbidden("TERMINAL_NOT_OWNED", "Terminal này thuộc merchant khác");
        if (!"ACTIVE".equals(String.valueOf(t.get("status"))))
            throw DomainException.conflict("TERMINAL_INACTIVE", "Terminal đang tắt, bật lên trước đã");

        String previous = binding.getGatewayTerminalId();
        binding.useTerminal(terminalId);
        bindings.saveAndFlush(binding);
        log.info("[ADMIN][BINDING_TERMINAL_CHANGED] actor=ADMIN merchantNo={} terminalId={} (trước đó {})",
                merNo, terminalId, previous);
        return Map.of("merNo", merNo, "activeTerminalId", terminalId);
    }

    @Override
    public Object addAcquirerConfig(String merNo, Map<String, Object> body) {
        Object result = admin.addAcquirerConfig(merNo, body);
        log.info("[ADMIN][ACQUIRER_CONFIG_ADDED] actor=ADMIN merchantNo={} acquirer={}", merNo, body.get("acquirerCode"));
        return result;
    }
}
