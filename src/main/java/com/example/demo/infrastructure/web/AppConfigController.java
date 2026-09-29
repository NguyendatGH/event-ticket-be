package com.example.demo.infrastructure.web;

import com.example.demo.application.dto.AppConfigResponse;
import com.example.demo.domain.payment.BankBins;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /api/v1/config: public. Trả cấu hình FE cần biết (application.yaml / .env)
 * để FE không phải chép cứng con số này. Chỉ đọc cấu hình, không có nghiệp vụ nên không cần service riêng.
 */
@RestController
@Tag(name = "Config", description = "Cấu hình nghiệp vụ cho FE")
public class AppConfigController {

    private final long checkoutFee;
    private final String googleClientId;

    public AppConfigController(@Value("${app.checkout.fee}") long checkoutFee,
                               @Value("${app.auth.google.client-id:}") String googleClientId) {
        this.checkoutFee = checkoutFee;
        // Client ID là thông tin công khai (Google bắt buộc lộ ở trình duyệt), khác client secret.
        this.googleClientId = googleClientId == null || googleClientId.isBlank() ? null : googleClientId.trim();
    }

    @GetMapping("/api/v1/config")
    @SecurityRequirements
    @Operation(summary = "Cấu hình nghiệp vụ", description = "Phí dịch vụ mỗi đơn, Google Client ID, danh sách ngân hàng cho refund")
    public AppConfigResponse get() {
        return new AppConfigResponse(checkoutFee, googleClientId, BankBins.all());
    }
}
