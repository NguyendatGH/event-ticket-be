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
import com.example.demo.infrastructure.gateway.banksim.BankSimMerchantClient;
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
    @MockitoBean BankSimMerchantClient merchantApi;
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

    private static BankSimAdminClient.Onboarded onboarded(String merNo, String secret) {
        return new BankSimAdminClient.Onboarded(merNo, "TerNoIgnored", secret, "STANDARD", List.of("CARD", "QR"), "OPTIONAL");
    }

    private void insertActiveBinding(UUID organizerId, String merNo) {
        jdbc.update("""
                insert into organizer_gateway_bindings (id, organizer_id, provider, gateway_merchant_no,
                    encrypted_merchant_secret, status, external_reference)
                values (?, ?, 'BANKSIM', ?, 'iv:ct', 'ACTIVE', ?)
                """, UUID.randomUUID(), organizerId, merNo, ref(organizerId));
    }


    @Test
    void encoreStoresTheMerchantOnlyNeverATerminal() {
        assertEquals(0, jdbc.queryForObject("""
                select count(*) from information_schema.columns
                where table_name in ('organizer_gateway_bindings', 'payments') and column_name = 'gateway_terminal_id'
                """, Integer.class), "terminal là của gateway, Encore không lưu con trỏ nào");
        assertEquals(0, jdbc.queryForObject(
                "select count(*) from information_schema.tables where table_name = 'organizer_payment_channels'", Integer.class),
                "kênh nhận tiền nằm ở gateway, Encore không còn bảng kênh");
        assertEquals(1, jdbc.queryForObject("""
                select count(*) from information_schema.columns
                where table_name = 'organizer_gateway_bindings' and column_name = 'gateway_merchant_no'
                """, Integer.class));
    }


    @Test
    void failedOnboardIsPersistedWithReason() {
        UUID organizerId = newOrganizer();
        when(admin.listMerchants()).thenReturn(List.of());
        when(admin.onboard(any(), eq(ref(organizerId)), any(), any()))
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
        when(admin.onboard(any(), eq(ref(organizerId)), any(), any()))
                .thenThrow(new DomainException(HttpStatus.BAD_GATEWAY, "GATEWAY_UNREACHABLE", "timeout"));
        assertThrows(DomainException.class, () -> provisioning.ensureProvisioned(organizerId, "BANKSIM"));

        when(admin.listMerchants()).thenReturn(List.of(
                new BankSimAdminClient.MerchantListItem("MerNo000099", "BTC", "ACTIVE", ref(organizerId), 1)));
        when(admin.rotateCredential("MerNo000099")).thenReturn(Map.of("merchantSecret", "gwsec_adopted"));

        OrganizerGatewayBinding result = provisioning.ensureProvisioned(organizerId, "BANKSIM");

        assertEquals(OrganizerGatewayBinding.Status.ACTIVE, result.getStatus());
        assertEquals("MerNo000099", result.getGatewayMerchantNo());
        verify(admin, times(1)).onboard(any(), eq(ref(organizerId)), any(), any());
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
        verify(admin, never()).onboard(any(), eq(ref(organizerId)), any(), any());
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
        when(admin.onboard(any(), eq(ref(organizerId)), any(), any())).thenReturn(onboarded("MerNo000100", "gwsec_fresh"));

        OrganizerGatewayBinding result = provisioning.ensureProvisioned(organizerId, "BANKSIM");

        assertEquals(OrganizerGatewayBinding.Status.ACTIVE, result.getStatus());
        assertEquals("MerNo000100", result.getGatewayMerchantNo());
    }

    @Test
    void registeringAnOrganizerOnboardsAMerchantRightAway() throws Exception {
        when(admin.isConfigured()).thenReturn(true);
        when(admin.listMerchants()).thenReturn(List.of());
        when(admin.onboard(any(), any(), any(), any())).thenReturn(onboarded("MerNo000300", "gwsec_reg"));

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
        verify(admin).onboard(any(), eq(ref(organizerId)), any(), any());
    }

    @Test
    void jobProvisionsOrganizersWithoutMerchant() {
        UUID organizerId = newOrganizer();
        when(admin.isConfigured()).thenReturn(true);
        when(admin.listMerchants()).thenReturn(List.of());
        when(admin.onboard(any(), eq(ref(organizerId)), any(), any())).thenReturn(onboarded("MerNo000301", "gwsec_job"));

        provisioning.provisionMissing();

        assertEquals("ACTIVE", binding(organizerId).get("status"));
        assertEquals("MerNo000301", binding(organizerId).get("gateway_merchant_no"));
    }

    @Test
    void jobReprovisionsABindingWhoseMerchantGatewayLost() {
        UUID organizerId = newOrganizer();
        insertActiveBinding(organizerId, "MerNo000700");
        when(admin.isConfigured()).thenReturn(true);
        when(admin.listMerchants()).thenReturn(List.of());
        when(admin.merchant("MerNo000700")).thenThrow(new DomainException(HttpStatus.NOT_FOUND, "MERCHANT_NOT_FOUND", "gone"));
        when(admin.onboard(any(), eq(ref(organizerId)), any(), any())).thenReturn(onboarded("MerNo000701", "gwsec_again"));

        provisioning.provisionMissing();

        assertEquals("MerNo000701", binding(organizerId).get("gateway_merchant_no"),
                "DB gateway được dựng lại: binding mồ côi tự được cấp phát lại, không cần ai can thiệp");
        assertEquals("ACTIVE", binding(organizerId).get("status"));
    }

    @Test
    void gatewayOutageDoesNotMakeAnyoneOrphaned() {
        UUID organizerId = newOrganizer();
        insertActiveBinding(organizerId, "MerNo000710");
        when(admin.isConfigured()).thenReturn(true);
        when(admin.listMerchants()).thenThrow(new DomainException(HttpStatus.BAD_GATEWAY, "GATEWAY_UNREACHABLE", "down"));

        provisioning.provisionMissing();

        assertEquals("MerNo000710", binding(organizerId).get("gateway_merchant_no"), "gateway sập thì giữ nguyên, không cấp phát lại hàng loạt");
        verify(admin, never()).onboard(any(), eq(ref(organizerId)), any(), any());
    }

    @Test
    void customersSeeExactlyWhatTheGatewaySaysTheOrganizerCanAccept() {
        UUID onlyCard = newOrganizer();
        UUID cardAndQr = newOrganizer();
        UUID unknown = newOrganizer();
        gateway.setTerminalMethods(onlyCard, Set.of("CARD"));
        gateway.setTerminalMethods(cardAndQr, Set.of("QR", "CARD", "PAYNOW"));

        assertEquals(List.of("CARD"), paymentMethods.resolve(onlyCard).paymentMethods());
        assertEquals(List.of("CARD", "QR", "PAYNOW"), paymentMethods.resolve(cardAndQr).paymentMethods(),
                "Thứ tự hiện cho khách cố định: thẻ trước, QR sau");
        assertEquals(List.of("CARD"), paymentMethods.resolve(unknown).paymentMethods(),
                "Không hỏi được cổng thì chỉ đưa ra cổng chung, không chặn hết");
    }


    @Test
    void createdTerminalBecomesTheDefaultOnlyWhenAdminAsks() {
        when(admin.createTerminalRaw(eq("MerNo000401"), any())).thenReturn(
                Map.of("terminalId", "TerNo000402", "merNo", "MerNo000401", "status", "ACTIVE", "purpose", "SPARE"),
                Map.of("terminalId", "TerNo000403", "merNo", "MerNo000401", "status", "ACTIVE", "purpose", "SPARE"));
        when(admin.setDefaultTerminal("MerNo000401", "TerNo000403")).thenReturn(
                Map.of("terminalId", "TerNo000403", "purpose", "DEFAULT"));

        Map<?, ?> kept = (Map<?, ?>) gatewayAdmin.createTerminal("MerNo000401", Map.of("channel", "WEB"), false);
        assertEquals("SPARE", kept.get("purpose"));
        verify(admin, never()).setDefaultTerminal(any(), any());

        Map<?, ?> used = (Map<?, ?>) gatewayAdmin.createTerminal("MerNo000401", Map.of("channel", "WEB"), true);
        assertEquals("DEFAULT", used.get("purpose"));
        verify(admin).setDefaultTerminal("MerNo000401", "TerNo000403");
    }

    @Test
    void terminalStatusRulesAreTheGatewaysNotEncores() {
        when(admin.patchTerminalRaw("TerNo000410", Map.of("status", "INACTIVE")))
                .thenThrow(new DomainException(HttpStatus.CONFLICT, "TERMINAL_IN_USE", "đang nhận đơn"));

        DomainException ex = assertThrows(DomainException.class, () -> gatewayAdmin.setTerminalStatus("TerNo000410", "INACTIVE"));

        assertEquals("TERMINAL_IN_USE", ex.getCode(), "lỗi nghiệp vụ của gateway đi thẳng tới admin");
    }


    private UUID organizerWithMerchant(String merNo) throws Exception {
        when(admin.isConfigured()).thenReturn(true);
        when(admin.listMerchants()).thenReturn(List.of());
        when(admin.onboard(any(), any(), any(), any())).thenReturn(onboarded(merNo, "gwsec_bank"));
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

    private static final BankSimMerchantClient.BankOption VCB =
            new BankSimMerchantClient.BankOption("VCB", "Vietcombank", "970436", List.of("CARD", "QR"), true);
    private static final BankSimMerchantClient.BankOption TCB =
            new BankSimMerchantClient.BankOption("TCB", "Techcombank", "970407", List.of("CARD", "QR", "GOOGLE_PAY"), true);

    private static BankSimMerchantClient.ChannelView channel(UUID id, BankSimMerchantClient.BankOption bank, boolean primary,
                                                             String masked, String... methods) {
        return new BankSimMerchantClient.ChannelView(id.toString(), bank.code(), bank.name(), bank.bankBin(),
                List.of(methods), List.of(methods), "NGUYEN VAN A", masked, primary, java.time.Instant.parse("2026-10-09T01:00:00Z"), "ACTIVE");
    }

    private static BankSimMerchantClient.Channels view(List<BankSimMerchantClient.ChannelView> channels, String... customer) {
        return new BankSimMerchantClient.Channels(channels, List.of(TCB, VCB), List.of(customer));
    }

    @Test
    void openingAChannelIsAskedOfTheGatewayWithMerchantCredentialsOnly() throws Exception {
        UUID userId = organizerWithMerchant("MerNo000500");
        UUID vcb = UUID.randomUUID();
        when(merchantApi.openChannel(any(), any())).thenReturn(view(List.of(channel(vcb, VCB, true, "******6789", "QR")), "QR"));

        PayoutAccountResponse saved = payoutAccounts.addChannel(userId,
                new AddPaymentChannelRequest("VCB", List.of("qr"), "NGUYEN VAN A", "0123 456 789"));

        var ctx = org.mockito.ArgumentCaptor.forClass(com.example.demo.infrastructure.gateway.banksim.GatewayCredentialResolver.GatewayMerchantContext.class);
        var sent = org.mockito.ArgumentCaptor.forClass(BankSimMerchantClient.OpenChannel.class);
        verify(merchantApi).openChannel(ctx.capture(), sent.capture());
        assertEquals("MerNo000500", ctx.getValue().merchantNo());
        assertEquals("VCB", sent.getValue().bankCode());
        assertEquals(List.of("qr"), sent.getValue().paymentMethods(), "Encore chuyển nguyên, gateway mới là nơi kiểm luật");
        assertEquals("0123 456 789", sent.getValue().accountNumber());

        assertEquals(1, saved.channels().size());
        assertEquals(vcb, saved.channels().getFirst().id());
        assertTrue(saved.channels().getFirst().primary());
        assertEquals("970436", saved.bankBin(), "tài khoản đang nhận tiền ở đầu trang = tài khoản kênh chính của gateway");
        assertEquals("******6789", saved.maskedAccountNumber());
        assertEquals(List.of("QR"), saved.paymentMethods());
        assertEquals(List.of("TCB", "VCB"), saved.banks().stream().map(b -> b.code()).toList(), "ngân hàng sắp theo tên");
    }

    @Test
    void channelRulesAreEnforcedByTheGatewayAndErrorsPassThroughUntouched() throws Exception {
        UUID userId = organizerWithMerchant("MerNo000501");
        for (String code : List.of("PAYMENT_METHOD_IN_OTHER_CHANNEL", "BANK_ALREADY_A_CHANNEL",
                "PAYMENT_METHOD_NOT_SUPPORTED_BY_BANK", "UNKNOWN_BANK", "ACCOUNT_REQUIRED")) {
            org.mockito.Mockito.doThrow(new DomainException(HttpStatus.CONFLICT, code, "gateway: " + code))
                    .when(merchantApi).openChannel(any(), any());
            DomainException ex = assertThrows(DomainException.class, () -> payoutAccounts.addChannel(userId,
                    new AddPaymentChannelRequest("VCB", List.of("QR"), "NGUYEN VAN A", "0123456789")));
            assertEquals(code, ex.getCode(), "FE bắt theo code nên Encore không được đổi");
            assertEquals("gateway: " + code, ex.getMessage());
        }
    }

    @Test
    void editingAndRemovingAChannelUseTheGatewaysChannelId() throws Exception {
        UUID userId = organizerWithMerchant("MerNo000502");
        UUID vcb = UUID.randomUUID();
        UUID tcb = UUID.randomUUID();
        when(merchantApi.updateChannel(any(), eq(vcb.toString()), any()))
                .thenReturn(view(List.of(channel(vcb, VCB, true, "******6789", "QR"), channel(tcb, TCB, false, "******8899", "GOOGLE_PAY")), "QR", "GOOGLE_PAY"));
        when(merchantApi.removeChannel(any(), eq(vcb.toString())))
                .thenReturn(view(List.of(channel(tcb, TCB, true, "******8899", "GOOGLE_PAY")), "GOOGLE_PAY"));

        PayoutAccountResponse edited = payoutAccounts.updateChannel(userId, vcb, new UpdatePaymentChannelRequest(List.of("QR"), "", ""));
        var sent = org.mockito.ArgumentCaptor.forClass(BankSimMerchantClient.UpdateChannel.class);
        verify(merchantApi).updateChannel(any(), eq(vcb.toString()), sent.capture());
        assertEquals(List.of("QR"), sent.getValue().paymentMethods());
        assertEquals(List.of(true, false), edited.channels().stream().map(PaymentChannelResponse::primary).toList());

        PayoutAccountResponse afterRemove = payoutAccounts.removeChannel(userId, vcb);
        assertEquals(List.of("TCB"), afterRemove.channels().stream().map(PaymentChannelResponse::bankCode).toList());
        assertTrue(afterRemove.channels().getFirst().primary(), "kênh còn lại thành kênh chính (gateway quyết)");
        assertEquals("970407", afterRemove.bankBin());
    }

    @Test
    void thePageShowsWhatTheGatewayHasAndNothingElse() throws Exception {
        UUID userId = organizerWithMerchant("MerNo000503");
        UUID vcb = UUID.randomUUID();
        when(merchantApi.channels(any())).thenReturn(view(List.of(channel(vcb, VCB, true, "******6789", "CARD", "QR")), "CARD", "QR"));

        PayoutAccountResponse page = payoutAccounts.mine(userId);

        assertEquals(List.of(vcb), page.channels().stream().map(PaymentChannelResponse::id).toList());
        assertTrue(page.acceptingPayments());
        assertEquals(List.of("CARD", "QR"), page.paymentMethods());
    }

    @Test
    void organizerNotYetProvisionedSeesAnEmptyPageAndCannotChangeChannels() throws Exception {
        UUID userId = organizerWithMerchant("MerNo000505");
        jdbc.update("update organizer_gateway_bindings set status = 'FAILED' where organizer_id = ?", organizerOf(userId));

        PayoutAccountResponse page = payoutAccounts.mine(userId);
        assertFalse(page.acceptingPayments());
        assertTrue(page.channels().isEmpty());

        DomainException ex = assertThrows(DomainException.class, () -> payoutAccounts.addChannel(userId,
                new AddPaymentChannelRequest("VCB", List.of("QR"), "NGUYEN VAN A", "0123456789")));
        assertEquals("GATEWAY_MERCHANT_NOT_READY", ex.getCode());
        verify(merchantApi, never()).openChannel(any(), any());
    }

    private UUID organizerOf(UUID userId) {
        return jdbc.queryForObject("select id from organizers where user_id = ?", UUID.class, userId);
    }

    @Test
    void singleAccountSaveIsRefusedWhenTheGatewayHasChannels() throws Exception {
        UUID userId = organizerWithMerchant("MerNo000504");
        DomainException ex = assertThrows(DomainException.class, () -> payoutAccounts.save(userId,
                new SavePayoutAccountRequest("970436", "NGUYEN VAN A", "0123456789")));
        assertEquals("USE_PAYMENT_CHANNELS", ex.getCode());
    }
}
