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

    default java.util.Optional<java.util.Set<String>> payoutableBankBins() {
        return java.util.Optional.empty();
    }

    default java.util.Optional<java.util.Set<String>> supportedPaymentMethods(java.util.UUID organizerId) {
        return java.util.Optional.empty();
    }

    default void forgetPaymentMethods(java.util.UUID organizerId) {
    }

    RefundEvent verifyAndParseRefund(String rawBody, Map<String, String> headers);
}
