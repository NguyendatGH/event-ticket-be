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
    private final BankSimPaymentGateway gateway =
            new BankSimPaymentGateway("http://localhost:1", credentials, new JsonMapper());

    @BeforeEach
    void merchantOwnsEveryPayment() {
        when(credentials.forProviderPaymentId(any()))
                .thenReturn(new GatewayCredentialResolver.GatewayMerchantContext("MerNo000001", "TerNo000001", SECRET));
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
                    .thenReturn(new GatewayCredentialResolver.GatewayMerchantContext("MerNo000001", "TerNo000001", SECRET));
            when(credentials.providerPaymentIdForRefundReference("ref-2")).thenReturn(java.util.Optional.of("TradeNo000042"));
            BankSimPaymentGateway real = new BankSimPaymentGateway(
                    "http://localhost:" + server.getAddress().getPort(), credentials, new JsonMapper());

            var result = real.submitRefund(new RefundCommand("ref-2", 100_000, "Hoan ve 2", "970436", "0123456789012"));

            assertEquals("TradeNo000043", result.providerRefundId());
            assertTrue(sent.get().contains("\"providerPaymentId\":\"TradeNo000042\""), sent.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void eachPaymentGoesThroughTheChannelThatOwnsItsMethod() throws Exception {
        Map<String, String> methodsByTerminal = Map.of("TerNoQR", "[\"QR\"]", "TerNoCARD", "[\"CARD\"]", "TerNoDEFAULT", "[\"CARD\",\"QR\"]");
        java.util.List<String> paidThrough = new java.util.concurrent.CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/api/v1/gateway/terminal", exchange -> respond(exchange, """
                {"terminalId":"%s","paymentMethods":%s}""".formatted(exchange.getRequestHeaders().getFirst("X-Terminal-Id"),
                methodsByTerminal.get(exchange.getRequestHeaders().getFirst("X-Terminal-Id")))));
        server.createContext("/api/v1/gateway/payments", exchange -> {
            paidThrough.add(exchange.getRequestHeaders().getFirst("X-Terminal-Id"));
            respond(exchange, """
                    {"providerPaymentId":"TradeNo000001","checkoutUrl":"http://gateway/checkout","qrCode":null}""");
        });
        server.start();
        try {
            java.util.UUID twoChannels = java.util.UUID.randomUUID();
            java.util.UUID noChannel = java.util.UUID.randomUUID();
            var binding = new GatewayCredentialResolver.GatewayMerchantContext("MerNo000001", "TerNoDEFAULT", SECRET);
            when(credentials.forOrganizer(any())).thenReturn(binding);
            when(credentials.channelTerminals(twoChannels)).thenReturn(java.util.List.of("TerNoQR", "TerNoCARD"));
            when(credentials.channelTerminals(noChannel)).thenReturn(java.util.List.of());
            BankSimPaymentGateway real = new BankSimPaymentGateway(
                    "http://localhost:" + server.getAddress().getPort(), credentials, new JsonMapper());

            assertEquals(java.util.Set.of("QR", "CARD"), real.supportedPaymentMethods(twoChannels).orElseThrow());
            var card = real.createPaymentLink(payment(twoChannels, "CARD"));
            var qr = real.createPaymentLink(payment(twoChannels, "QR"));
            real.createPaymentLink(payment(noChannel, "QR"));

            assertEquals(java.util.List.of("TerNoCARD", "TerNoQR", "TerNoDEFAULT"), paidThrough);
            assertEquals("TerNoCARD", card.gatewayTerminalId(), "lưu đúng terminal để tra trạng thái / hoàn tiền về sau");
            assertEquals("TerNoQR", qr.gatewayTerminalId());
        } finally {
            server.stop(0);
        }
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
                    .thenReturn(new GatewayCredentialResolver.GatewayMerchantContext("MerNo000001", "TerNo000001", SECRET));
            BankSimPaymentGateway real = new BankSimPaymentGateway(
                    "http://localhost:" + server.getAddress().getPort(), credentials, new JsonMapper());
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
