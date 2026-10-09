package com.example.demo.commerce;

import com.example.demo.application.AuthService;
import com.example.demo.application.GatewayAdminService;
import com.example.demo.application.GatewayProvisioningService;
import com.example.demo.application.dto.RegisterOrganizerRequest;
import com.example.demo.application.PaymentMethodsService;
import com.example.demo.application.PayoutAccountService;
import com.example.demo.application.dto.PayoutAccountResponse;
import com.example.demo.application.dto.AddPaymentChannelRequest;
import com.example.demo.application.dto.PaymentChannelResponse;
import com.example.demo.application.dto.SavePayoutAccountRequest;
import com.example.demo.application.dto.UpdatePaymentChannelRequest;
import com.example.demo.domain.common.DomainException;
import com.example.demo.domain.gateway.OrganizerGatewayBinding;
import com.example.demo.infrastructure.gateway.banksim.BankSimAdminClient;
import com.example.demo.support.MockPaymentGateway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "app.jwt.secret=test-secret-test-secret-test-secret-1234",
        "DB_URL=unused", "DB_USERNAME=unused", "DB_PASSWORD=unused"
})
@ActiveProfiles("test")
class OrganizerGatewayTests {

    @TestConfiguration(proxyBeanMethods = false)
    static class Containers {
        @Bean
        @ServiceConnection
        PostgreSQLContainer postgres() {
            return new PostgreSQLContainer("postgres:16-alpine");
        }
    }

    @MockitoBean BankSimAdminClient admin;
    @Autowired GatewayProvisioningService provisioning;
    @Autowired PaymentMethodsService paymentMethods;
    @Autowired MockPaymentGateway gateway;
    @Autowired JdbcTemplate jdbc;
    @Autowired AuthService auth;
    @Autowired GatewayAdminService gatewayAdmin;
    @Autowired PayoutAccountService payoutAccounts;

