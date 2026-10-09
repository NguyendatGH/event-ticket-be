package com.example.demo.domain.payment;

import java.util.Map;
import java.util.Optional;

public interface PaymentGatewayPort {

    PaymentProvider provider();

    PaymentLink createPaymentLink(CreatePaymentCommand command);

    PaymentStatusResult getPaymentStatus(String providerPaymentId);

    void cancelPaymentLink(String providerPaymentId, String reason);

    PaymentEvent verifyAndParse(String rawBody, Map<String, String> headers);

    RefundSubmitResult submitRefund(RefundCommand command);

    RefundStatusResult getRefundStatus(String providerRefundId);

    Optional<RefundStatusResult> findRefundByReference(String referenceId);

    long getPayoutBalance();

    RefundEvent verifyAndParseRefund(String rawBody, Map<String, String> headers);
}
