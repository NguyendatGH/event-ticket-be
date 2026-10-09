package com.example.demo.application.impl;

import com.example.demo.application.GatewayProvisioningService;
import com.example.demo.application.PaymentGatewayRegistry;
import com.example.demo.domain.common.DomainException;
import com.example.demo.domain.gateway.OrganizerGatewayBinding;
import com.example.demo.domain.organizer.Organizer;
import com.example.demo.domain.payment.MerchantGateway;
import com.example.demo.domain.payment.OrganizerBankAccount;
import com.example.demo.infrastructure.gateway.banksim.BankSimAdminClient;
import com.example.demo.infrastructure.gateway.banksim.BankSimAdminClient.SettlementAccount;
import com.example.demo.infrastructure.gateway.banksim.GatewaySecretCipher;
import com.example.demo.infrastructure.persistence.OrganizerBankAccountRepository;
import com.example.demo.infrastructure.persistence.OrganizerGatewayBindingRepository;
import com.example.demo.infrastructure.persistence.OrganizerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class GatewayProvisioningServiceImpl implements GatewayProvisioningService {

    public static final String BANKSIM = "BANKSIM";
    private static final Logger log = LoggerFactory.getLogger(GatewayProvisioningServiceImpl.class);
    private static final Duration PENDING_STALE_AFTER = Duration.ofMinutes(2);

    private final OrganizerGatewayBindingRepository bindings;
    private final OrganizerRepository organizers;
    private final BankSimAdminClient admin;
    private final GatewaySecretCipher cipher;
    private final TransactionTemplate tx;
    private final OrganizerBankAccountRepository payoutAccounts;
    private final PaymentGatewayRegistry gateways;
    private final MerchantGateway configuredDefault;
    private final JdbcClient jdbc;

    public GatewayProvisioningServiceImpl(OrganizerGatewayBindingRepository bindings, OrganizerRepository organizers,
                                          BankSimAdminClient admin, GatewaySecretCipher cipher, TransactionTemplate tx,
                                          OrganizerBankAccountRepository payoutAccounts, PaymentGatewayRegistry gateways,
                                          @Value("${app.payment.default-gateway:PAYOS}") MerchantGateway configuredDefault,
                                          JdbcClient jdbc) {
        this.bindings = bindings;
        this.organizers = organizers;
        this.admin = admin;
        this.cipher = cipher;
        this.tx = tx;
        this.payoutAccounts = payoutAccounts;
        this.gateways = gateways;
        this.configuredDefault = configuredDefault;
        this.jdbc = jdbc;
    }

    @Override
    public boolean enabled() {
        return admin.isConfigured() && gateways.platformGateway(configuredDefault) == MerchantGateway.BANKSIM;
    }

    @Override
    public void onboardNewOrganizer(UUID organizerId) {
        if (!enabled()) return;
        try {
            ensureProvisioned(organizerId, BANKSIM);
        } catch (RuntimeException ex) {
            log.warn("[GATEWAY-PROVISION] organizer={} chưa cấp phát được lúc đăng ký ({}) -> job sẽ thử lại",
                    organizerId, ex.toString());
        }
    }

    @Override
    public int provisionMissing() {
        if (!enabled()) return 0;
        List<UUID> missing = jdbc.sql("""
                        SELECT o.id FROM organizers o
                        LEFT JOIN organizer_gateway_bindings b ON b.organizer_id = o.id AND b.provider = 'BANKSIM'
                        WHERE b.id IS NULL OR b.status = 'FAILED' OR (b.status = 'PENDING' AND b.updated_at < :staleBefore)
                        ORDER BY o.created_at
                        """)
                .param("staleBefore", Timestamp.from(Instant.now().minus(PENDING_STALE_AFTER)))
                .query((rs, n) -> rs.getObject(1, UUID.class)).list();
        int provisioned = 0;
        for (UUID organizerId : missing) {
            try {
                ensureProvisioned(organizerId, BANKSIM);
                provisioned++;
            } catch (RuntimeException ex) {
                log.warn("[GATEWAY-PROVISION] organizer={} vẫn chưa cấp phát được: {}", organizerId, ex.toString());
            }
        }
        return provisioned;
    }

    @Override
    public int syncPendingSettlements() {
        if (!enabled()) return 0;
        int synced = 0;
        for (OrganizerBankAccount a : payoutAccounts.findByIsDefaultTrueAndGatewaySyncedAtIsNull()) {
            if (!pushable(a)) continue;
            try {
                syncSettlement(a.getOrganizerId(), new SettlementAccount(a.getBankBin(), a.getAccountNumber(), a.getAccountName()));
                synced++;
            } catch (RuntimeException ex) {
                log.warn("[GATEWAY-PROVISION] organizer={} chưa đẩy được tài khoản nhận tiền: {}", a.getOrganizerId(), ex.toString());
            }
        }
        return synced;
    }

    @Override
    public OrganizerGatewayBinding ensureProvisioned(UUID organizerId, String provider) {
        SettlementAccount stored = payoutAccounts.findFirstByOrganizerIdAndIsDefaultTrue(organizerId)
                .filter(GatewayProvisioningServiceImpl::pushable)
                .map(a -> new SettlementAccount(a.getBankBin(), a.getAccountNumber(), a.getAccountName()))
                .orElse(null);
        return provision(organizerId, provider, stored);
    }

    private static boolean pushable(OrganizerBankAccount account) {
        return account.getBankBin() != null && account.getAccountNumber() != null && !account.getAccountNumber().isBlank();
    }

    @Override
    public void syncSettlement(UUID organizerId, SettlementAccount account) {
        if (!admin.isConfigured()) {
            log.warn("[GATEWAY-PROVISION] organizer={} chưa cấu hình BANK_SIMULATE_ADMIN_KEY -> chưa đẩy được tài khoản"
                    + " nhận tiền lên gateway, BTC chưa nhận thanh toán được", organizerId);
            return;
        }
        OrganizerGatewayBinding existing = bindings.findByOrganizerIdAndProvider(organizerId, BANKSIM).orElse(null);
        if (existing != null && existing.isUsable() && stillOnGateway(existing)) {
            admin.updateSettlement(existing.getGatewayMerchantNo(), account);
            markSynced(organizerId, account);
            log.info("[GATEWAY-PROVISION] organizer={} merchantNo={} đổi tài khoản nhận tiền -> {} {}",
                    organizerId, existing.getGatewayMerchantNo(), account.bankBin(), mask(account.accountNumber()));
            return;
        }
        provision(organizerId, BANKSIM, account);
    }

    private OrganizerGatewayBinding provision(UUID organizerId, String provider, SettlementAccount settlement) {
        if (!BANKSIM.equals(provider))
            throw DomainException.badRequest("PROVIDER_NOT_PROVISIONABLE", "Chỉ BankSim cần cấp phát merchant riêng");

        OrganizerGatewayBinding existing = bindings.findByOrganizerIdAndProvider(organizerId, provider).orElse(null);
        if (existing != null && existing.isUsable() && stillOnGateway(existing)) return existing;
        boolean replaceBroken = existing != null && existing.isUsable();

        Organizer organizer = organizers.findById(organizerId)
                .orElseThrow(() -> DomainException.notFound("ORGANIZER_NOT_FOUND", "Không tìm thấy ban tổ chức"));
        String externalReference = "ENCORE_ORGANIZER_" + organizerId;

        OrganizerGatewayBinding claimed = claim(organizerId, provider, externalReference, replaceBroken);
        if (claimed.isUsable() && !replaceBroken) return claimed;
        UUID bindingId = claimed.getId();

        log.info("[GATEWAY-PROVISION] organizer={} name='{}' bắt đầu cấp phát trên {}",
                organizerId, organizer.getName(), provider);
        try {
            Optional<Provisioned> adopted = adopt(organizerId, externalReference);
            if (adopted.isPresent() && settlement != null)
                admin.updateSettlement(adopted.get().merchantNo(), settlement);
            Provisioned result = adopted.orElseGet(() -> onboard(organizerId, organizer.getName(), externalReference, settlement));
            String encryptedSecret = cipher.encrypt(result.merchantSecret());
            OrganizerGatewayBinding active = tx.execute(s -> {
                OrganizerGatewayBinding b = bindings.findById(bindingId).orElseThrow();
                if (b.getStatus() != OrganizerGatewayBinding.Status.PENDING) return b;
                b.activate(result.merchantNo(), result.terminalId(), encryptedSecret);
                return bindings.saveAndFlush(b);
            });
            if (settlement != null) markSynced(organizerId, settlement);
            log.info("[GATEWAY-PROVISION] organizer={} -> merchantNo={} terminalId={} settlement={}",
                    organizerId, result.merchantNo(), result.terminalId(), settlement == null ? "chưa khai" : "đã gửi");
            return active;
        } catch (RuntimeException ex) {
            tx.executeWithoutResult(s -> bindings.findById(bindingId)
                    .filter(b -> b.getStatus() == OrganizerGatewayBinding.Status.PENDING)
                    .ifPresent(b -> {
                        b.fail(ex.getMessage());
                        bindings.saveAndFlush(b);
                    }));
            log.error("[GATEWAY-PROVISION] organizer={} cấp phát THẤT BẠI: {}", organizerId, ex.toString());
            throw ex;
        }
    }

    private OrganizerGatewayBinding claim(UUID organizerId, String provider, String externalReference,
                                         boolean replaceBroken) {
        try {
            return tx.execute(s -> {
                OrganizerGatewayBinding b = bindings.findWithLockByOrganizerIdAndProvider(organizerId, provider)
                        .orElse(null);
                if (b == null)
                    return bindings.saveAndFlush(OrganizerGatewayBinding.pending(organizerId, provider, externalReference));
                if (b.isUsable() && !replaceBroken) return b;
                b.beginProvisioning(PENDING_STALE_AFTER);
                return bindings.saveAndFlush(b);
            });
        } catch (DataIntegrityViolationException ex) {
            throw DomainException.conflict("GATEWAY_PROVISIONING_IN_PROGRESS",
                    "Đang cấp phát cổng thanh toán cho ban tổ chức này, thử lại sau ít giây");
        }
    }

    private Optional<Provisioned> adopt(UUID organizerId, String externalReference) {
        String merNo = admin.listMerchants().stream()
                .filter(m -> externalReference.equals(m.externalReference()))
                .map(BankSimAdminClient.MerchantListItem::merNo)
                .findFirst().orElse(null);
        if (merNo == null) return Optional.empty();

        String terminalId = activeTerminalOf(merNo);
        String secret = admin.rotateCredential(merNo) instanceof Map<?, ?> m && m.get("merchantSecret") != null
                ? m.get("merchantSecret").toString() : null;
        if (secret == null)
            throw DomainException.conflict("GATEWAY_ADMIN_REJECTED", "Gateway rotate không trả về merchantSecret");
        log.warn("[GATEWAY-PROVISION] organizer={} gateway đã có merchantNo={} (reference {}) -> nhận lại, rotate secret",
                organizerId, merNo, externalReference);
        return Optional.of(new Provisioned(merNo, terminalId, secret));
    }

    private Provisioned onboard(UUID organizerId, String name, String externalReference, SettlementAccount settlement) {
        BankSimAdminClient.Onboarded result = admin.onboard(name, externalReference, null, null, settlement);
        log.info("[GATEWAY-PROVISION] organizer={} onboard mới merchantNo={} routing={} methods={}",
                organizerId, result.merchantNo(), result.routingProfileCode(), result.paymentMethods());
        return new Provisioned(result.merchantNo(), result.terminalId(), result.merchantSecret());
    }

    private String activeTerminalOf(String merNo) {
        if (admin.terminals(merNo) instanceof List<?> terminals) {
            for (Object t : terminals) {
                if (t instanceof Map<?, ?> m && "ACTIVE".equals(String.valueOf(m.get("status"))))
                    return String.valueOf(m.get("terminalId"));
            }
        }
        throw DomainException.conflict("GATEWAY_TERMINAL_MISSING",
                "Merchant " + merNo + " trên gateway không có terminal ACTIVE nào. Admin cần bật hoặc tạo terminal rồi cấp phát lại.");
    }

    private boolean stillOnGateway(OrganizerGatewayBinding binding) {
        if (!admin.isConfigured()) return true;
        String merNo = binding.getGatewayMerchantNo();
        try {
            Object reference = admin.merchant(merNo) instanceof Map<?, ?> m ? m.get("externalReference") : null;
            if (reference == null || reference.equals(binding.getExternalReference())) return true;
            log.warn("[GATEWAY-PROVISION] organizer={} merchantNo={} giờ thuộc externalReference={} -> cấp phát lại",
                    binding.getOrganizerId(), merNo, reference);
            return false;
        } catch (DomainException ex) {
            if (ex.getStatus() != HttpStatus.NOT_FOUND) throw ex;
            log.warn("[GATEWAY-PROVISION] organizer={} merchantNo={} không còn trên gateway -> cấp phát lại",
                    binding.getOrganizerId(), merNo);
            return false;
        }
    }

    private void markSynced(UUID organizerId, SettlementAccount account) {
        tx.executeWithoutResult(s -> payoutAccounts.markSynced(organizerId, account.bankBin(), account.accountNumber(),
                Instant.now()));
    }

    private static String mask(String accountNumber) {
        return accountNumber == null ? null : "••••" + accountNumber.substring(Math.max(0, accountNumber.length() - 4));
    }

    private record Provisioned(String merchantNo, String terminalId, String merchantSecret) {}
}
