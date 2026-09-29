package com.example.demo.infrastructure.security;

import com.example.demo.domain.common.DomainException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Verify id_token Google trả cho FE. Payload chưa verify thì KHÔNG được tin — ai cũng bịa được JWT chứa email người khác.
 * Kiểm 4 thứ: chữ ký (public key ở {@link #JWKS_URI}), {@code aud} = client id của app, {@code iss} là Google, còn hạn.
 * Không cần client secret; secret chỉ dùng cho luồng redirect.
 */
@Component
public class GoogleIdTokenVerifier {

    private static final String JWKS_URI = "https://www.googleapis.com/oauth2/v3/certs";
    private static final List<String> ISSUERS = List.of("https://accounts.google.com", "accounts.google.com");

    /** Thông tin lấy từ id_token sau khi đã verify. */
    public record GoogleAccount(String email, String fullName, String pictureUrl) {}

    /** null khi chưa cấu hình GOOGLE_CLIENT_ID → tính năng tắt, không làm app chết lúc khởi động. */
    private final JwtDecoder decoder;

    public GoogleIdTokenVerifier(@Value("${app.auth.google.client-id:}") String clientId) {
        String id = clientId == null ? "" : clientId.trim();
        this.decoder = id.isEmpty() ? null : buildDecoder(id);
    }

    public GoogleAccount verify(String idToken) {
        if (decoder == null) {
            throw DomainException.badRequest("GOOGLE_LOGIN_DISABLED",
                    "Server chưa cấu hình GOOGLE_CLIENT_ID nên chưa bật đăng nhập Google");
        }

        Jwt jwt;
        try {
            jwt = decoder.decode(idToken);   // sai chữ ký / hết hạn / sai aud đều ném JwtException
        } catch (JwtException e) {
            throw DomainException.unauthorized("GOOGLE_TOKEN_INVALID", "Token Google không hợp lệ hoặc đã hết hạn");
        }

        // Google Workspace có thể trả email chưa xác minh; email chưa xác minh mà cho đăng nhập
        // thì người khác đăng ký trùng email là chiếm được tài khoản.
        if (!Boolean.TRUE.equals(jwt.getClaimAsBoolean("email_verified"))) {
            throw DomainException.unauthorized("GOOGLE_EMAIL_NOT_VERIFIED", "Email Google này chưa được xác minh");
        }

        String email = jwt.getClaimAsString("email");
        if (email == null || email.isBlank()) {
            throw DomainException.unauthorized("GOOGLE_TOKEN_INVALID", "Token Google không kèm email");
        }
        return new GoogleAccount(email, jwt.getClaimAsString("name"), jwt.getClaimAsString("picture"));
    }

    private static JwtDecoder buildDecoder(String clientId) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder
                .withJwkSetUri(JWKS_URI)
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(),
                new JwtClaimValidator<List<String>>(JwtClaimNames.AUD, aud -> aud != null && aud.contains(clientId)),
                new JwtClaimValidator<String>(JwtClaimNames.ISS, ISSUERS::contains)));
        return decoder;
    }
}
