package com.example.demo.infrastructure.gateway.banksim;

import com.example.demo.domain.common.LogContext;
import com.example.demo.domain.payment.CreatePaymentCommand;
import com.example.demo.domain.payment.GatewayRejectedException;
import com.example.demo.domain.payment.GatewayTimeoutException;
import com.example.demo.domain.payment.InvalidWebhookSignatureException;
import com.example.demo.domain.payment.PaymentEvent;
import com.example.demo.domain.payment.PaymentGatewayPort;
import com.example.demo.domain.payment.PaymentLink;
import com.example.demo.domain.payment.PaymentProvider;
import com.example.demo.domain.payment.PaymentStatusResult;
import com.example.demo.domain.payment.RefundCommand;
import com.example.demo.domain.payment.RefundEvent;
import com.example.demo.domain.payment.RefundStatusResult;
import com.example.demo.domain.payment.RefundSubmitResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.HexFormat;

@Component
@Profile("!test")
public class BankSimPaymentGateway implements PaymentGatewayPort {
    private static final Logger log = LoggerFactory.getLogger(BankSimPaymentGateway.class);
    private static final String SIGNATURE_HEADER = "x-mock-signature";
    private final RestClient http;
    private final GatewayCredentialResolver credentials;
    private final BankSimMerchantClient merchantApi;
    private final ObjectMapper json;
    private volatile SupportedBins supportedBins;
    private final Map<java.util.UUID, CachedMethods> supportedMethods = new java.util.concurrent.ConcurrentHashMap<>();

    public BankSimPaymentGateway(@Value("${app.bank-simulate.base-url}") String baseUrl,
                                 GatewayCredentialResolver credentials,
                                 BankSimMerchantClient merchantApi,
                                 ObjectMapper json) {
        this.http = RestClient.builder()
                .baseUrl(baseUrl.replaceAll("/$", ""))
                .requestFactory(new BufferingClientHttpRequestFactory(new JdkClientHttpRequestFactory()))
                .requestInterceptor(BankSimPaymentGateway::logExchange)
                .build();
        this.credentials = credentials;
        this.merchantApi = merchantApi;
        log.info("BankSim gateway client: baseUrl={} (credential lấy theo từng organizer)", baseUrl);
        this.json = json;
    }

    @Override public PaymentProvider provider() { return PaymentProvider.MOCK; }

    @Override
    public PaymentLink createPaymentLink(CreatePaymentCommand c) {
        try {
            List<BankItem> items = new ArrayList<>();
            for (CreatePaymentCommand.Item item : c.items()) {
                items.add(new BankItem(item.name(), item.quantity(), item.price()));
            }
            GatewayCredentialResolver.GatewayMerchantContext ctx = credentials.forOrganizer(c.organizerId());
            BankPaymentResponse response = post(ctx, "/api/v1/gateway/payments")
                    .body(new BankPaymentRequest(c.orderCode(), c.amount(), c.description(), c.paymentMethod(),
                            null, items, c.returnUrl(), c.cancelUrl(), c.expiresAt()))
                    .retrieve().body(BankPaymentResponse.class);
            return new PaymentLink(response.providerPaymentId(), response.checkoutUrl(), response.qrCode(), ctx.merchantNo());
        } catch (RestClientResponseException ex) {
            throw new IllegalStateException("BankSim rejected createPaymentLink: " + ex.getStatusCode()
                    + " " + ex.getResponseBodyAsString(), ex);
        } catch (RuntimeException ex) {
            throw new IllegalStateException("BankSim payment link failed", ex);
        }
    }

    @Override
    public PaymentStatusResult getPaymentStatus(String providerPaymentId) {
        try (LogContext.Scope ignored = LogContext.withTrade(null, null, providerPaymentId)) {
            BankPaymentStatus response = get(credentials.forProviderPaymentId(providerPaymentId), "/api/v1/gateway/payments/{id}", providerPaymentId)
                    .retrieve().body(BankPaymentStatus.class);
            return new PaymentStatusResult(statusOf(response.status()), response.amountPaid(),
                    response.transactionRef(), response.paidAt());
        }
    }

