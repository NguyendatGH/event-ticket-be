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

    public Onboarded onboard(String name, String externalReference, String channel, String currency,
                             SettlementAccount settlementAccount) {
        return call("POST /admin/merchant-onboarding", () -> post("/api/v1/admin/merchant-onboarding")
                .body(new OnboardingRequest(name, externalReference, channel, currency, webhookUrl, settlementAccount))
                .retrieve().body(Onboarded.class));
    }

    public void updateSettlement(String merNo, SettlementAccount account) {
        call("PUT /admin/merchants/" + merNo + "/settlement-account",
                () -> http.put().uri("/api/v1/admin/merchants/{m}/settlement-account", merNo)
                        .headers(this::headers).body(account).retrieve().toBodilessEntity());
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

    public List<SelectableBank> selectableBanks() {
        List<AcquirerItem> all = call("GET /admin/acquirers", () -> get("/api/v1/admin/acquirers")
                .retrieve().body(new org.springframework.core.ParameterizedTypeReference<List<AcquirerItem>>() {}));
        return all == null ? List.of() : all.stream()
                .filter(a -> "ACTIVE".equals(a.status()) && a.bankBin() != null && !a.bankBin().isBlank())
                .map(a -> new SelectableBank(a.code(), a.name(), a.bankBin(),
                        a.paymentMethods() == null ? List.of() : a.paymentMethods(), a.threeDsSupported()))
                .sorted(java.util.Comparator.comparing(SelectableBank::name))
                .toList();
    }

    public TerminalSetup terminalSetup(String terminalId) {
        return call("GET /admin/terminals/" + terminalId,
                () -> get("/api/v1/admin/terminals/{t}", terminalId).retrieve().body(TerminalSetup.class));
    }

    public List<TerminalSetup> terminalSetups(String merNo) {
        List<TerminalSetup> all = call("GET /admin/merchants/" + merNo + "/terminals",
                () -> get("/api/v1/admin/merchants/{m}/terminals", merNo).retrieve()
                        .body(new org.springframework.core.ParameterizedTypeReference<List<TerminalSetup>>() {}));
        return all == null ? List.of() : all;
    }

    public TerminalSetup openChannel(String merNo, String name, String bankCode, List<String> paymentMethods,
                                     String threeDsPolicy, SettlementAccount settlement) {
        return call("POST /admin/merchants/" + merNo + "/terminals",
                () -> http.post().uri("/api/v1/admin/merchants/{m}/terminals", merNo).headers(this::headers)
                        .body(new CreateTerminalRequest(name, "WEB", "VND", paymentMethods, threeDsPolicy, null, bankCode, settlement))
                        .retrieve().body(TerminalSetup.class));
    }

    public TerminalSetup configureChannel(String terminalId, String bankCode, List<String> paymentMethods,
                                          String threeDsPolicy, SettlementAccount settlement) {
        return call("PUT /admin/terminals/" + terminalId + "/configuration",
                () -> http.put().uri("/api/v1/admin/terminals/{t}/configuration", terminalId).headers(this::headers)
                        .body(new TerminalConfigurationRequest(paymentMethods, threeDsPolicy, null, bankCode, settlement))
                        .retrieve().body(TerminalSetup.class));
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

    public CreatedMerchant createMerchant(String name, String webhookUrl) {
        return call("POST /admin/merchants", () -> post("/api/v1/admin/merchants")
                .body(new CreateMerchantRequest(name, webhookUrl))
                .retrieve().body(CreatedMerchant.class));
    }

    public void linkAcquirer(String merNo, String acquirerCode, String mid, String tid) {
        call("POST /admin/merchants/" + merNo + "/acquirer-configs",
                () -> post("/api/v1/admin/merchants/{m}/acquirer-configs", merNo)
                        .body(new AcquirerConfigRequest(acquirerCode, mid, tid))
                        .retrieve().toBodilessEntity());
    }

    public CreatedTerminal createTerminal(String merNo, String name, String channel, String currency,
                                          List<String> paymentMethods, String threeDsPolicy, String routingProfileCode) {
        return call("POST /admin/merchants/" + merNo + "/terminals",
                () -> post("/api/v1/admin/merchants/{m}/terminals", merNo)
                        .body(new CreateTerminalRequest(name, channel, currency, paymentMethods, threeDsPolicy, routingProfileCode, null, null))
                        .retrieve().body(CreatedTerminal.class));
    }

    public void updatePaymentMethods(String terminalId, List<String> paymentMethods) {
        call("PUT /admin/terminals/" + terminalId + "/payment-methods",
                () -> http.put().uri("/api/v1/admin/terminals/{t}/payment-methods", terminalId)
                        .headers(this::headers).body(new PaymentMethodsRequest(paymentMethods))
                        .retrieve().toBodilessEntity());
    }

    public void deactivateTerminal(String terminalId) {
        call("PATCH /admin/terminals/" + terminalId,
                () -> http.patch().uri("/api/v1/admin/terminals/{t}", terminalId)
                        .headers(this::headers).body(new UpdateTerminalRequest("INACTIVE"))
                        .retrieve().toBodilessEntity());
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

    private record CreateMerchantRequest(String name, String webhookUrl) {}
    private record AcquirerConfigRequest(String acquirerCode, String mid, String tid) {}
    private record CreateTerminalRequest(String name, String channel, String currency, List<String> paymentMethods,
                                         String threeDsPolicy, String routingProfileCode, String acquirerCode,
                                         SettlementAccount settlementAccount) {}
    private record PaymentMethodsRequest(List<String> paymentMethods) {}
    private record UpdateTerminalRequest(String status) {}

    private record OnboardingRequest(String name, String externalReference, String channel, String currency,
                                     String webhookUrl, SettlementAccount settlementAccount) {}

    private record TerminalConfigurationRequest(List<String> paymentMethods, String threeDsPolicy,
                                                String routingProfileCode, String acquirerCode,
                                                SettlementAccount settlementAccount) {}
    private record AcquirerItem(String code, String name, String status, List<String> paymentMethods,
                                boolean threeDsSupported, String bankBin) {}

    public record SelectableBank(String code, String name, String bankBin, List<String> paymentMethods,
                                 boolean threeDsSupported) {}

    public record TerminalSetup(String terminalId, String status, List<String> paymentMethods, String threeDsPolicy,
                                String acquirerCode, List<String> routableMethods) {}

    public record SettlementAccount(String bankBin, String accountNumber, String accountName) {}

    public record Onboarded(String merchantNo, String terminalId, String merchantSecret, String routingProfileCode,
                            List<String> paymentMethods, String threeDsPolicy) {}

    public record MerchantListItem(String merNo, String name, String status, String externalReference,
                                   long terminalCount) {}

    public record CreatedMerchant(String merNo, String name, String status, String webhookUrl, String merchantSecret) {}
    public record CreatedTerminal(String terminalId, String merNo, String name, String channel, String currency,
                                  List<String> paymentMethods, String threeDsPolicy, String routingProfileCode,
                                  String status) {}
}
