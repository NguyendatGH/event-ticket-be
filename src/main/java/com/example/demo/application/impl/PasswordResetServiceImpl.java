package com.example.demo.application.impl;

import com.example.demo.application.AuthService;
import com.example.demo.application.PasswordResetService;
import com.example.demo.application.UserService;
import com.example.demo.application.dto.ForgotPasswordResponse;
import com.example.demo.domain.auth.PasswordResetToken;
import com.example.demo.domain.common.DomainException;
import com.example.demo.domain.user.User;
import com.example.demo.infrastructure.mail.Mailer;
import com.example.demo.infrastructure.persistence.PasswordResetTokenRepository;
import com.example.demo.infrastructure.persistence.RefreshTokenRepository;
import com.example.demo.infrastructure.persistence.UserRepository;
import com.example.demo.infrastructure.security.OpaqueTokens;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

/**
 * Quên / đặt lại mật khẩu. Controller: AuthController (POST /auth/forgot-password, /auth/reset-password).
 * Token đặt lại là opaque, dùng một lần, DB chỉ lưu sha256 (giống refresh token).
 */
@Service
public class PasswordResetServiceImpl implements PasswordResetService {

    private final UserService userService;
    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordResetTokenRepository resetTokens;
    private final Mailer mailer;
    private final Environment env;
    private final Duration resetTtl;
    private final String resetPasswordUrl;

    public PasswordResetServiceImpl(UserService userService,
                                UserRepository users,
                                PasswordEncoder passwordEncoder,
                                RefreshTokenRepository refreshTokens,
                                PasswordResetTokenRepository resetTokens,
                                Mailer mailer,
                                Environment env,
                                @Value("${app.auth.reset-token-ttl:30m}") Duration resetTtl,
                                @Value("${app.auth.reset-password-url}") String resetPasswordUrl) {
        this.userService = userService;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.refreshTokens = refreshTokens;
        this.resetTokens = resetTokens;
        this.mailer = mailer;
        this.env = env;
        this.resetTtl = resetTtl;
        this.resetPasswordUrl = resetPasswordUrl;
    }

    /**
     * Luôn trả cùng một kết quả dù email có tồn tại hay không (không cho dò email nào đã đăng ký).
     * Email có thật: hủy link cũ chưa dùng, tạo token mới và gửi link qua Mailer.
     */
    @Override
    @Transactional
    public ForgotPasswordResponse forgotPassword(String email) {
        String resetUrl = users.findByEmail(AuthService.normalizeEmail(email)).map(user -> {
            Instant now = Instant.now();
            resetTokens.invalidateUnused(user.getId(), now);
            String raw = OpaqueTokens.generate();
            resetTokens.save(PasswordResetToken.create(user.getId(), OpaqueTokens.sha256Hex(raw), now.plus(resetTtl)));
            String url = resetPasswordUrl + "?token=" + raw;
            mailer.sendPasswordReset(user.getEmail(), url);
            return url;
        }).orElse(null);
        // Link chỉ lộ ra response ở profile dev (để test khi chưa có email thật); email lạ thì không có link
        String devResetUrl = env.matchesProfiles("dev") ? resetUrl : null;
        return new ForgotPasswordResponse(true, resetTtl.toMinutes(), devResetUrl);
    }

    /** 400 TOKEN_INVALID (lạ/đã dùng), 410 TOKEN_EXPIRED. Đổi mật khẩu và revoke mọi refresh token (đăng xuất mọi thiết bị). */
    @Override
    @Transactional
    public void resetPassword(String rawToken, String newPassword) {
        PasswordResetToken token = resetTokens.findByTokenHash(OpaqueTokens.sha256Hex(rawToken))
                .filter(t -> !t.isUsed())
                .orElseThrow(() -> DomainException.badRequest("TOKEN_INVALID", "Link đặt lại mật khẩu không hợp lệ hoặc đã được dùng"));
        if (token.isExpired(Instant.now())) {
            throw DomainException.gone("TOKEN_EXPIRED", "Link đặt lại mật khẩu đã hết hạn, vui lòng yêu cầu link mới");
        }
        token.markUsed();
        User user = userService.requireUser(token.getUserId());
        user.changePasswordHash(passwordEncoder.encode(newPassword));
        refreshTokens.revokeAllByUserId(user.getId(), Instant.now());
    }
}
