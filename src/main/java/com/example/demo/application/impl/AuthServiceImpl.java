package com.example.demo.application.impl;

import com.example.demo.application.AuthService;
import com.example.demo.application.OrganizerService;
import com.example.demo.application.UserService;
import com.example.demo.application.dto.AuthResponse;
import com.example.demo.application.dto.LoginRequest;
import com.example.demo.application.dto.OrganizerProfileRequest;
import com.example.demo.application.dto.RegisterOrganizerRequest;
import com.example.demo.application.dto.RegisterRequest;
import com.example.demo.domain.auth.RefreshToken;
import com.example.demo.domain.common.DomainException;
import com.example.demo.domain.user.User;
import com.example.demo.domain.user.UserRole;
import com.example.demo.infrastructure.persistence.OrganizerRepository;
import com.example.demo.infrastructure.persistence.RefreshTokenRepository;
import com.example.demo.infrastructure.persistence.UserRepository;
import com.example.demo.infrastructure.security.GoogleIdTokenVerifier;
import com.example.demo.infrastructure.security.JwtService;
import com.example.demo.infrastructure.security.OpaqueTokens;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Service
public class AuthServiceImpl implements AuthService {

    private final UserRepository users;
    private final UserService userService;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final OrganizerRepository organizers;
    private final OrganizerService organizerService;
    private final RefreshTokenRepository refreshTokens;
    private final GoogleIdTokenVerifier googleIdTokenVerifier;
    private final Duration refreshTtl;

    public AuthServiceImpl(UserRepository users,
                           UserService userService,
                           PasswordEncoder passwordEncoder,
                           AuthenticationManager authenticationManager,
                           JwtService jwtService,
                           OrganizerRepository organizers,
                           OrganizerService organizerService,
                           RefreshTokenRepository refreshTokens,
                           GoogleIdTokenVerifier googleIdTokenVerifier,
                           @Value("${app.auth.refresh-token-ttl:14d}") Duration refreshTtl) {
        this.users = users;
        this.userService = userService;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
        this.organizers = organizers;
        this.organizerService = organizerService;
        this.refreshTokens = refreshTokens;
        this.googleIdTokenVerifier = googleIdTokenVerifier;
        this.refreshTtl = refreshTtl;
    }

    /** Tạo user role CUSTOMER và phát phiên. 409 EMAIL_ALREADY_USED nếu email đã tồn tại. */
    @Transactional
    @Override
    public AuthResponse register(RegisterRequest req) {
        return session(createUser(req.fullName(), req.email(), req.password(), UserRole.CUSTOMER));
    }

    /** Một transaction: user ORGANIZER + hồ sơ BTC (slug duy nhất từ tên), rồi phát phiên. */
    @Transactional
    @Override
    public AuthResponse registerOrganizer(RegisterOrganizerRequest req) {
        User user = createUser(req.fullName(), req.email(), req.password(), UserRole.ORGANIZER);
        organizerService.create(user.getId(), new OrganizerProfileRequest(req.organizerName(), req.organizerDescription(),
                null, null, req.website(), req.city(), null, req.contactPhone()));
        return session(user);
    }

    /** Kiểm tra email/password, phát phiên. Sai → BadCredentialsException (GlobalExceptionHandler trả 401 BAD_CREDENTIALS). */
    @Transactional
    @Override
    public AuthResponse login(LoginRequest req) {
        String email = AuthService.normalizeEmail(req.email());
        // Spring Security so khớp hash BCrypt; ném BadCredentialsException nếu email không tồn tại hoặc password sai
        authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(email, req.password()));

