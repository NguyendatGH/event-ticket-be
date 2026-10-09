package com.example.demo.infrastructure.web;

import com.example.demo.application.AuthService;
import com.example.demo.application.PasswordResetService;
import com.example.demo.application.UserService;
import com.example.demo.application.dto.AuthResponse;
import com.example.demo.application.dto.ForgotPasswordRequest;
import com.example.demo.application.dto.ForgotPasswordResponse;
import com.example.demo.application.dto.GoogleLoginRequest;
import com.example.demo.application.dto.LoginRequest;
import com.example.demo.application.dto.RefreshTokenRequest;
import com.example.demo.application.dto.RegisterOrganizerRequest;
import com.example.demo.application.dto.RegisterRequest;
import com.example.demo.application.dto.ResetPasswordRequest;
import com.example.demo.application.dto.UserResponse;
import com.example.demo.infrastructure.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Auth", description = "Đăng ký, đăng nhập, refresh token, quên mật khẩu")
public class AuthController {

    private final AuthService authService;
    private final PasswordResetService passwordResetService;
    private final UserService userService;

    public AuthController(AuthService authService, PasswordResetService passwordResetService, UserService userService) {
        this.authService = authService;
        this.passwordResetService = passwordResetService;
        this.userService = userService;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    @SecurityRequirements
    @Operation(summary = "Đăng ký", description = "Tạo user role CUSTOMER và trả về phiên đăng nhập (access + refresh token)")
    public AuthResponse register(@Valid @RequestBody RegisterRequest req) {
        return authService.register(req);
    }

    @PostMapping("/register-organizer")
    @ResponseStatus(HttpStatus.CREATED)
    @SecurityRequirements
    @Operation(summary = "Đăng ký ban tổ chức", description = "Tạo user role ORGANIZER kèm hồ sơ BTC trong một bước")
    public AuthResponse registerOrganizer(@Valid @RequestBody RegisterOrganizerRequest req) {
        return authService.registerOrganizer(req);
    }

    @PostMapping("/login")
    @SecurityRequirements
    @Operation(summary = "Đăng nhập", description = "Kiểm tra email/password, trả về access token + refresh token")
    public AuthResponse login(@Valid @RequestBody LoginRequest req) {
        return authService.login(req);
    }

    @PostMapping("/google")
    @SecurityRequirements
    @Operation(summary = "Đăng nhập với Google",
            description = "Nhận id_token từ Google Identity Services (FE), verify chữ ký rồi trả về phiên đăng nhập. "
                    + "Email chưa có tài khoản thì tạo mới (role CUSTOMER)")
    public AuthResponse loginWithGoogle(@Valid @RequestBody GoogleLoginRequest req) {
        return authService.loginWithGoogle(req.idToken());
    }

    @PostMapping("/refresh")
    @SecurityRequirements
    @Operation(summary = "Làm mới phiên", description = "Đổi refresh token lấy cặp token mới; token cũ bị revoke (rotation)")
    public AuthResponse refresh(@Valid @RequestBody RefreshTokenRequest req) {
        return authService.refresh(req.refreshToken());
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @SecurityRequirements
    @Operation(summary = "Đăng xuất", description = "Revoke refresh token. Token lạ vẫn trả 204")
    public void logout(@RequestBody(required = false) RefreshTokenRequest req) {
        if (req != null && req.refreshToken() != null && !req.refreshToken().isBlank()) {
            authService.logout(req.refreshToken());
        }
    }

    @GetMapping("/me")
    @Operation(summary = "User hiện tại", description = "Đọc id từ claim sub của JWT trong header Authorization")
    public UserResponse me() {
        return userService.me(CurrentUser.require());
    }

    @PostMapping("/forgot-password")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @SecurityRequirements
    @Operation(summary = "Quên mật khẩu", description = "Luôn 202 kể cả email lạ. Link đặt lại gửi qua email (bản hiện tại in ra log); "
            + "profile dev trả thêm devResetUrl")
    public ForgotPasswordResponse forgotPassword(@Valid @RequestBody ForgotPasswordRequest req) {
        return passwordResetService.forgotPassword(req.email());
    }

    @PostMapping("/reset-password")
    @SecurityRequirements
    @Operation(summary = "Đặt lại mật khẩu", description = "Dùng token trong link; thành công thì đăng xuất mọi thiết bị")
    public Map<String, Boolean> resetPassword(@Valid @RequestBody ResetPasswordRequest req) {
        passwordResetService.resetPassword(req.token(), req.password());
        return Map.of("updated", true);
    }
}
