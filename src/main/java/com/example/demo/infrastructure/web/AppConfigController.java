package com.example.demo.infrastructure.web;

import com.example.demo.application.PaymentGatewayRegistry;
import com.example.demo.application.dto.AppConfigResponse;
import com.example.demo.domain.payment.MerchantGateway;
import com.example.demo.domain.payment.BankBins;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;
import java.util.Set;

@RestController
@Tag(name = "Config", description = "Cấu hình nghiệp vụ cho FE")
public class AppConfigController {

    private final long checkoutFee;
    private final String googleClientId;
    private final PaymentGatewayRegistry gateways;
    private final MerchantGateway configuredDefault;

    public AppConfigController(@Value("${app.checkout.fee}") long checkoutFee,
                               @Value("${app.auth.google.client-id:}") String googleClientId,
                               PaymentGatewayRegistry gateways,
                               @Value("${app.payment.default-gateway:PAYOS}") MerchantGateway configuredDefault) {
        this.checkoutFee = checkoutFee;
        this.googleClientId = googleClientId == null || googleClientId.isBlank() ? null : googleClientId.trim();
        this.gateways = gateways;
        this.configuredDefault = configuredDefault;
    }

    @GetMapping("/api/v1/config")
    @SecurityRequirements
    @Operation(summary = "Cấu hình nghiệp vụ", description = "Phí dịch vụ, Google Client ID, danh sách ngân hàng kèm cờ supported")
    public AppConfigResponse get() {
        Optional<Set<String>> payoutable = gateways.defaultGateway(configuredDefault).payoutableBankBins();
        List<AppConfigResponse.BankOption> banks = BankBins.all().stream()
                .map(b -> new AppConfigResponse.BankOption(b.bin(), b.name(),
                        payoutable.map(bins -> bins.contains(b.bin())).orElse(true)))
                .toList();
        return new AppConfigResponse(checkoutFee, googleClientId, banks);
    }
}
