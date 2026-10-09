package com.example.demo.infrastructure.gateway.banksim;

import com.example.demo.domain.common.DomainException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;

@Component
public class BankSimAdminClient {

    private static final Logger log = LoggerFactory.getLogger(BankSimAdminClient.class);

    private final RestClient http;
    private final String adminKey;
    private final String webhookUrl;

    public BankSimAdminClient(@Value("${app.bank-simulate.base-url}") String baseUrl,
                              @Value("${app.bank-simulate.admin-key:}") String adminKey,
                              @Value("${app.bank-simulate.webhook-url:}") String webhookUrl) {
        this.adminKey = adminKey == null ? "" : adminKey;
        this.webhookUrl = webhookUrl == null || webhookUrl.isBlank() ? null : webhookUrl;
        java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(3)).build();
        org.springframework.http.client.JdkClientHttpRequestFactory factory =
                new org.springframework.http.client.JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(java.time.Duration.ofSeconds(15));
        this.http = RestClient.builder().baseUrl(baseUrl.replaceAll("/$", "")).requestFactory(factory).build();
    }

    public boolean isConfigured() {
        return !adminKey.isBlank();
    }

    public Onboarded onboard(String name, String externalReference, String channel, String currency) {
        return call("POST /admin/merchant-onboarding", () -> post("/api/v1/admin/merchant-onboarding")
                .body(new OnboardingRequest(name, externalReference, channel, currency, webhookUrl))
                .retrieve().body(Onboarded.class));
    }

    public Object setDefaultTerminal(String merNo, String terminalId) {
        return call("PUT /admin/merchants/" + merNo + "/default-terminal",
                () -> http.put().uri("/api/v1/admin/merchants/{m}/default-terminal", merNo).headers(this::headers)
                        .body(new DefaultTerminalRequest(terminalId)).retrieve().body(Object.class));
    }

    public Object createAcquirer(Object body) {
        return call("POST /admin/acquirers", () -> post("/api/v1/admin/acquirers").body(body).retrieve().body(Object.class));
    }

    public Object updateAcquirer(String code, Object body) {
        return call("PATCH /admin/acquirers/" + code, () -> http.patch().uri("/api/v1/admin/acquirers/{c}", code)
                .headers(this::headers).body(body).retrieve().body(Object.class));
    }

    public List<MerchantListItem> listMerchants() {
        return call("GET /admin/merchants", () -> get("/api/v1/admin/merchants")
                .retrieve().body(new org.springframework.core.ParameterizedTypeReference<List<MerchantListItem>>() {}));
    }

    public Object merchant(String merNo) {
        return call("GET /admin/merchants/" + merNo,
                () -> get("/api/v1/admin/merchants/{m}", merNo).retrieve().body(Object.class));
    }

    public Object terminals(String merNo) {
        return call("GET /admin/merchants/" + merNo + "/terminals",
                () -> get("/api/v1/admin/merchants/{m}/terminals", merNo).retrieve().body(Object.class));
    }

    public Object terminal(String terminalId) {
        return call("GET /admin/terminals/" + terminalId,
                () -> get("/api/v1/admin/terminals/{t}", terminalId).retrieve().body(Object.class));
    }

    public Object acquirerConfigs(String merNo) {
        return call("GET /admin/merchants/" + merNo + "/acquirer-configs",
                () -> get("/api/v1/admin/merchants/{m}/acquirer-configs", merNo).retrieve().body(Object.class));
    }

    public Object routingProfile(String code) {
        return call("GET /admin/routing-profiles/" + code,
                () -> get("/api/v1/admin/routing-profiles/{c}", code).retrieve().body(Object.class));
    }

    public Object createRoutingProfile(Object body) {
        return call("POST /admin/routing-profiles",
                () -> post("/api/v1/admin/routing-profiles").body(body).retrieve().body(Object.class));
    }

    public Object addRoutingRule(String code, Object body) {
        return call("POST /admin/routing-profiles/" + code + "/rules",
                () -> post("/api/v1/admin/routing-profiles/{c}/rules", code).body(body).retrieve().body(Object.class));
    }

    public Object routingProfiles() {
        return call("GET /admin/routing-profiles",
                () -> get("/api/v1/admin/routing-profiles").retrieve().body(Object.class));
    }

    public Object acquirers() {
        return call("GET /admin/acquirers", () -> get("/api/v1/admin/acquirers").retrieve().body(Object.class));
    }

    public Object createMerchantRaw(Object body) {
        return call("POST /admin/merchants", () -> post("/api/v1/admin/merchants").body(body)
                .retrieve().body(Object.class));
    }

    public Object updateMerchant(String merNo, Object body) {
        return call("PATCH /admin/merchants/" + merNo, () -> http.patch().uri("/api/v1/admin/merchants/{m}", merNo)
                .headers(this::headers).body(body).retrieve().body(Object.class));
    }

    public Object rotateCredential(String merNo) {
        return call("POST /admin/merchants/" + merNo + "/credentials/rotate",
                () -> post("/api/v1/admin/merchants/{m}/credentials/rotate", merNo).retrieve().body(Object.class));
    }

    public Object createTerminalRaw(String merNo, Object body) {
        return call("POST /admin/merchants/" + merNo + "/terminals",
                () -> post("/api/v1/admin/merchants/{m}/terminals", merNo).body(body).retrieve().body(Object.class));
    }

    public Object patchTerminalRaw(String terminalId, Object body) {
        return call("PATCH /admin/terminals/" + terminalId,
                () -> http.patch().uri("/api/v1/admin/terminals/{t}", terminalId)
                        .headers(this::headers).body(body).retrieve().body(Object.class));
    }

    public Object updateTerminalRaw(String terminalId, String suffix, Object body) {
        return call("PUT /admin/terminals/" + terminalId + suffix,
                () -> http.put().uri("/api/v1/admin/terminals/{t}" + suffix, terminalId)
                        .headers(this::headers).body(body).retrieve().body(Object.class));
    }

    public Object addAcquirerConfig(String merNo, Object body) {
        return call("POST /admin/merchants/" + merNo + "/acquirer-configs",
                () -> post("/api/v1/admin/merchants/{m}/acquirer-configs", merNo).body(body).retrieve().body(Object.class));
    }

    private DomainException passthrough(RestClientResponseException ex) {
        String body = ex.getResponseBodyAsString();
        String code = field(body, "code");
        String title = field(body, "title");
        return new DomainException(HttpStatus.valueOf(ex.getStatusCode().value()),
                code == null ? "GATEWAY_ADMIN_REJECTED" : code,
                title == null ? "Gateway từ chối yêu cầu" : title);
    }

    private static String field(String json, String name) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + name + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json == null ? "" : json);
        return m.find() ? m.group(1) : null;
    }

    private RestClient.RequestHeadersSpec<?> get(String path, Object... vars) {
        return http.get().uri(path, vars).headers(this::headers);
    }

    private <T> T call(String what, java.util.function.Supplier<T> action) {
        if (!isConfigured())
            throw DomainException.conflict("GATEWAY_ADMIN_KEY_MISSING", "Chưa cấu hình BANK_SIMULATE_ADMIN_KEY");
        long startedAt = System.nanoTime();
        try {
            T result = action.get();
            return result;
        } catch (RestClientResponseException ex) {

            if (ex.getStatusCode().is4xxClientError()) throw passthrough(ex);
            throw new DomainException(HttpStatus.BAD_GATEWAY, "GATEWAY_ADMIN_REJECTED",
                    "Gateway từ chối: " + ex.getResponseBodyAsString());
        } catch (RuntimeException ex) {
        log.error("[BACKEND] cannot connect to [PAYMENT-GATEWAY]");
            throw new DomainException(HttpStatus.BAD_GATEWAY, "GATEWAY_UNREACHABLE", " gateway unreachable");
        }
    }

    private RestClient.RequestBodySpec post(String path, Object... vars) {
        return http.post().uri(path, vars).headers(this::headers);
    }

    private void headers(org.springframework.http.HttpHeaders h) {
        h.set("X-Admin-Key", adminKey);
    }


    private record OnboardingRequest(String name, String externalReference, String channel, String currency,
                                     String webhookUrl) {}

    private record DefaultTerminalRequest(String terminalId) {}





    public record Onboarded(String merchantNo, String terminalId, String merchantSecret, String routingProfileCode,
                            List<String> paymentMethods, String threeDsPolicy) {}

    public record MerchantListItem(String merNo, String name, String status, String externalReference,
                                   long terminalCount) {}

}
