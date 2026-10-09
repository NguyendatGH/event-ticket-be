package com.example.demo.application;

import com.example.demo.application.dto.PublicPaymentMethodsResponse;
import com.example.demo.domain.payment.MerchantGateway;

import java.util.List;
import java.util.UUID;

public interface PaymentMethodsService {

    Resolved resolve(UUID organizerId);

    PublicPaymentMethodsResponse publicMethods(UUID organizerId);

    record Resolved(MerchantGateway gateway, List<String> paymentMethods) {
        public boolean supports(String method) {
            return paymentMethods.contains(method);
        }
    }
}
