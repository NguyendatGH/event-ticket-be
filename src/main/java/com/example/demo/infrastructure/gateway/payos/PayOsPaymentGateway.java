package com.example.demo.infrastructure.gateway.payos;

import com.example.demo.domain.common.VietnamTime;
import com.example.demo.domain.common.DomainException;
import com.example.demo.domain.payment.CreatePaymentCommand;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import vn.payos.PayOS;
import vn.payos.exception.APIException;
import vn.payos.exception.PayOSException;
import vn.payos.exception.WebhookException;
import vn.payos.model.v2.paymentRequests.CreatePaymentLinkRequest;
import vn.payos.model.v2.paymentRequests.CreatePaymentLinkResponse;
import vn.payos.model.v2.paymentRequests.PaymentLinkItem;
import vn.payos.model.v2.paymentRequests.Transaction;
import vn.payos.model.webhooks.Webhook;
import vn.payos.model.webhooks.WebhookData;
import vn.payos.util.DataConverterUtils;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

@Component
@Profile("!test")
public class PayOsPaymentGateway implements PaymentGatewayPort {

    private static final Logger log = LoggerFactory.getLogger(PayOsPaymentGateway.class);
    private static final DateTimeFormatter PAYOS_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int DESCRIPTION_MAX = 25;

    private final PayOS client;
    private final String webhookUrl;
    private final PayOsPayoutClient payout;

    @Autowired
    public PayOsPaymentGateway(@Value("${app.payos.client-id}") String clientId,
                               @Value("${app.payos.api-key}") String apiKey,
                               @Value("${app.payos.checksum-key}") String checksumKey,
                               @Value("${app.payos.webhook-url:}") String webhookUrl,
                               PayOsPayoutClient payout) {
        this(new PayOS(clientId, apiKey, checksumKey), webhookUrl, payout);
    }

    PayOsPaymentGateway(PayOS client, String webhookUrl) {
        this(client, webhookUrl, null);
    }

    PayOsPaymentGateway(PayOS client, String webhookUrl, PayOsPayoutClient payout) {
        this.client = client;
        this.webhookUrl = webhookUrl;
        this.payout = payout;
    }

