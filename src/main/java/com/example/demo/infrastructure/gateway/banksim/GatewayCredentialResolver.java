package com.example.demo.infrastructure.gateway.banksim;

import com.example.demo.domain.common.DomainException;
import com.example.demo.domain.gateway.OrganizerGatewayBinding;
import com.example.demo.infrastructure.persistence.OrganizerGatewayBindingRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

@Component
public class GatewayCredentialResolver {

    public static final String BANKSIM = "BANKSIM";

    private final OrganizerGatewayBindingRepository bindings;
    private final GatewaySecretCipher cipher;
    private final JdbcClient jdbc;

    public GatewayCredentialResolver(OrganizerGatewayBindingRepository bindings, GatewaySecretCipher cipher,
                                     JdbcClient jdbc) {
        this.bindings = bindings;
        this.cipher = cipher;
        this.jdbc = jdbc;
    }

    public GatewayMerchantContext forOrganizer(UUID organizerId) {
        if (organizerId == null) throw notProvisioned("không xác định được ban tổ chức của đơn");
        OrganizerGatewayBinding binding = bindings.findByOrganizerIdAndProvider(organizerId, BANKSIM)
                .orElseThrow(() -> notProvisioned("ban tổ chức chưa được cấp phát trên BankSim"));
        if (!binding.isUsable())
            throw notProvisioned("binding đang ở trạng thái " + binding.getStatus());
        return new GatewayMerchantContext(binding.getGatewayMerchantNo(), binding.getGatewayTerminalId(),
                cipher.decrypt(binding.getEncryptedMerchantSecret()));
    }

    public java.util.List<String> channelTerminals(UUID organizerId) {
        return jdbc.sql("""
                        SELECT gateway_terminal_id FROM organizer_payment_channels
                        WHERE organizer_id = ? AND status = 'ACTIVE' ORDER BY opened_at
                        """)
                .param(organizerId).query(String.class).list();
    }

    public GatewayMerchantContext forProviderPaymentId(String providerPaymentId) {
        if (providerPaymentId == null || providerPaymentId.isBlank())
            throw notProvisioned("thiếu providerPaymentId");
        var row = jdbc.sql("SELECT gateway_merchant_no, gateway_terminal_id FROM payments WHERE provider_payment_id = ?")
                .param(providerPaymentId)
                .query((rs, n) -> new String[]{rs.getString(1), rs.getString(2)})
                .optional().orElse(null);
        if (row == null || row[0] == null || row[1] == null)
            throw notProvisioned("payment " + providerPaymentId + " không lưu danh tính gateway");
        OrganizerGatewayBinding binding = bindings.findByGatewayMerchantNoAndProvider(row[0], BANKSIM)
                .orElseThrow(() -> notProvisioned("không tìm thấy binding cho merchant " + row[0]));
        return new GatewayMerchantContext(row[0], row[1], cipher.decrypt(binding.getEncryptedMerchantSecret()));
    }

    public GatewayMerchantContext forRefundReference(String referenceId) {
        return forRefund("r.idempotency_key = ?", referenceId);
    }

    public GatewayMerchantContext forProviderRefundId(String providerRefundId) {
        return forRefund("r.provider_refund_id = ?", providerRefundId);
    }

    public Optional<String> providerPaymentIdForRefundReference(String referenceId) {
        if (referenceId == null || referenceId.isBlank()) return Optional.empty();
        return jdbc.sql("SELECT p.provider_payment_id FROM refunds r JOIN payments p ON p.id = r.payment_id "
                        + "WHERE r.idempotency_key = ?")
                .param(referenceId)
                .query(String.class)
                .optional();
    }

    private GatewayMerchantContext forRefund(String where, String value) {
        if (value == null || value.isBlank()) throw notProvisioned("thiếu mã refund");
        var row = jdbc.sql("SELECT p.gateway_merchant_no, p.gateway_terminal_id FROM refunds r "
                        + "JOIN payments p ON p.id = r.payment_id WHERE " + where)
                .param(value)
                .query((rs, n) -> new String[]{rs.getString(1), rs.getString(2)})
                .optional().orElse(null);
        if (row == null) throw notProvisioned("không tìm thấy refund " + value);
        if (row[0] == null || row[1] == null) return anyContext();
        OrganizerGatewayBinding binding = bindings.findByGatewayMerchantNoAndProvider(row[0], BANKSIM)
                .orElseThrow(() -> notProvisioned("không tìm thấy binding cho merchant " + row[0]));
        return new GatewayMerchantContext(row[0], row[1], cipher.decrypt(binding.getEncryptedMerchantSecret()));
    }

    public GatewayMerchantContext anyContext() {
        OrganizerGatewayBinding binding = bindings
                .findFirstByProviderAndStatusOrderByCreatedAtAsc(BANKSIM, OrganizerGatewayBinding.Status.ACTIVE)
                .orElseThrow(() -> notProvisioned("chưa có ban tổ chức nào được cấp phát trên BankSim"));
        return new GatewayMerchantContext(binding.getGatewayMerchantNo(), binding.getGatewayTerminalId(),
                cipher.decrypt(binding.getEncryptedMerchantSecret()));
    }

    private static DomainException notProvisioned(String why) {
        return DomainException.conflict("GATEWAY_MERCHANT_NOT_PROVISIONED",
                "Organizer is not provisioned on BANKSIM (" + why + ")");
    }

    public record GatewayMerchantContext(String merchantNo, String terminalId, String secret) {
        public GatewayMerchantContext withTerminal(String otherTerminalId) {
            return new GatewayMerchantContext(merchantNo, otherTerminalId, secret);
        }
    }
}