    private static PaymentStatusResult.Status statusOf(String status) {
        if (status == null) return PaymentStatusResult.Status.PENDING;
        return switch (status) {
            case "PAID" -> PaymentStatusResult.Status.PAID;
            case "UNDERPAID" -> PaymentStatusResult.Status.UNDERPAID;
            case "EXPIRED" -> PaymentStatusResult.Status.EXPIRED;
            case "DECLINED", "FAILED", "CANCELLED" -> PaymentStatusResult.Status.CANCELLED;
            default -> PaymentStatusResult.Status.PENDING;
        };
    }

    @Override
    public void cancelPaymentLink(String providerPaymentId, String reason) {
        try (LogContext.Scope ignored = LogContext.withTrade(null, null, providerPaymentId)) {
            post(credentials.forProviderPaymentId(providerPaymentId), "/api/v1/gateway/payments/{id}/cancel", providerPaymentId)
                    .body(Map.of("reason", reason)).retrieve().toBodilessEntity();
        }
    }

    @Override
    public PaymentEvent verifyAndParse(String rawBody, Map<String, String> headers) {
        requireSignature(rawBody, headers);
        JsonNode n = json.readTree(rawBody);
        String paidAt = n.path("paidAt").asText();
        return new PaymentEvent(n.path("eventId").asText(), paymentIdOf(n), n.path("orderCode").asLong(),
                n.path("success").asBoolean(), n.path("amount").asLong(), n.path("transactionRef").asText(),
                paidAt.isBlank() ? null : Instant.parse(paidAt), textOrNull(n, "payerBankBin"),
                textOrNull(n, "payerAccountNumber"), rawBody);
    }