        User user = users.findByEmail(email).orElseThrow();
        return session(user);
    }

    @Transactional
    @Override
    public AuthResponse loginWithGoogle(String idToken) {
        GoogleIdTokenVerifier.GoogleAccount account = googleIdTokenVerifier.verify(idToken);
        String email = AuthService.normalizeEmail(account.email());

        User user = users.findByEmail(email).orElseGet(() -> createGoogleUser(account, email));
        return session(user);
    }


    @Transactional(noRollbackFor = DomainException.class)
    @Override
    public AuthResponse refresh(String rawRefreshToken) {
        Instant now = Instant.now();
        RefreshToken token = refreshTokens.findByTokenHash(OpaqueTokens.sha256Hex(rawRefreshToken))
                .filter(t -> t.getExpiresAt().isAfter(now))
                .orElseThrow(AuthServiceImpl::invalidRefreshToken);
        if (refreshTokens.revokeIfActive(token.getId(), now) == 0) {
            refreshTokens.revokeAllByUserId(token.getUserId(), now);
            throw invalidRefreshToken();
        }
        User user = users.findById(token.getUserId()).orElseThrow(AuthServiceImpl::invalidRefreshToken);
        return session(user);
    }

    /** Revoke refresh token; token lạ bỏ qua (đăng xuất luôn thành công). */
    @Transactional
    @Override
    public void logout(String rawRefreshToken) {
        refreshTokens.findByTokenHash(OpaqueTokens.sha256Hex(rawRefreshToken))
                .ifPresent(t -> refreshTokens.revokeIfActive(t.getId(), Instant.now()));
    }

    /** CUSTOMER → ORGANIZER + tạo hồ sơ BTC, phát phiên mới (JWT mới mang role mới). 409 ALREADY_ORGANIZER. */
    @Transactional
    @Override
    public AuthResponse becomeOrganizer(UUID userId, OrganizerProfileRequest req) {
        User user = userService.requireUser(userId);
        if (organizers.findByUserId(userId).isPresent()) {
            throw DomainException.conflict("ALREADY_ORGANIZER", "Tài khoản đã có hồ sơ ban tổ chức");
        }
        user.promoteToOrganizer();   // ADMIN giữ nguyên role, vẫn được tạo hồ sơ
        organizerService.create(userId, req);
        return session(users.saveAndFlush(user));
    }

    private User createUser(String fullName, String rawEmail, String password, UserRole role) {
        String email = AuthService.normalizeEmail(rawEmail);
        if (users.existsByEmail(email)) {
            throw DomainException.conflict("EMAIL_ALREADY_USED", "Email " + email + " đã được sử dụng");
        }
        return users.save(User.create(
                fullName.trim(),
                email,
                passwordEncoder.encode(password),
                role));
    }

    /**
     * Tài khoản Google không có mật khẩu nhưng password_hash là NOT NULL và login mật khẩu vẫn phải từ chối họ
     * → lưu hash của chuỗi ngẫu nhiên không ai biết. Muốn đăng nhập mật khẩu thì dùng "Quên mật khẩu".
     */
    private User createGoogleUser(GoogleIdTokenVerifier.GoogleAccount account, String email) {
        String fullName = account.fullName() == null || account.fullName().isBlank()
                ? email.split("@")[0]
                : account.fullName().trim();
        return users.save(User.createFromGoogle(
                fullName,
                email,
                passwordEncoder.encode(OpaqueTokens.generate()),
                account.pictureUrl()));
    }

    /** Phát access token (JWT, sống ngắn) + refresh token mới (opaque, DB chỉ lưu sha256 của nó). */
    private AuthResponse session(User user) {
        String raw = OpaqueTokens.generate();
        refreshTokens.save(RefreshToken.create(user.getId(), OpaqueTokens.sha256Hex(raw), Instant.now().plus(refreshTtl)));
        return AuthResponse.of(jwtService.issue(user), raw, refreshTtl.toSeconds(), userService.toResponse(user));
    }

    private static DomainException invalidRefreshToken() {
        return DomainException.unauthorized("REFRESH_TOKEN_INVALID", "Phiên đăng nhập đã hết hạn, vui lòng đăng nhập lại");
    }
}
