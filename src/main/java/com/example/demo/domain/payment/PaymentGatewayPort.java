package com.example.demo.domain.payment;

import java.util.Map;
import java.util.Optional;

/**
 * Cổng ra cổng thanh toán. Adapter thật: infrastructure.gateway.payos; test dùng MockPaymentGateway.
 * Không gọi các method này bên trong DB transaction.
 */
public interface PaymentGatewayPort {

    PaymentProvider provider();

    /** Tạo link thanh toán. Ném RuntimeException nếu provider từ chối; caller hủy order và trả kho. */
    PaymentLink createPaymentLink(CreatePaymentCommand command);

    PaymentStatusResult getPaymentStatus(String providerPaymentId);

    /** Hủy link tại provider (order hết hạn/hủy). Lỗi ở đây chỉ log, không chặn nghiệp vụ. */
    void cancelPaymentLink(String providerPaymentId, String reason);

    /**
     * Verify chữ ký trên raw body và chuẩn hóa thành PaymentEvent.
     * @throws InvalidWebhookSignatureException khi chữ ký sai
     */
    PaymentEvent verifyAndParse(String rawBody, Map<String, String> headers);

    /**
     * Gửi lệnh hoàn. Provider phải idempotent theo {@code command.referenceId()}.
     * @throws GatewayRejectedException provider từ chối dứt khoát (4xx), tiền CHƯA đi, được gửi lại bằng key mới
     * @throws GatewayTimeoutException  không rõ kết quả; caller GIỮ NGUYÊN key, RefundRecoveryJob tra lại theo key
     */
    RefundSubmitResult submitRefund(RefundCommand command);

    RefundStatusResult getRefundStatus(String providerRefundId);

    /** Tra lệnh theo referenceId sau timeout: có thì provider đã nhận, không thì gửi lại cùng key. */
    Optional<RefundStatusResult> findRefundByReference(String referenceId);

    /** Số dư ví chi hiện có ở provider (VND). */
    long getPayoutBalance();

    /**
     * Verify và chuẩn hóa webhook kết quả refund. Provider không có webhook lệnh chi (PayOS)
     * ném DomainException 404 REFUND_WEBHOOK_UNSUPPORTED.
     */
    RefundEvent verifyAndParseRefund(String rawBody, Map<String, String> headers);
}