    @Override
    public RefundSubmitResult submitRefund(RefundCommand c) {
        GatewayCredentialResolver.GatewayMerchantContext ctx;
        try {
            ctx = credentials.forRefundReference(c.referenceId());
        } catch (com.example.demo.domain.common.DomainException ex) {
            throw new GatewayRejectedException(ex.getCode(), ex.getMessage());
        }
        try {
            BankRefundResponse response = post(ctx, "/api/v1/gateway/refunds")
                    .body(new BankRefundRequest(c.referenceId(), c.amount(), c.description(), c.toBin(), c.toAccountNumber(),
                            credentials.providerPaymentIdForRefundReference(c.referenceId()).orElse(null)))
                    .retrieve().body(BankRefundResponse.class);
            return new RefundSubmitResult(response.providerRefundId(), RefundStatusResult.Status.valueOf(response.status()), json.writeValueAsString(response));
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().is4xxClientError()) throw refundRejected(ex.getResponseBodyAsString());
            throw new GatewayTimeoutException("BankSim refund request failed", ex);
        } catch (RuntimeException ex) {
            throw new GatewayTimeoutException("BankSim refund request failed", ex);
        }
    }

    GatewayRejectedException refundRejected(String body) {
        String code = null;
        String message = null;
        try {
            JsonNode n = json.readTree(body);
            code = textOrNull(n, "code");
            message = textOrNull(n, "title");
        } catch (RuntimeException notJson) {
        }
        String normalized = code == null ? "BANK_SIM_REJECTED" : switch (code) {
            case "BANK_PROFILE_NOT_FOUND" -> "INVALID_DESTINATION";
            case "INSUFFICIENT_PAYOUT_BALANCE" -> "INSUFFICIENT_PAYOUT_BALANCE";
            default -> code;
        };
        return new GatewayRejectedException(normalized, "BankSim từ chối lệnh chi: "
                + (message != null ? message : body == null || body.isBlank() ? "(không có nội dung)" : body));
    }

    @Override
    public RefundStatusResult getRefundStatus(String providerRefundId) {
        try (LogContext.Scope ignored = LogContext.withTrade(null, null, providerRefundId)) {
            BankRefundResponse response = get(credentials.forProviderRefundId(providerRefundId), "/api/v1/gateway/refunds/{id}", providerRefundId)
                    .retrieve().body(BankRefundResponse.class);
            return new RefundStatusResult(RefundStatusResult.Status.valueOf(response.status()), response.providerRefundId(), response.failureCode(), response.failureReason(), Instant.now());
        }
    }

    @Override
    public Optional<RefundStatusResult> findRefundByReference(String referenceId) {
        var response = http.get().uri(uri -> uri.path("/api/v1/gateway/refunds/by-reference").queryParam("referenceId", referenceId).build()).headers(h -> headers(h, credentials.forRefundReference(referenceId)))
                .retrieve().toEntity(BankRefundResponse.class);
        if (response.getBody() == null) return Optional.empty();
        BankRefundResponse r = response.getBody();
        return Optional.of(new RefundStatusResult(RefundStatusResult.Status.valueOf(r.status()), r.providerRefundId(), r.failureCode(), r.failureReason(), Instant.now()));
    }

    @Override
    public long getPayoutBalance() {
        BankBalanceResponse response = get(credentials.anyContext(), "/api/v1/gateway/payout/balance").retrieve().body(BankBalanceResponse.class);
        return response.available();
    }

    @Override
    public java.util.Optional<java.util.Set<String>> payoutableBankBins() {
        SupportedBins cached = supportedBins;
        if (cached != null && cached.freshAt().isAfter(Instant.now().minusSeconds(60))) return cached.bins();
        try {
            SupportedBanksResponse response = http.get().uri("/api/v1/gateway/banks").retrieve().body(SupportedBanksResponse.class);
            java.util.Set<String> bins = response == null || response.bins() == null
                    ? java.util.Set.of() : java.util.Set.copyOf(response.bins());
            log.info("banksim chi được tới {} BIN: {}", bins.size(), bins);
            supportedBins = new SupportedBins(Optional.of(bins), Instant.now());
        } catch (RuntimeException ex) {
            log.warn("Không lấy được danh sách BIN của banksim, tạm coi là không giới hạn: {}", ex.toString());
            supportedBins = new SupportedBins(Optional.empty(), Instant.now());
        }
        return supportedBins.bins();
    }

    @Override
    public java.util.Optional<java.util.Set<String>> supportedPaymentMethods(java.util.UUID organizerId) {
        if (organizerId == null) return Optional.empty();
        CachedMethods cached = supportedMethods.get(organizerId);
        if (cached != null && cached.freshAt().isAfter(Instant.now().minusSeconds(15))) return cached.methods();
        GatewayCredentialResolver.GatewayMerchantContext ctx;
        try {
            ctx = credentials.forOrganizer(organizerId);
        } catch (com.example.demo.domain.common.DomainException ex) {
            log.info("organizer={} chưa có merchant banksim dùng được ({}), chưa lọc phương thức", organizerId, ex.getCode());
            return Optional.empty();
        }
        Optional<java.util.Set<String>> methods;
        try {
            methods = Optional.of(java.util.Set.copyOf(merchantApi.paymentMethods(ctx)));
            log.info("organizer={} banksim trả được {}", organizerId, methods.get());
        } catch (com.example.demo.domain.common.DomainException ex) {
            boolean refused = ex.getStatus().is4xxClientError();
            log.warn("organizer={} gateway {} khi hỏi phương thức ({})", organizerId, refused ? "từ chối" : "lỗi", ex.getCode());
            methods = refused ? Optional.of(java.util.Set.of()) : Optional.empty();
        }
        supportedMethods.put(organizerId, new CachedMethods(methods, Instant.now()));
        return methods;
    }

    @Override
    public void forgetPaymentMethods(java.util.UUID organizerId) {
        if (organizerId != null) supportedMethods.remove(organizerId);
    }

    private record CachedMethods(Optional<java.util.Set<String>> methods, Instant freshAt) {}

    private record SupportedBins(java.util.Optional<java.util.Set<String>> bins, Instant freshAt) {}

    private record SupportedBanksResponse(List<String> bins) {}

    @Override
    public RefundEvent verifyAndParseRefund(String rawBody, Map<String, String> headers) {
        requireSignature(rawBody, headers);
        JsonNode n = json.readTree(rawBody);
        return new RefundEvent(n.path("eventId").asText(), n.path("providerRefundId").asText(), n.path("referenceId").asText(),
                RefundStatusResult.Status.valueOf(n.path("state").asText()), n.path("failureCode").isNull() ? null : n.path("failureCode").asText(),
                n.path("failureReason").isNull() ? null : n.path("failureReason").asText(), rawBody);
    }

    private static ClientHttpResponse logExchange(HttpRequest request, byte[] body,
                                                  ClientHttpRequestExecution execution) throws IOException {
        long startedAt = System.nanoTime();
        String call = request.getMethod() + " " + request.getURI().getPath();
        try (LogContext.Scope ignored = LogContext.withTrade(request.getHeaders().getFirst("X-Merchant-No"),
                request.getHeaders().getFirst("X-Terminal-Id"), null)) {
            try {
                ClientHttpResponse response = execution.execute(request, body);
                long ms = (System.nanoTime() - startedAt) / 1_000_000;
                int status = response.getStatusCode().value();
                if (response.getStatusCode().isError()) {
                    String responseBody = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
                    log.warn("-> banksim {} failed status={} in {}ms body={}", call, status, ms, responseBody);
                } else {
                    log.info("-> banksim {} ok status={} in {}ms", call, status, ms);
                }
                return response;
            } catch (IOException | RuntimeException ex) {
                long ms = (System.nanoTime() - startedAt) / 1_000_000;
                log.error("-> banksim {} unreachable after {}ms: {}", call, ms, ex.toString());
                throw ex;
            }
        }
    }

    private RestClient.RequestHeadersSpec<?> get(GatewayCredentialResolver.GatewayMerchantContext c, String path, Object... vars) {
        return http.get().uri(path, vars).headers(h -> headers(h, c));
    }

    private RestClient.RequestBodySpec post(GatewayCredentialResolver.GatewayMerchantContext c, String path, Object... vars) {
        return http.post().uri(path, vars).headers(h -> headers(h, c));
    }

    private void headers(org.springframework.http.HttpHeaders h, GatewayCredentialResolver.GatewayMerchantContext c) {
            h.set("X-Merchant-No", c.merchantNo());
            h.set("X-Merchant-Secret", c.secret());
    }

    private static String paymentIdOf(JsonNode n) {
        String id = n.path("gwTxnId").asText(null);
        return id == null || id.isBlank() ? n.path("providerPaymentId").asText(null) : id;
    }

    private static String textOrNull(JsonNode n, String field) {
        String value = n.path(field).asText(null);
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String secretForWebhook(String rawBody) {
        try {
            return credentials.forProviderPaymentId(paymentIdOf(json.readTree(rawBody))).secret();
        } catch (RuntimeException ex) {
            throw new InvalidWebhookSignatureException("Không xác định được merchant của webhook");
        }
    }

    private void requireSignature(String rawBody, Map<String, String> headers) {
        String supplied = headers.get(SIGNATURE_HEADER);
        String secret = secretForWebhook(rawBody);
        if (supplied == null || !MessageDigest.isEqual(sign(rawBody, secret).getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8)))
            throw new InvalidWebhookSignatureException("Chữ ký webhook BankSim không hợp lệ");
    }

    private String sign(String rawBody, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(rawBody.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) { throw new IllegalStateException(e); }
    }

    private record BankPaymentRequest(long orderCode, long amount, String description, String paymentMethod,
                                      Boolean threeDs, List<BankItem> items,
                                      String returnUrl, String cancelUrl, Instant expiresAt) {}
    private record BankItem(String name, int quantity, long price) {}
    private record BankPaymentResponse(String providerPaymentId, String checkoutUrl, String qrCode) {}
    private record BankPaymentStatus(String status, long amountPaid, String transactionRef, Instant paidAt) {}


    private record BankRefundRequest(String referenceId, long amount, String description, String toBin, String toAccountNumber,
                                     String providerPaymentId) {}
    private record BankRefundResponse(String providerRefundId, String status, String failureCode, String failureReason) {}
    private record BankBalanceResponse(long available) {}
}