    @Override
    public PaymentProvider provider() {
        return PaymentProvider.PAYOS;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void confirmWebhook() {
        if (webhookUrl == null || webhookUrl.isBlank()) return;
        try {
            var res = client.webhooks().confirm(webhookUrl);
            log.info("PayOS confirm-webhook OK: url={} merchant={}", res.getWebhookUrl(), res.getName());
        } catch (PayOSException e) {
            log.error("PayOS confirm-webhook thất bại cho {}: {}", webhookUrl, describe(e));
        }
    }

    @Override
    public PaymentLink createPaymentLink(CreatePaymentCommand c) {
        CreatePaymentLinkRequest req = CreatePaymentLinkRequest.builder()
                .orderCode(c.orderCode())
                .amount(c.amount())
                .description(truncate(c.description()))
                .returnUrl(c.returnUrl())
                .cancelUrl(c.cancelUrl())
                .expiredAt(c.expiresAt() == null ? null : c.expiresAt().getEpochSecond())
                .items(c.items().stream()
                        .map(i -> PaymentLinkItem.builder().name(i.name()).quantity(i.quantity()).price(i.price()).build())
                        .toList())
                .build();
        CreatePaymentLinkResponse res = call("create payment link", () -> client.paymentRequests().create(req));
        return new PaymentLink(res.getPaymentLinkId(), res.getCheckoutUrl(), res.getQrCode());
    }

    @Override
    public PaymentStatusResult getPaymentStatus(String paymentLinkId) {
        var link = call("get payment link", () -> client.paymentRequests().get(paymentLinkId));
        List<Transaction> txs = link.getTransactions();
        Transaction last = txs == null || txs.isEmpty() ? null : txs.get(txs.size() - 1);
        return new PaymentStatusResult(
                mapStatus(link.getStatus()),
                link.getAmountPaid() == null ? 0 : link.getAmountPaid(),
                last == null ? null : last.getReference(),
                last == null ? null : parseTime(last.getTransactionDateTime()));
    }

    @Override
    public void cancelPaymentLink(String paymentLinkId, String reason) {
        call("cancel payment link", () -> client.paymentRequests().cancel(paymentLinkId, reason));
    }

    @Override
    public PaymentEvent verifyAndParse(String rawBody, Map<String, String> headers) {
        Webhook webhook;
        try {
            webhook = DataConverterUtils.normalize(rawBody, Webhook.class);
        } catch (IllegalArgumentException e) {
            throw DomainException.badRequest("INVALID_WEBHOOK", "Body không đúng định dạng webhook PayOS");
        }
        if (webhook.getData() == null || webhook.getSignature() == null) {
            throw DomainException.badRequest("INVALID_WEBHOOK", "Webhook PayOS thiếu data hoặc signature");
        }
        WebhookData d;
        try {
            d = client.webhooks().verify(webhook);
        } catch (WebhookException e) {
            throw new InvalidWebhookSignatureException("Chữ ký webhook PayOS không hợp lệ");
        }
        boolean success = "00".equals(webhook.getCode()) && Boolean.TRUE.equals(webhook.getSuccess());
        return new PaymentEvent(
                d.getPaymentLinkId() + ":" + d.getReference(),
                d.getPaymentLinkId(),
                d.getOrderCode(),
                success,
                d.getAmount(),
                d.getReference(),
                parseTime(d.getTransactionDateTime()),
                d.getCounterAccountBankId(),
                d.getCounterAccountNumber(),
                rawBody);
    }

    @Override
    public RefundSubmitResult submitRefund(RefundCommand command) {
        return payout().submit(command);
    }

    @Override
    public RefundStatusResult getRefundStatus(String providerRefundId) {
        return payout().status(providerRefundId);
    }

    @Override
    public Optional<RefundStatusResult> findRefundByReference(String referenceId) {
        return payout().findByReference(referenceId);
    }

    @Override
    public long getPayoutBalance() {
        return payout().balance();
    }

    @Override
    public RefundEvent verifyAndParseRefund(String rawBody, Map<String, String> headers) {
        throw DomainException.notFound("REFUND_WEBHOOK_UNSUPPORTED",
                "PayOS không gửi webhook cho lệnh chi; kết quả refund được lấy bằng poll");
    }

    private PayOsPayoutClient payout() {
        if (payout == null) throw new IllegalStateException("Adapter này được dựng không có kênh chi");
        return payout;
    }

    static String truncate(String description) {
        if (description == null) return "";
        return description.length() <= DESCRIPTION_MAX ? description : description.substring(0, DESCRIPTION_MAX);
    }

    static Instant parseTime(String payosDateTime) {
        if (payosDateTime == null || payosDateTime.isBlank()) return null;
        String raw = payosDateTime.trim();
        try {
            return OffsetDateTime.parse(raw).toInstant();
        } catch (DateTimeParseException notIso) {
            return LocalDateTime.parse(raw, PAYOS_TIME).atZone(VietnamTime.ZONE).toInstant();
        }
    }

    private static PaymentStatusResult.Status mapStatus(vn.payos.model.v2.paymentRequests.PaymentLinkStatus s) {
        return switch (s) {
            case PAID -> PaymentStatusResult.Status.PAID;
            case CANCELLED, FAILED -> PaymentStatusResult.Status.CANCELLED;
            case EXPIRED -> PaymentStatusResult.Status.EXPIRED;
            case PENDING, UNDERPAID, PROCESSING -> PaymentStatusResult.Status.PENDING;
        };
    }

    private static <T> T call(String op, Supplier<T> action) {
        try {
            return action.get();
        } catch (PayOSException e) {
            throw new RuntimeException("PayOS " + op + " thất bại: " + describe(e), e);
        }
    }

    private static String describe(PayOSException e) {
        if (e instanceof APIException api) {
            return api.getErrorCode().orElse("?") + " " + api.getErrorDesc().orElse(e.getMessage());
        }
        return e.getMessage();
    }
}
