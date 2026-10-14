package com.example.demo.infrastructure.gateway.banksim;

import com.example.demo.domain.common.DomainException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;

@Component
public class BankSimMerchantClient {

    private static final Logger log = LoggerFactory.getLogger(BankSimMerchantClient.class);

    private final RestClient http;

    public BankSimMerchantClient(@Value("${app.bank-simulate.base-url}") String baseUrl) {
        java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(3)).build();
        org.springframework.http.client.JdkClientHttpRequestFactory factory =
                new org.springframework.http.client.JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(java.time.Duration.ofSeconds(15));
        this.http = RestClient.builder().baseUrl(baseUrl.replaceAll("/$", "")).requestFactory(factory).build();
    }

    public Channels channels(GatewayCredentialResolver.GatewayMerchantContext c) {
        return call("GET /gateway/channels", () -> http.get().uri("/api/v1/gateway/channels")
                .headers(h -> headers(h, c)).retrieve().body(Channels.class));
    }

    public Channels openChannel(GatewayCredentialResolver.GatewayMerchantContext c, OpenChannel request) {
        return call("POST /gateway/channels", () -> http.post().uri("/api/v1/gateway/channels")
                .headers(h -> headers(h, c)).body(request).retrieve().body(Channels.class));
    }

    public Channels updateChannel(GatewayCredentialResolver.GatewayMerchantContext c, String channelId, UpdateChannel request) {
        return call("PUT /gateway/channels/" + channelId, () -> http.put().uri("/api/v1/gateway/channels/{id}", channelId)
                .headers(h -> headers(h, c)).body(request).retrieve().body(Channels.class));
    }

    public Channels removeChannel(GatewayCredentialResolver.GatewayMerchantContext c, String channelId) {
        return call("DELETE /gateway/channels/" + channelId, () -> http.delete().uri("/api/v1/gateway/channels/{id}", channelId)
                .headers(h -> headers(h, c)).retrieve().body(Channels.class));
    }

    public List<String> paymentMethods(GatewayCredentialResolver.GatewayMerchantContext c) {
        PaymentMethods response = call("GET /gateway/payment-methods", () -> http.get().uri("/api/v1/gateway/payment-methods")
                .headers(h -> headers(h, c)).retrieve().body(PaymentMethods.class));
        return response == null || response.paymentMethods() == null ? List.of() : response.paymentMethods();
    }

    private <T> T call(String what, Supplier<T> action) {
        try {
            return action.get();
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().is4xxClientError()) throw passthrough(ex);
            log.warn("[BANKSIM] {} lỗi {}: {}", what, ex.getStatusCode(), ex.getResponseBodyAsString());
            throw new DomainException(HttpStatus.BAD_GATEWAY, "GATEWAY_REJECTED", "Cổng thanh toán đang lỗi, thử lại sau");
        } catch (RuntimeException ex) {
            log.error("[BANKSIM] {} không gọi được gateway: {}", what, ex.toString());
            throw new DomainException(HttpStatus.BAD_GATEWAY, "GATEWAY_UNREACHABLE", "Không kết nối được cổng thanh toán");
        }
    }

    private static DomainException passthrough(RestClientResponseException ex) {
        String body = ex.getResponseBodyAsString();
        String code = field(body, "code");
        String title = field(body, "title");
        HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
        if (status == HttpStatus.UNAUTHORIZED || status == HttpStatus.FORBIDDEN) status = HttpStatus.CONFLICT;
        return new DomainException(status == null ? HttpStatus.CONFLICT : status,
                code == null ? "GATEWAY_REJECTED" : code, title == null ? "Cổng thanh toán từ chối yêu cầu" : title);
    }

    private static String field(String json, String name) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + name + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(json == null ? "" : json);
        return m.find() ? m.group(1).replace("\\\"", "\"").replace("\\\\", "\\") : null;
    }

    private static void headers(org.springframework.http.HttpHeaders h, GatewayCredentialResolver.GatewayMerchantContext c) {
        h.set("X-Merchant-No", c.merchantNo());
        h.set("X-Merchant-Secret", c.secret());
    }

    public record OpenChannel(String bankCode, List<String> paymentMethods, String accountName, String accountNumber) {}

    public record UpdateChannel(List<String> paymentMethods, String accountName, String accountNumber) {}

    public record Channels(List<ChannelView> channels, List<BankOption> banks, List<String> customerMethods) {
        public List<ChannelView> channelsOrEmpty() {
            return channels == null ? List.of() : channels;
        }

        public List<BankOption> banksOrEmpty() {
            return banks == null ? List.of() : banks;
        }
    }

    public record ChannelView(String id, String bankCode, String bankName, String bankBin, List<String> paymentMethods,
                              List<String> routableMethods, String accountName, String accountNumberMasked,
                              boolean primary, Instant openedAt, String status) {}

    public record BankOption(String code, String name, String bankBin, List<String> paymentMethods, boolean threeDsSupported) {}

    private record PaymentMethods(List<String> paymentMethods) {}
}
