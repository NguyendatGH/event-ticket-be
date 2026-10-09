package com.example.demo.support;

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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Component
@Profile("test")
public class MockPaymentGateway implements PaymentGatewayPort {

    public static final String SIGNATURE_HEADER = "x-mock-signature";
    private static final Logger log = LoggerFactory.getLogger(MockPaymentGateway.class);

    public static final class Link {
        public final long orderCode;
        public final long amount;
        public volatile PaymentStatusResult.Status status = PaymentStatusResult.Status.PENDING;
        public volatile long amountPaid;
        public volatile String transactionRef;
        public volatile Instant paidAt;

        Link(long orderCode, long amount) { this.orderCode = orderCode; this.amount = amount; }
    }

    private final Map<String, Link> links = new ConcurrentHashMap<>();
    private final Map<String, MockRefund> refunds = new ConcurrentHashMap<>();
    private final Map<String, String> refundIdByReference = new ConcurrentHashMap<>();
    private final AtomicLong payoutBalance;
    private final AtomicLong payoutReserved = new AtomicLong();
    private final AtomicBoolean timeoutNextSubmit = new AtomicBoolean(false);
    private final String checkoutBase;
    private final byte[] secret;
    private final ObjectMapper json;

    public MockPaymentGateway(@Value("${app.payment.mock-checkout-url}") String checkoutBase,
                              @Value("${app.payment.mock-secret}") String secret,
                              @Value("${app.payment.mock-payout-balance:100000000}") long payoutBalance,
                              ObjectMapper json) {
        this.checkoutBase = checkoutBase;
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.payoutBalance = new AtomicLong(payoutBalance);
        this.json = json;
    }

    @Override
    public PaymentProvider provider() { return PaymentProvider.MOCK; }

    @Override
    public PaymentLink createPaymentLink(CreatePaymentCommand command) {
        if (command.amount() % 1000 == 13) throw new IllegalStateException("mock provider từ chối tạo link (amount % 1000 == 13)");
        String id = "mock_" + command.orderCode();
        links.put(id, new Link(command.orderCode(), command.amount()));
        String orderId = queryParam(command.returnUrl(), "orderId");
        return new PaymentLink(id, checkoutBase + "/" + (orderId != null ? orderId : command.orderCode()), null);
    }

    @Override
    public PaymentStatusResult getPaymentStatus(String providerPaymentId) {
        Link l = links.get(providerPaymentId);
        if (l == null) {
            log.warn("Mock không có link {} (app đã restart?), coi như PENDING", providerPaymentId);
            return new PaymentStatusResult(PaymentStatusResult.Status.PENDING, 0, null, null);
        }
        return new PaymentStatusResult(l.status, l.amountPaid, l.transactionRef, l.paidAt);
    }

    @Override
    public void cancelPaymentLink(String providerPaymentId, String reason) {
        Link l = links.get(providerPaymentId);
        if (l != null && l.status != PaymentStatusResult.Status.PAID) l.status = PaymentStatusResult.Status.CANCELLED;
    }

    @Override
    public PaymentEvent verifyAndParse(String rawBody, Map<String, String> headers) {
        requireValidSignature(rawBody, headers);
        JsonNode n = json.readTree(rawBody);
        String paidAt = n.path("paidAt").asText();
        return new PaymentEvent(n.path("eventId").asText(), n.path("providerPaymentId").asText(), n.path("orderCode").asLong(),
                n.path("success").asBoolean(), n.path("amount").asLong(), n.path("transactionRef").asText(),
                paidAt.isBlank() ? null : Instant.parse(paidAt), n.path("payerBankBin").asText(), n.path("payerAccountNumber").asText(), rawBody);
    }


