package com.example.demo.infrastructure.gateway.banksim;

import com.example.demo.domain.payment.GatewayRejectedException;
import com.example.demo.domain.payment.InvalidWebhookSignatureException;
import com.example.demo.domain.payment.PaymentEvent;
import com.example.demo.domain.payment.RefundCommand;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BankSimPaymentGatewayTest {

    private static final String SECRET = "gwsec_test";

    private final GatewayCredentialResolver credentials = mock(GatewayCredentialResolver.class);
    private final BankSimMerchantClient merchantApi = mock(BankSimMerchantClient.class);
    private final BankSimPaymentGateway gateway =
            new BankSimPaymentGateway("http://localhost:1", credentials, merchantApi, new JsonMapper());

    @BeforeEach
    void merchantOwnsEveryPayment() {
        when(credentials.forProviderPaymentId(any()))
                .thenReturn(new GatewayCredentialResolver.GatewayMerchantContext("MerNo000001", SECRET));
    }

    private PaymentEvent parse(String body) {
        return gateway.verifyAndParse(body, Map.of("x-mock-signature", sign(body)));
    }

    @Test
    void qrWebhookKeepsThePayerAccountSoRefundCanGoBackAutomatically() {
        PaymentEvent e = parse("""
                {"eventId":"TradeNo000001:BS1","providerPaymentId":"TradeNo000001","gwTxnId":"TradeNo000001",
                 "orderCode":123,"success":true,"paymentMethod":"QR","amount":100000,"transactionRef":"BS1",
                 "paidAt":"2026-10-08T03:00:00Z","payerBankBin":"970436","payerAccountNumber":"0123456789012"}""");

        assertEquals("970436", e.payerBankBin());
        assertEquals("0123456789012", e.payerAccountNumber());
        assertTrue(e.success());
        assertEquals("TradeNo000001", e.providerPaymentId());
    }

    @Test
    void cardWebhookHasNoPayerAccount() {
        PaymentEvent e = parse("""
                {"eventId":"TradeNo000002:02","gwTxnId":"TradeNo000002","orderCode":124,"resultCode":"02",
                 "success":true,"bankRef":"DIR-ABC","transactionRef":"DIR-ABC","amount":100000,
                 "paidAt":"2026-10-08T03:00:00Z"}""");

        assertNull(e.payerBankBin());
        assertNull(e.payerAccountNumber());
        assertEquals("TradeNo000002", e.providerPaymentId());
    }

    @Test
    void forgedSignatureIsRejected() {
        String body = """
                {"gwTxnId":"TradeNo000003","orderCode":1,"success":true,"amount":1}""";
        assertThrows(InvalidWebhookSignatureException.class,
                () -> gateway.verifyAndParse(body, Map.of("x-mock-signature", "deadbeef")));
    }

    @Test
    void refundToBankGatewayCannotPayIsInvalidDestination() throws Exception {
        GatewayRejectedException ex = rejectedOverHttp("""
                {"type":"about:blank","title":"No runtime bank profile configured for BIN 970418","status":409,
                 "code":"BANK_PROFILE_NOT_FOUND","traceId":"t-1"}""");

        assertEquals("INVALID_DESTINATION", ex.getCode());
        assertTrue(ex.getMessage().contains("970418"), ex.getMessage());
    }

    @Test
    void emptyPayoutWalletKeepsItsCode() {
        assertEquals("INSUFFICIENT_PAYOUT_BALANCE", gateway.refundRejected("""
                {"title":"Mock payout wallet has insufficient balance","status":409,"code":"INSUFFICIENT_PAYOUT_BALANCE"}""").getCode());
    }

    @Test
    void otherCodesPassThroughAndNonJsonFallsBack() {
        assertEquals("REFUND_REFERENCE_CONFLICT", gateway.refundRejected("""
                {"title":"Refund reference was already used with different details","code":"REFUND_REFERENCE_CONFLICT"}""").getCode());
        assertEquals("BANK_SIM_REJECTED", gateway.refundRejected("<html>bad request</html>").getCode());
        assertEquals("BANK_SIM_REJECTED", gateway.refundRejected("").getCode());
    }

    @Test
    void refundCarriesTheOriginalPayment() throws Exception {
        java.util.concurrent.atomic.AtomicReference<String> sent = new java.util.concurrent.atomic.AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/api/v1/gateway/refunds", exchange -> {
            sent.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = """
                    {"providerRefundId":"TradeNo000043","status":"SUCCEEDED","failureCode":null,"failureReason":null}"""
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            when(credentials.forRefundReference("ref-2"))
                    .thenReturn(new GatewayCredentialResolver.GatewayMerchantContext("MerNo000001", SECRET));
            when(credentials.providerPaymentIdForRefundReference("ref-2")).thenReturn(java.util.Optional.of("TradeNo000042"));
            BankSimPaymentGateway real = new BankSimPaymentGateway(
                    "http://localhost:" + server.getAddress().getPort(), credentials, merchantApi, new JsonMapper());

            var result = real.submitRefund(new RefundCommand("ref-2", 100_000, "Hoan ve 2", "970436", "0123456789012"));

            assertEquals("TradeNo000043", result.providerRefundId());
            assertTrue(sent.get().contains("\"providerPaymentId\":\"TradeNo000042\""), sent.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void paymentIsCreatedWithMerchantCredentialsOnlyAndGatewayPicksTheTerminal() throws Exception {
        java.util.List<String> terminalHeaders = new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.List<String> merchantHeaders = new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.List<String> bodies = new java.util.concurrent.CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/api/v1/gateway/payments", exchange -> {
            terminalHeaders.add(String.valueOf(exchange.getRequestHeaders().getFirst("X-Terminal-Id")));
            merchantHeaders.add(exchange.getRequestHeaders().getFirst("X-Merchant-No") + "/"
                    + exchange.getRequestHeaders().getFirst("X-Merchant-Secret"));
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, """
                    {"providerPaymentId":"TradeNo000001","checkoutUrl":"http://gateway/checkout","qrCode":null}""");
        });
        server.start();
        try {
            when(credentials.forOrganizer(any()))
                    .thenReturn(new GatewayCredentialResolver.GatewayMerchantContext("MerNo000001", SECRET));
            BankSimPaymentGateway real = new BankSimPaymentGateway(
                    "http://localhost:" + server.getAddress().getPort(), credentials, merchantApi, new JsonMapper());

            var qr = real.createPaymentLink(payment(java.util.UUID.randomUUID(), "QR"));
            real.createPaymentLink(payment(java.util.UUID.randomUUID(), "GOOGLE_PAY"));

            assertEquals(java.util.List.of("null", "null"), terminalHeaders, "Encore không gửi terminal: gateway tự chọn theo phương thức");
            assertEquals(java.util.List.of("MerNo000001/" + SECRET, "MerNo000001/" + SECRET), merchantHeaders);
            assertTrue(bodies.get(0).contains("\"paymentMethod\":\"QR\""), bodies.get(0));
            assertTrue(bodies.get(1).contains("\"paymentMethod\":\"GOOGLE_PAY\""), bodies.get(1));
            assertEquals("MerNo000001", qr.gatewayMerchantNo(), "lưu merchant đã thu để tra trạng thái / hoàn tiền về sau");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void customerMethodsComeFromTheGatewayAndAreCached() {
        java.util.UUID organizer = java.util.UUID.randomUUID();
        when(credentials.forOrganizer(organizer))
                .thenReturn(new GatewayCredentialResolver.GatewayMerchantContext("MerNo000001", SECRET));
        when(merchantApi.paymentMethods(any())).thenReturn(java.util.List.of("CARD", "QR"));

        assertEquals(java.util.Set.of("CARD", "QR"), gateway.supportedPaymentMethods(organizer).orElseThrow());
        gateway.supportedPaymentMethods(organizer);

        org.mockito.Mockito.verify(merchantApi, org.mockito.Mockito.times(1)).paymentMethods(any());
        gateway.forgetPaymentMethods(organizer);
        gateway.supportedPaymentMethods(organizer);
        org.mockito.Mockito.verify(merchantApi, org.mockito.Mockito.times(2)).paymentMethods(any());
    }

    @Test
    void gatewayRefusalMeansNoMethodsButOutageMeansUnknown() {
        java.util.UUID refused = java.util.UUID.randomUUID();
        java.util.UUID outage = java.util.UUID.randomUUID();
        java.util.UUID unprovisioned = java.util.UUID.randomUUID();
        var ctx = new GatewayCredentialResolver.GatewayMerchantContext("MerNo000001", SECRET);
        when(credentials.forOrganizer(refused)).thenReturn(ctx);
        when(credentials.forOrganizer(outage)).thenReturn(ctx);
        when(credentials.forOrganizer(unprovisioned))
                .thenThrow(com.example.demo.domain.common.DomainException.conflict("GATEWAY_MERCHANT_NOT_PROVISIONED", "x"));
        when(merchantApi.paymentMethods(any()))
                .thenThrow(new com.example.demo.domain.common.DomainException(
                        org.springframework.http.HttpStatus.CONFLICT, "MERCHANT_INACTIVE", "merchant tắt"))
                .thenThrow(new com.example.demo.domain.common.DomainException(
                        org.springframework.http.HttpStatus.BAD_GATEWAY, "GATEWAY_UNREACHABLE", "gateway sập"));

        assertEquals(java.util.Set.of(), gateway.supportedPaymentMethods(refused).orElseThrow());
        assertTrue(gateway.supportedPaymentMethods(outage).isEmpty());
        assertTrue(gateway.supportedPaymentMethods(unprovisioned).isEmpty());
    }

    private static com.example.demo.domain.payment.CreatePaymentCommand payment(java.util.UUID organizerId, String method) {
        return new com.example.demo.domain.payment.CreatePaymentCommand(organizerId, 1L, 100_000, "Ve", method,
                java.util.List.of(), "http://encore/return", "http://encore/cancel", java.time.Instant.now().plusSeconds(900));
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String json) throws java.io.IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private GatewayRejectedException rejectedOverHttp(String problemJson) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/api/v1/gateway/refunds", exchange -> {
            byte[] body = problemJson.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/problem+json");
            exchange.sendResponseHeaders(409, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            when(credentials.forRefundReference(any()))
                    .thenReturn(new GatewayCredentialResolver.GatewayMerchantContext("MerNo000001", SECRET));
            BankSimPaymentGateway real = new BankSimPaymentGateway(
                    "http://localhost:" + server.getAddress().getPort(), credentials, merchantApi, new JsonMapper());
            return assertThrows(GatewayRejectedException.class, () -> real.submitRefund(
                    new RefundCommand("ref-1", 100_000, "Hoan ve 1", "970418", "9998887776")));
        } finally {
            server.stop(0);
        }
    }

    private static String sign(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
