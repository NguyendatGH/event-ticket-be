package com.example.demo.infrastructure.gateway.payos;

import com.example.demo.domain.payment.GatewayRejectedException;
import com.example.demo.domain.payment.GatewayTimeoutException;
import com.example.demo.domain.payment.RefundCommand;
import com.example.demo.domain.payment.RefundStatusResult;
import com.example.demo.domain.payment.RefundSubmitResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import vn.payos.PayOS;
import vn.payos.core.ClientOptions;
import vn.payos.core.Page;
import vn.payos.exception.APIException;
import vn.payos.exception.ConnectionException;
import vn.payos.exception.ConnectionTimeoutException;
import vn.payos.model.v1.payouts.GetPayoutListParams;
import vn.payos.model.v1.payouts.Payout;
import vn.payos.model.v1.payouts.PayoutApprovalState;
import vn.payos.model.v1.payouts.PayoutRequests;
import vn.payos.model.v1.payouts.PayoutTransaction;
import vn.payos.model.v1.payoutsAccount.PayoutAccountInfo;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

@Component
@Profile("!test")
public class PayOsPayoutClient {

    private static final Logger log = LoggerFactory.getLogger(PayOsPayoutClient.class);
    private static final String DEFAULT_BASE_URL = "https://api-merchant.payos.vn";

    private final String clientId;
    private final String apiKey;
    private final String checksumKey;
    private final String baseUrl;
    private volatile PayOS sdk;

    public PayOsPayoutClient(@Value("${app.payos.payout.client-id:}") String clientId,
                             @Value("${app.payos.payout.api-key:}") String apiKey,
                             @Value("${app.payos.payout.checksum-key:}") String checksumKey,
                             @Value("${app.payos.payout.base-url:}") String baseUrl) {
        this.clientId = clientId;
        this.apiKey = apiKey;
        this.checksumKey = checksumKey;
        this.baseUrl = baseUrl;
    }

    public boolean configured() {
        return notBlank(clientId) && notBlank(apiKey) && notBlank(checksumKey);
    }

    public RefundSubmitResult submit(RefundCommand command) {
        PayoutRequests body = PayoutRequests.builder()
                .referenceId(command.referenceId())
                .amount(command.amount())
                .description(command.description())
                .toBin(command.toBin())
                .toAccountNumber(command.toAccountNumber())
                .build();
        Payout p = call("create payout", () -> sdk().payouts().create(body, command.referenceId()));
        RefundStatusResult mapped = map(p);
        log.info("Lệnh chi {} gửi PayOS: id={} state={}", command.referenceId(), p.getId(), mapped.status());
        return new RefundSubmitResult(p.getId(), mapped.status(), "id=" + p.getId() + " approvalState=" + p.getApprovalState());
    }

    public RefundStatusResult status(String providerRefundId) {
        return map(call("get payout", () -> sdk().payouts().get(providerRefundId)));
    }

    public Optional<RefundStatusResult> findByReference(String referenceId) {
        GetPayoutListParams params = GetPayoutListParams.builder().referenceId(referenceId).limit(10).build();
        Page<Payout> page = call("list payouts", () -> sdk().payouts().list(params));
        List<Payout> items = page.getItems();
        if (items == null) return Optional.empty();
        return items.stream().filter(p -> referenceId.equals(p.getReferenceId())).findFirst().map(PayOsPayoutClient::map);
    }

    public long balance() {
        PayoutAccountInfo info = call("payout balance", () -> sdk().payoutsAccount().balance());
        String raw = info.getBalance() == null ? "" : info.getBalance().trim();
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            try {
                return (long) Double.parseDouble(raw);
            } catch (NumberFormatException e2) {
                throw new GatewayTimeoutException("PayOS trả số dư ví không đọc được: " + raw, e2);
            }
        }
    }

    private PayOS sdk() {
        PayOS s = sdk;
        if (s == null) {
            synchronized (this) {
                s = sdk;
                if (s == null) {
                    if (!configured()) {
                        throw new GatewayRejectedException("PAYOUT_NOT_CONFIGURED",
                                "Chưa cấu hình kênh chi PayOS (PAYOS_PAYOUT_CLIENT_ID / PAYOS_PAYOUT_API_KEY / PAYOS_PAYOUT_CHECKSUM_KEY)");
                    }
                    s = new PayOS(ClientOptions.builder()
                            .clientId(clientId.trim())
                            .apiKey(apiKey.trim())
                            .checksumKey(checksumKey.trim())
                            .baseURL(notBlank(baseUrl) ? baseUrl.trim() : DEFAULT_BASE_URL)
                            .build());
                    sdk = s;
                    log.info("PayOS payout client sẵn sàng");
                }
            }
        }
        return s;
    }

    private static <T> T call(String op, Supplier<T> action) {
        try {
            return action.get();
        } catch (GatewayRejectedException | GatewayTimeoutException e) {
            throw e;
        } catch (ConnectionTimeoutException | ConnectionException e) {
            throw new GatewayTimeoutException("PayOS " + op + " lỗi kết nối: " + e.getMessage(), e);
        } catch (APIException e) {
            int status = e.getStatusCode().orElse(0);
            String code = e.getErrorCode().orElse(null);
            String desc = e.getErrorDesc().orElse(e.getMessage());
            boolean definitive = status >= 200 && status < 500 && status != 429 && status != 408;
            if (definitive) throw new GatewayRejectedException(normalize(code, desc), "PayOS " + op + ": " + desc);
            throw new GatewayTimeoutException("PayOS " + op + " lỗi " + status + ": " + desc, e);
        } catch (RuntimeException e) {
            throw new GatewayTimeoutException("PayOS " + op + " thất bại: " + e.getMessage(), e);
        }
    }

    static String normalize(String payosCode, String description) {
        String c = payosCode == null ? "" : payosCode.toUpperCase();
        String m = description == null ? "" : description.toLowerCase();
        if (c.contains("BALANCE") || c.contains("INSUFFICIENT") || m.contains("insufficient") || m.contains("số dư")) {
            return "INSUFFICIENT_PAYOUT_BALANCE";
        }
        if (m.contains("tài khoản") || m.contains("account") || m.contains("bin")) return "INVALID_DESTINATION";
        if (c.contains("LIMIT") || m.contains("hạn mức") || m.contains("limit")) return "LIMIT_EXCEEDED";
        return "REJECTED";
    }

    static RefundStatusResult map(Payout p) {
        List<PayoutTransaction> txs = p.getTransactions();
        PayoutTransaction tx = txs == null || txs.isEmpty() ? null : txs.get(txs.size() - 1);
        RefundStatusResult.Status status = tx != null && tx.getState() != null
                ? RefundStatusResult.Status.valueOf(tx.getState().name())
                : fromApproval(p.getApprovalState());
        return new RefundStatusResult(status, p.getId(),
                tx == null ? null : tx.getErrorCode(),
                tx == null ? null : tx.getErrorMessage(),
                null);
    }

    private static RefundStatusResult.Status fromApproval(PayoutApprovalState approval) {
        if (approval == null) return RefundStatusResult.Status.PROCESSING;
        return switch (approval) {
            case COMPLETED -> RefundStatusResult.Status.SUCCEEDED;
            case REJECTED, FAILED -> RefundStatusResult.Status.FAILED;
            case CANCELLED -> RefundStatusResult.Status.CANCELLED;
            case PARTIAL_COMPLETED -> RefundStatusResult.Status.ON_HOLD;
            case DRAFTING, SUBMITTED, APPROVED, SCHEDULED, PROCESSING -> RefundStatusResult.Status.PROCESSING;
        };
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