    public String sign(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public void markPaid(String providerPaymentId, long amount, String transactionRef, Instant paidAt) {
        Link l = links.computeIfAbsent(providerPaymentId, k -> new Link(0, amount));
        l.status = PaymentStatusResult.Status.PAID;
        l.amountPaid = amount;
        l.transactionRef = transactionRef;
        l.paidAt = paidAt;
    }

    public void markExpired(String providerPaymentId) {
        Link l = links.get(providerPaymentId);
        if (l != null && l.status == PaymentStatusResult.Status.PENDING) l.status = PaymentStatusResult.Status.EXPIRED;
    }


    public static final class MockRefund {
        public final String id;
        public final String referenceId;
        public final long amount;
        public volatile RefundStatusResult.Status status = RefundStatusResult.Status.PROCESSING;
        public volatile String failureCode;

        MockRefund(String id, String referenceId, long amount) {
            this.id = id;
            this.referenceId = referenceId;
            this.amount = amount;
        }
    }

    @Override
    public synchronized RefundSubmitResult submitRefund(RefundCommand c) {
        String existingId = refundIdByReference.get(c.referenceId());
        if (existingId != null) {
            MockRefund old = refunds.get(existingId);
            return new RefundSubmitResult(old.id, old.status, "{\"replayed\":true,\"id\":\"" + old.id + "\"}");
        }
        if (c.toAccountNumber() == null || c.toAccountNumber().endsWith("000")) {
            throw new GatewayRejectedException("INVALID_DESTINATION", "mock: tài khoản thụ hưởng không hợp lệ");
        }
        long available = payoutBalance.get() - payoutReserved.get();
        if (c.amount() > available) {
            throw new GatewayRejectedException("INSUFFICIENT_PAYOUT_BALANCE", "mock: ví chi còn " + available + " < " + c.amount());
        }
        MockRefund r = new MockRefund("mock_rf_" + c.referenceId(), c.referenceId(), c.amount());
        refunds.put(r.id, r);
        refundIdByReference.put(c.referenceId(), r.id);
        payoutReserved.addAndGet(c.amount());
        if (timeoutNextSubmit.compareAndSet(true, false)) {
            throw new GatewayTimeoutException("mock: timeout sau khi provider đã nhận lệnh " + r.id);
        }
        return new RefundSubmitResult(r.id, r.status, "{\"id\":\"" + r.id + "\",\"state\":\"" + r.status + "\"}");
    }

    @Override
    public RefundStatusResult getRefundStatus(String providerRefundId) {
        MockRefund r = refunds.get(providerRefundId);
        if (r == null) throw new GatewayRejectedException("NOT_FOUND", "mock: không có lệnh chi " + providerRefundId);
        return toResult(r);
    }

    @Override
    public Optional<RefundStatusResult> findRefundByReference(String referenceId) {
        String id = refundIdByReference.get(referenceId);
        return id == null ? Optional.empty() : Optional.of(toResult(refunds.get(id)));
    }

    @Override
    public long getPayoutBalance() {
        return payoutBalance.get();
    }

    private final Map<java.util.UUID, java.util.Set<String>> terminalMethods = new ConcurrentHashMap<>();

    public void setTerminalMethods(java.util.UUID organizerId, java.util.Set<String> methods) {
        terminalMethods.put(organizerId, methods);
    }

    @Override
    public Optional<java.util.Set<String>> supportedPaymentMethods(java.util.UUID organizerId) {
        return Optional.ofNullable(terminalMethods.get(organizerId));
    }

    @Override
    public RefundEvent verifyAndParseRefund(String rawBody, Map<String, String> headers) {
        requireValidSignature(rawBody, headers);
        JsonNode n = json.readTree(rawBody);
        return new RefundEvent(n.path("eventId").asText(), n.path("providerRefundId").asText(),
                n.path("referenceId").asText(), RefundStatusResult.Status.valueOf(n.path("state").asText()),
                n.hasNonNull("failureCode") ? n.get("failureCode").asText() : null,
                n.hasNonNull("failureReason") ? n.get("failureReason").asText() : null, rawBody);
    }

    private void requireValidSignature(String rawBody, Map<String, String> headers) {
        String given = headers.get(SIGNATURE_HEADER);
        if (given == null || !MessageDigest.isEqual(sign(rawBody).getBytes(StandardCharsets.UTF_8), given.getBytes(StandardCharsets.UTF_8))) {
            throw new InvalidWebhookSignatureException("Chữ ký webhook mock không hợp lệ");
        }
    }

    private static RefundStatusResult toResult(MockRefund r) {
        return new RefundStatusResult(r.status, r.id, r.failureCode, r.failureCode == null ? null : "mock: " + r.failureCode, null);
    }


    public synchronized MockRefund settleRefund(String providerRefundId, RefundStatusResult.Status next, String failureCode) {
        MockRefund r = refunds.get(providerRefundId);
        if (r == null) throw new IllegalArgumentException("mock: không có lệnh chi " + providerRefundId);
        if (r.status == RefundStatusResult.Status.PROCESSING || r.status == RefundStatusResult.Status.ON_HOLD) {
            if (next == RefundStatusResult.Status.SUCCEEDED) {
                payoutBalance.addAndGet(-r.amount);
                payoutReserved.addAndGet(-r.amount);
            }
            if (next == RefundStatusResult.Status.FAILED || next == RefundStatusResult.Status.CANCELLED) {
                payoutReserved.addAndGet(-r.amount);
            }
            r.status = next;
            r.failureCode = failureCode;
        }
        return r;
    }

    public void setPayoutBalance(long balance) { payoutBalance.set(balance); }

    public long getPayoutReserved() { return payoutReserved.get(); }

    public void timeoutNextSubmit() { timeoutNextSubmit.set(true); }

    private static String queryParam(String url, String name) {
        String query = url == null ? null : URI.create(url).getQuery();
        if (query == null) return null;
        for (String kv : query.split("&")) {
            if (kv.startsWith(name + "=")) return kv.substring(name.length() + 1);
        }
        return null;
    }
}
