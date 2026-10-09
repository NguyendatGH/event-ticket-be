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

    AuthResponse loginWithGoogle(String idToken);

    AuthResponse refresh(String rawRefreshToken);

    void logout(String rawRefreshToken);

    AuthResponse becomeOrganizer(UUID userId, OrganizerProfileRequest req);

    static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
