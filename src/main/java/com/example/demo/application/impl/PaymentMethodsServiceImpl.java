package com.example.demo.application.impl;

import com.example.demo.application.PaymentGatewayRegistry;
import com.example.demo.application.PaymentMethodsService;
import com.example.demo.application.dto.PublicPaymentMethodsResponse;
import com.example.demo.domain.payment.MerchantGateway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class PaymentMethodsServiceImpl implements PaymentMethodsService {
    private static final List<String> ORDER = List.of("CARD", "QR", "PAYNOW", "GOOGLE_PAY", "APPLE_PAY");
    private static final List<String> FALLBACK = List.of("CARD");

    private final PaymentGatewayRegistry gateways;
    private final MerchantGateway configuredDefault;

    public PaymentMethodsServiceImpl(PaymentGatewayRegistry gateways,
                                     @Value("${app.payment.default-gateway:PAYOS}") MerchantGateway configuredDefault) {
        this.gateways = gateways;
        this.configuredDefault = configuredDefault;
    }

    @Override
    public Resolved resolve(UUID organizerId) {
        MerchantGateway gateway = gateways.platformGateway(configuredDefault);
        Optional<Set<String>> enabled = gateways.forMerchant(gateway).supportedPaymentMethods(organizerId);
        if (enabled.isEmpty()) return new Resolved(gateway, FALLBACK);
        return new Resolved(gateway, ORDER.stream().filter(enabled.get()::contains).toList());
    }

    @Override
    public PublicPaymentMethodsResponse publicMethods(UUID organizerId) {
        Resolved resolved = resolve(organizerId);
        return new PublicPaymentMethodsResponse(resolved.gateway(), resolved.paymentMethods());
    }
}
