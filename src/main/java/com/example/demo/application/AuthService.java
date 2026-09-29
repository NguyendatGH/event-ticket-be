package com.example.demo.application;

import com.example.demo.application.dto.AuthResponse;
import com.example.demo.application.dto.LoginRequest;
import com.example.demo.application.dto.OrganizerProfileRequest;
import com.example.demo.application.dto.RegisterOrganizerRequest;
import com.example.demo.application.dto.RegisterRequest;

import java.util.Locale;
import java.util.UUID;

public interface AuthService {

    AuthResponse register(RegisterRequest req);

    AuthResponse registerOrganizer(RegisterOrganizerRequest req);

    AuthResponse login(LoginRequest req);

    /** Đăng nhập bằng Google: id_token FE gửi lên, chưa có tài khoản thì tạo mới. */
    AuthResponse loginWithGoogle(String idToken);

    AuthResponse refresh(String rawRefreshToken);

    void logout(String rawRefreshToken);

    AuthResponse becomeOrganizer(UUID userId, OrganizerProfileRequest req);

    /** Email so sánh không phân biệt hoa thường: luôn lưu và tra cứu ở dạng trim + chữ thường. */
    static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