    private UUID newOrganizer() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into organizers (id, slug, name) values (?, ?, ?)", id, "btc-" + id, "BTC " + id);
        return id;
    }

    private Map<String, Object> binding(UUID organizerId) {
        return jdbc.queryForMap("select * from organizer_gateway_bindings where organizer_id = ? and provider = 'BANKSIM'",
                organizerId);
    }

    private static String ref(UUID organizerId) {
        return "ENCORE_ORGANIZER_" + organizerId;
    }

    @Test
    void failedOnboardIsPersistedWithReason() {
        UUID organizerId = newOrganizer();
        when(admin.listMerchants()).thenReturn(List.of());
        when(admin.onboard(any(), eq(ref(organizerId)), any(), any(), any()))
                .thenThrow(new DomainException(HttpStatus.BAD_GATEWAY, "GATEWAY_UNREACHABLE", "gateway unreachable"));

        assertThrows(DomainException.class, () -> provisioning.ensureProvisioned(organizerId, "BANKSIM"));

        Map<String, Object> row = binding(organizerId);
        assertEquals("FAILED", row.get("status"));
        assertEquals("gateway unreachable", row.get("provisioning_error"));
    }

    @Test
    void retryAdoptsMerchantWhoseResponseWasLost() {
        UUID organizerId = newOrganizer();
        when(admin.listMerchants()).thenReturn(List.of());
        when(admin.onboard(any(), eq(ref(organizerId)), any(), any(), any()))
                .thenThrow(new DomainException(HttpStatus.BAD_GATEWAY, "GATEWAY_UNREACHABLE", "timeout"));
        assertThrows(DomainException.class, () -> provisioning.ensureProvisioned(organizerId, "BANKSIM"));

        when(admin.listMerchants()).thenReturn(List.of(
                new BankSimAdminClient.MerchantListItem("MerNo000099", "BTC", "ACTIVE", ref(organizerId), 1)));
        when(admin.terminals("MerNo000099")).thenReturn(List.of(
                Map.of("terminalId", "TerNo000098", "status", "INACTIVE"),
                Map.of("terminalId", "TerNo000099", "status", "ACTIVE")));
        when(admin.rotateCredential("MerNo000099")).thenReturn(Map.of("merchantSecret", "gwsec_adopted"));

        OrganizerGatewayBinding result = provisioning.ensureProvisioned(organizerId, "BANKSIM");

        assertEquals(OrganizerGatewayBinding.Status.ACTIVE, result.getStatus());
        assertEquals("MerNo000099", result.getGatewayMerchantNo());
        assertEquals("TerNo000099", result.getGatewayTerminalId());
        verify(admin, times(1)).onboard(any(), eq(ref(organizerId)), any(), any(), any());
        Map<String, Object> row = binding(organizerId);
        assertEquals("ACTIVE", row.get("status"));
        assertNull(row.get("provisioning_error"));
        assertNotEquals("gwsec_adopted", row.get("encrypted_merchant_secret"), "Secret không được lưu plaintext");
    }

    @Test
    void secondRequestIsRejectedWhileFirstIsStillProvisioning() {
        UUID organizerId = newOrganizer();
        jdbc.update("""
                insert into organizer_gateway_bindings (id, organizer_id, provider, status, external_reference)
                values (?, ?, 'BANKSIM', 'PENDING', ?)
                """, UUID.randomUUID(), organizerId, ref(organizerId));

        DomainException ex = assertThrows(DomainException.class,
                () -> provisioning.ensureProvisioned(organizerId, "BANKSIM"));

        assertEquals("GATEWAY_PROVISIONING_IN_PROGRESS", ex.getCode());
        verify(admin, never()).onboard(any(), eq(ref(organizerId)), any(), any(), any());
        assertEquals("PENDING", binding(organizerId).get("status"));
    }

    @Test
    void stalePendingIsTakenOver() {
        UUID organizerId = newOrganizer();
        jdbc.update("""
                insert into organizer_gateway_bindings (id, organizer_id, provider, status, external_reference, updated_at)
                values (?, ?, 'BANKSIM', 'PENDING', ?, now() - interval '10 minutes')
                """, UUID.randomUUID(), organizerId, ref(organizerId));
        when(admin.listMerchants()).thenReturn(List.of());
        when(admin.onboard(any(), eq(ref(organizerId)), any(), any(), any())).thenReturn(new BankSimAdminClient.Onboarded(
                "MerNo000100", "TerNo000100", "gwsec_fresh", "CARD_VIA_BANK_A", List.of("CARD"), "OPTIONAL"));

        OrganizerGatewayBinding result = provisioning.ensureProvisioned(organizerId, "BANKSIM");

        assertEquals(OrganizerGatewayBinding.Status.ACTIVE, result.getStatus());
        assertEquals("MerNo000100", result.getGatewayMerchantNo());
    }

    @Test
    void customersSeeExactlyWhatTheOrganizersOwnTerminalEnables() {
        UUID onlyCard = newOrganizer();
        UUID cardAndQr = newOrganizer();
        UUID unknown = newOrganizer();
        gateway.setTerminalMethods(onlyCard, Set.of("CARD"));
        gateway.setTerminalMethods(cardAndQr, Set.of("QR", "CARD", "PAYNOW"));

        assertEquals(List.of("CARD"), paymentMethods.resolve(onlyCard).paymentMethods());
        assertEquals(List.of("CARD", "QR", "PAYNOW"), paymentMethods.resolve(cardAndQr).paymentMethods(),
                "Thứ tự hiện cho khách cố định: thẻ trước, QR sau");
        assertEquals(List.of("CARD"), paymentMethods.resolve(unknown).paymentMethods(),
                "Không hỏi được terminal thì chỉ đưa ra cổng chung, không chặn hết");
    }

    @Test
    void firstPayoutAccountOnboardsMerchantWithSettlement() {
        UUID organizerId = newOrganizer();
        var account = new BankSimAdminClient.SettlementAccount("970422", "0123456789", "NGUYEN VAN A");
        when(admin.isConfigured()).thenReturn(true);
        when(admin.listMerchants()).thenReturn(List.of());
        when(admin.onboard(any(), eq(ref(organizerId)), any(), any(), eq(account))).thenReturn(new BankSimAdminClient.Onboarded(
                "MerNo000200", "TerNo000200", "gwsec_new", "STANDARD", List.of("CARD", "QR"), "OPTIONAL"));

        provisioning.syncSettlement(organizerId, account);

        assertEquals("ACTIVE", binding(organizerId).get("status"));
        verify(admin, never()).updateSettlement(any(), any());
    }

    @Test
    void changingPayoutAccountUpdatesSettlementOnExistingMerchant() {
        UUID organizerId = newOrganizer();
        jdbc.update("""
                insert into organizer_gateway_bindings (id, organizer_id, provider, gateway_merchant_no, gateway_terminal_id,
                    encrypted_merchant_secret, status, external_reference)
                values (?, ?, 'BANKSIM', 'MerNo000201', 'TerNo000201', 'iv:ct', 'ACTIVE', ?)
                """, UUID.randomUUID(), organizerId, ref(organizerId));
        var account = new BankSimAdminClient.SettlementAccount("970436", "9988776655", "TRAN THI B");
        when(admin.isConfigured()).thenReturn(true);
        when(admin.merchant("MerNo000201")).thenReturn(Map.of("merNo", "MerNo000201", "externalReference", ref(organizerId)));

        provisioning.syncSettlement(organizerId, account);

        verify(admin).updateSettlement("MerNo000201", account);
        verify(admin, never()).onboard(any(), any(), any(), any(), any());
    }

    private void savePayoutAccount(UUID organizerId, String bin, String number) {
        jdbc.update("""
                insert into organizer_bank_accounts (id, organizer_id, bank_name, bank_bin, account_name, account_number, is_default)
                values (?, ?, 'MB Bank', ?, 'NGUYEN VAN A', ?, true)
                """, UUID.randomUUID(), organizerId, bin, number);
    }

    private Object syncedAt(UUID organizerId) {
        return jdbc.queryForObject("select gateway_synced_at from organizer_bank_accounts where organizer_id = ? and is_default",
                Object.class, organizerId);
    }

    @Test
    void registeringAnOrganizerOnboardsAMerchantRightAway() throws Exception {
        when(admin.isConfigured()).thenReturn(true);
        when(admin.listMerchants()).thenReturn(List.of());
        when(admin.onboard(any(), any(), any(), any(), any())).thenReturn(new BankSimAdminClient.Onboarded(
                "MerNo000300", "TerNo000300", "gwsec_reg", "STANDARD", List.of("CARD", "QR"), "OPTIONAL"));

        String email = "btc-" + UUID.randomUUID() + "@example.com";
        auth.registerOrganizer(new RegisterOrganizerRequest("Tran C", email, "password123", "BTC " + UUID.randomUUID(),
                null, null, null, null));
        UUID organizerId = jdbc.queryForObject(
                "select o.id from organizers o join users u on u.id = o.user_id where u.email = ?", UUID.class, email);

        String status = null;
        for (int i = 0; i < 50 && !"ACTIVE".equals(status); i++) {
            Thread.sleep(100);
            status = jdbc.query("select status from organizer_gateway_bindings where organizer_id = ?",
                    rs -> rs.next() ? rs.getString(1) : null, organizerId);
        }
        assertEquals("ACTIVE", status, "Đăng ký xong phải có merchant trên gateway, không chờ BTC khai tài khoản");
        verify(admin).onboard(any(), eq(ref(organizerId)), any(), any(), eq(null));
    }

    @Test
    void jobProvisionsOrganizersWithoutMerchantIncludingStoredPayoutAccount() {
        UUID organizerId = newOrganizer();
        savePayoutAccount(organizerId, "970422", "0123456789");
        var stored = new BankSimAdminClient.SettlementAccount("970422", "0123456789", "NGUYEN VAN A");
        when(admin.isConfigured()).thenReturn(true);
        when(admin.listMerchants()).thenReturn(List.of());
        when(admin.onboard(any(), eq(ref(organizerId)), any(), any(), eq(stored))).thenReturn(new BankSimAdminClient.Onboarded(
                "MerNo000301", "TerNo000301", "gwsec_job", "STANDARD", List.of("CARD", "QR"), "OPTIONAL"));

        provisioning.provisionMissing();

        assertEquals("ACTIVE", binding(organizerId).get("status"));
        assertNotNull(syncedAt(organizerId), "Tài khoản đi kèm onboard thì đã nằm trên gateway");
    }

    @Test
    void legacyAccountWithoutBinDoesNotBlockOnboarding() {
        UUID organizerId = newOrganizer();
        savePayoutAccount(organizerId, null, "12345678");
        when(admin.isConfigured()).thenReturn(true);
        when(admin.listMerchants()).thenReturn(List.of());
        when(admin.onboard(any(), eq(ref(organizerId)), any(), any(), eq(null))).thenReturn(new BankSimAdminClient.Onboarded(
                "MerNo000303", "TerNo000303", "gwsec_legacy", "STANDARD", List.of("CARD", "QR"), "OPTIONAL"));

        provisioning.provisionMissing();
        provisioning.syncPendingSettlements();

        assertEquals("ACTIVE", binding(organizerId).get("status"));
        assertNull(syncedAt(organizerId), "Tài khoản thiếu BIN chưa lên gateway, BTC phải khai lại");
        verify(admin, never()).updateSettlement(eq("MerNo000303"), any());
    }

    @Test
    void jobPushesUnsyncedPayoutAccountToExistingMerchant() {
        UUID organizerId = newOrganizer();
        jdbc.update("""
                insert into organizer_gateway_bindings (id, organizer_id, provider, gateway_merchant_no, gateway_terminal_id,
                    encrypted_merchant_secret, status, external_reference)
                values (?, ?, 'BANKSIM', 'MerNo000302', 'TerNo000302', 'iv:ct', 'ACTIVE', ?)
                """, UUID.randomUUID(), organizerId, ref(organizerId));
        savePayoutAccount(organizerId, "970436", "5566778899");
        when(admin.isConfigured()).thenReturn(true);
        when(admin.merchant("MerNo000302")).thenReturn(Map.of("merNo", "MerNo000302", "externalReference", ref(organizerId)));

        provisioning.syncPendingSettlements();

        verify(admin).updateSettlement("MerNo000302",
                new BankSimAdminClient.SettlementAccount("970436", "5566778899", "NGUYEN VAN A"));
        assertNotNull(syncedAt(organizerId));
    }

    @Test
    void createdTerminalIsUsedByEncoreOnlyWhenAdminAsks() {
        UUID organizerId = newOrganizer();
        jdbc.update("""
                insert into organizer_gateway_bindings (id, organizer_id, provider, gateway_merchant_no, gateway_terminal_id,
                    encrypted_merchant_secret, status, external_reference)
                values (?, ?, 'BANKSIM', 'MerNo000401', 'TerNo000401', 'iv:ct', 'ACTIVE', ?)
                """, UUID.randomUUID(), organizerId, ref(organizerId));
        when(admin.terminal("TerNo000401")).thenReturn(Map.of("terminalId", "TerNo000401", "status", "ACTIVE"));
        when(admin.createTerminalRaw(eq("MerNo000401"), any())).thenReturn(
                Map.of("terminalId", "TerNo000402", "merNo", "MerNo000401", "status", "ACTIVE"),
                Map.of("terminalId", "TerNo000403", "merNo", "MerNo000401", "status", "ACTIVE"));

        Map<?, ?> kept = (Map<?, ?>) gatewayAdmin.createTerminal("MerNo000401", Map.of("channel", "WEB"), false);
        assertEquals(false, kept.get("usedByEncore"));
        assertEquals("TerNo000401", binding(organizerId).get("gateway_terminal_id"));

        Map<?, ?> used = (Map<?, ?>) gatewayAdmin.createTerminal("MerNo000401", Map.of("channel", "WEB"), true);
        assertEquals(true, used.get("usedByEncore"));
        assertEquals("TerNo000403", used.get("terminalId"));
        assertEquals("TerNo000403", binding(organizerId).get("gateway_terminal_id"));
    }

    private UUID organizerWithMerchant(String merNo, String terminalId) throws Exception {
        when(admin.isConfigured()).thenReturn(true);
        when(admin.listMerchants()).thenReturn(List.of());
        when(admin.onboard(any(), any(), any(), any(), any())).thenReturn(new BankSimAdminClient.Onboarded(
                merNo, terminalId, "gwsec_bank", "STANDARD", List.of("CARD", "QR"), "OPTIONAL"));
        String email = "btc-" + UUID.randomUUID() + "@example.com";
        auth.registerOrganizer(new RegisterOrganizerRequest("Le D", email, "password123", "BTC " + UUID.randomUUID(),
                null, null, null, null));
        UUID organizerId = jdbc.queryForObject(
                "select o.id from organizers o join users u on u.id = o.user_id where u.email = ?", UUID.class, email);
        String status = null;
        for (int i = 0; i < 50 && !"ACTIVE".equals(status); i++) {
            Thread.sleep(100);
            status = jdbc.query("select status from organizer_gateway_bindings where organizer_id = ?",
                    rs -> rs.next() ? rs.getString(1) : null, organizerId);
        }
        assertEquals("ACTIVE", status);
        return jdbc.queryForObject("select id from users where email = ?", UUID.class, email);
    }

    private static final BankSimAdminClient.SelectableBank VCB =
            new BankSimAdminClient.SelectableBank("VCB", "Vietcombank", "970436", List.of("CARD", "QR"), true);
    private static final BankSimAdminClient.SelectableBank MBB =
            new BankSimAdminClient.SelectableBank("MBB", "MB Bank", "970422", List.of("QR"), false);
    private static final BankSimAdminClient.SelectableBank TCB =
            new BankSimAdminClient.SelectableBank("TCB", "Techcombank", "970407", List.of("CARD", "QR", "GOOGLE_PAY"), true);

    private static BankSimAdminClient.TerminalSetup terminal(String id, String bank, String threeDs, String... methods) {
        return new BankSimAdminClient.TerminalSetup(id, "ACTIVE", List.of(methods), threeDs, bank, List.of(methods));
    }

    private UUID organizerOf(UUID userId) {
        return jdbc.queryForObject("select id from organizers where user_id = ?", UUID.class, userId);
    }

    @Test
    void firstChannelUsesTheDefaultTerminalWithOnlyTheChosenMethods() throws Exception {
        UUID userId = organizerWithMerchant("MerNo000500", "TerNo000500");
        when(admin.selectableBanks()).thenReturn(List.of(VCB));
        when(admin.terminalSetups("MerNo000500")).thenReturn(List.of(terminal("TerNo000500", null, "OPTIONAL", "CARD", "QR")));

        DomainException unsupported = assertThrows(DomainException.class, () -> payoutAccounts.addChannel(userId,
                new AddPaymentChannelRequest("VCB", List.of("QR", "PAYNOW"), "NGUYEN VAN A", "0123456789")));
        assertEquals("PAYMENT_METHOD_NOT_SUPPORTED_BY_BANK", unsupported.getCode());
        DomainException unknown = assertThrows(DomainException.class, () -> payoutAccounts.addChannel(userId,
                new AddPaymentChannelRequest("XYZ", List.of("QR"), "NGUYEN VAN A", "0123456789")));
        assertEquals("UNKNOWN_BANK", unknown.getCode());
        verify(admin, never()).configureChannel(any(), any(), any(), any(), any());

        PayoutAccountResponse saved = payoutAccounts.addChannel(userId,
                new AddPaymentChannelRequest("VCB", List.of("qr"), "NGUYEN VAN A", "0123 456 789"));

        var settlement = new BankSimAdminClient.SettlementAccount("970436", "0123456789", "NGUYEN VAN A");
        verify(admin).configureChannel("TerNo000500", "VCB", List.of("QR"), null, settlement);
        verify(admin, never()).openChannel(any(), any(), any(), any(), any(), any());
        assertEquals(1, saved.channels().size());
        assertTrue(saved.channels().getFirst().primary());
        assertEquals("970436", saved.bankBin(), "kênh chính cũng là tài khoản mặc định / settlement của merchant");
        verify(admin).updateSettlement("MerNo000500", settlement);
    }

    @Test
    void secondChannelOpensANewTerminalAndMethodsCannotOverlap() throws Exception {
        UUID userId = organizerWithMerchant("MerNo000501", "TerNo000501");
        when(admin.selectableBanks()).thenReturn(List.of(VCB, MBB, TCB));
        when(admin.terminalSetups("MerNo000501")).thenReturn(List.of(
                terminal("TerNo000501", "VCB", null, "QR"), terminal("TerNo000601", "TCB", "OPTIONAL", "CARD", "GOOGLE_PAY")));
        when(admin.openChannel(any(), any(), any(), any(), any(), any())).thenReturn(terminal("TerNo000601", "TCB", "OPTIONAL", "CARD", "GOOGLE_PAY"));
        payoutAccounts.addChannel(userId, new AddPaymentChannelRequest("VCB", List.of("QR"), "NGUYEN VAN A", "0123456789"));

        DomainException qrTaken = assertThrows(DomainException.class, () -> payoutAccounts.addChannel(userId,
                new AddPaymentChannelRequest("MBB", List.of("QR"), "NGUYEN VAN A", "1111222233")));
        assertEquals("PAYMENT_METHOD_IN_OTHER_CHANNEL", qrTaken.getCode());
        DomainException sameBank = assertThrows(DomainException.class, () -> payoutAccounts.addChannel(userId,
                new AddPaymentChannelRequest("VCB", List.of("CARD"), "NGUYEN VAN A", "0123456789")));
        assertEquals("BANK_ALREADY_A_CHANNEL", sameBank.getCode());

        PayoutAccountResponse two = payoutAccounts.addChannel(userId,
                new AddPaymentChannelRequest("TCB", List.of("CARD", "GOOGLE_PAY"), "NGUYEN VAN A", "9988776655"));

        verify(admin).openChannel("MerNo000501", "Kênh Techcombank", "TCB", List.of("CARD", "GOOGLE_PAY"), "OPTIONAL",
                new BankSimAdminClient.SettlementAccount("970407", "9988776655", "NGUYEN VAN A"));
        assertEquals(List.of("VCB", "TCB"), two.channels().stream().map(PaymentChannelResponse::bankCode).toList());
        assertEquals(List.of(true, false), two.channels().stream().map(PaymentChannelResponse::primary).toList());
        assertEquals(List.of("QR", "CARD", "GOOGLE_PAY"), two.paymentMethods(), "khách thấy gộp phương thức của mọi kênh");
        assertEquals(List.of("TerNo000501", "TerNo000601"),
                jdbc.queryForList("select gateway_terminal_id from organizer_payment_channels where organizer_id = ? order by opened_at",
                        String.class, organizerOf(userId)));
    }

    @Test
    void editingKeepsAccountRemovingKeepsAtLeastOneAndReAddingReusesTheTerminal() throws Exception {
        UUID userId = organizerWithMerchant("MerNo000502", "TerNo000502");
        when(admin.selectableBanks()).thenReturn(List.of(VCB, TCB));
        when(admin.terminalSetups("MerNo000502")).thenReturn(List.of(
                terminal("TerNo000502", "VCB", "REQUIRED", "CARD", "QR"), terminal("TerNo000602", "TCB", null, "GOOGLE_PAY")));
        when(admin.openChannel(any(), any(), any(), any(), any(), any())).thenReturn(terminal("TerNo000602", "TCB", null, "GOOGLE_PAY"));
        UUID vcb = payoutAccounts.addChannel(userId,
                new AddPaymentChannelRequest("VCB", List.of("CARD", "QR"), "NGUYEN VAN A", "0123456789")).channels().getFirst().id();
        verify(admin).configureChannel(eq("TerNo000502"), eq("VCB"), eq(List.of("CARD", "QR")), eq("REQUIRED"), any());

        PayoutAccountResponse onlyQr = payoutAccounts.updateChannel(userId, vcb,
                new UpdatePaymentChannelRequest(List.of("QR"), "", ""));
        verify(admin).configureChannel("TerNo000502", "VCB", List.of("QR"), null, null);
        assertEquals("******6789", onlyQr.channels().getFirst().maskedAccountNumber(), "tài khoản cũ được giữ");
        DomainException half = assertThrows(DomainException.class, () -> payoutAccounts.updateChannel(userId, vcb,
                new UpdatePaymentChannelRequest(List.of("QR"), "TRAN THI B", "")));
        assertEquals("ACCOUNT_REQUIRED", half.getCode(), "nhập một nửa tài khoản là lỗi, không đoán");

        DomainException last = assertThrows(DomainException.class, () -> payoutAccounts.removeChannel(userId, vcb));
        assertEquals("LAST_PAYMENT_CHANNEL", last.getCode());

        payoutAccounts.addChannel(userId, new AddPaymentChannelRequest("TCB", List.of("GOOGLE_PAY"), "TRAN THI B", "5566778899"));
        PayoutAccountResponse afterRemove = payoutAccounts.removeChannel(userId, vcb);
        assertEquals(List.of("TCB"), afterRemove.channels().stream().map(PaymentChannelResponse::bankCode).toList());
        assertTrue(afterRemove.channels().getFirst().primary(), "kênh còn lại thành kênh chính");
        assertEquals("970407", afterRemove.bankBin());
        verify(admin).updateSettlement("MerNo000502", new BankSimAdminClient.SettlementAccount("970407", "5566778899", "TRAN THI B"));
        verify(admin, never()).patchTerminalRaw(any(), any());

        payoutAccounts.addChannel(userId, new AddPaymentChannelRequest("VCB", List.of("QR"), "NGUYEN VAN A", "1234567890"));
        verify(admin).configureChannel("TerNo000502", "VCB", List.of("QR"), null,
                new BankSimAdminClient.SettlementAccount("970436", "1234567890", "NGUYEN VAN A"));
        verify(admin, times(1)).openChannel(any(), any(), any(), any(), any(), any());
    }

    @Test
    void singleBankChoiceFromBeforeChannelsBecomesTheFirstChannel() throws Exception {
        UUID userId = organizerWithMerchant("MerNo000503", "TerNo000503");
        savePayoutAccount(organizerOf(userId), "970436", "0123456789");
        when(admin.selectableBanks()).thenReturn(List.of(VCB));
        when(admin.terminalSetups("MerNo000503")).thenReturn(List.of(terminal("TerNo000503", "VCB", null, "QR")));

        PayoutAccountResponse page = payoutAccounts.mine(userId);

        assertEquals(1, page.channels().size());
        assertEquals("VCB", page.channels().getFirst().bankCode());
        assertEquals(List.of("QR"), page.channels().getFirst().paymentMethods());
        assertEquals(1, payoutAccounts.mine(userId).channels().size(), "ghi nhận đúng một lần");
    }

    @Test
    void singleAccountSaveIsRefusedWhenTheGatewayHasChannels() throws Exception {
        UUID userId = organizerWithMerchant("MerNo000504", "TerNo000504");
        DomainException ex = assertThrows(DomainException.class, () -> payoutAccounts.save(userId,
                new SavePayoutAccountRequest("970436", "NGUYEN VAN A", "0123456789")));
        assertEquals("USE_PAYMENT_CHANNELS", ex.getCode());
    }
}
