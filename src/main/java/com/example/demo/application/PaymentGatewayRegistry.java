package com.example.demo.application;

import com.example.demo.domain.common.DomainException;
import com.example.demo.domain.payment.MerchantGateway;
import com.example.demo.domain.payment.PaymentGatewayPort;
import com.example.demo.domain.payment.PaymentProvider;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Component
public class PaymentGatewayRegistry {
    private final Map<PaymentProvider, PaymentGatewayPort> byProvider = new EnumMap<>(PaymentProvider.class);

    public PaymentGatewayRegistry(List<PaymentGatewayPort> gateways) {
        gateways.forEach(g -> byProvider.put(g.provider(), g));
    }

    public PaymentGatewayPort forProvider(PaymentProvider provider) {
        PaymentGatewayPort gateway = byProvider.get(provider);
        if (gateway == null) throw DomainException.notFound("PROVIDER_INACTIVE", "Provider " + provider + " không hoạt động");
        return gateway;
    }

    public PaymentGatewayPort forMerchant(MerchantGateway gateway) {
        return forProvider(providerOf(gateway));
    }

    public PaymentGatewayPort defaultGateway() {
        return byProvider.values().stream().findFirst()
                .orElseThrow(() -> DomainException.notFound("PROVIDER_INACTIVE", "Không có payment gateway nào được bật"));
    }

    public PaymentGatewayPort defaultGateway(MerchantGateway preferred) {
        if (preferred != null) {
            PaymentGatewayPort configured = byProvider.get(providerOf(preferred));
            if (configured != null) return configured;
        }
        return defaultGateway();
    }

    public MerchantGateway platformGateway(MerchantGateway configured) {
        List<MerchantGateway> available = availableMerchantGateways();
        if (available.isEmpty()) throw DomainException.notFound("PROVIDER_INACTIVE", "Không có payment gateway nào được bật");
        return configured != null && available.contains(configured) ? configured : available.getFirst();
    }

    public List<MerchantGateway> availableMerchantGateways() {
        return byProvider.keySet().stream().map(this::merchantGatewayOf).distinct().sorted().toList();
    }

    public MerchantGateway merchantGatewayOf(PaymentProvider provider) {
        return provider == PaymentProvider.PAYOS ? MerchantGateway.PAYOS : MerchantGateway.BANKSIM;
    }

    public PaymentProvider providerOf(MerchantGateway gateway) {
        return gateway == MerchantGateway.PAYOS ? PaymentProvider.PAYOS : PaymentProvider.MOCK;
    }
}
