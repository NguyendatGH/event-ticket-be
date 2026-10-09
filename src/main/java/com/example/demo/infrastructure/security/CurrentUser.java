package com.example.demo.infrastructure.security;

import com.example.demo.domain.common.DomainException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.Optional;
import java.util.UUID;

public final class CurrentUser {

    private CurrentUser() {}

    public static Optional<UUID> id() {
        return SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken jwt
                ? Optional.of(UUID.fromString(jwt.getToken().getSubject()))
                : Optional.empty();
    }

    public static UUID require() {
        return id().orElseThrow(() -> DomainException.unauthorized("UNAUTHORIZED", "Bạn cần đăng nhập để tiếp tục"));
    }
}
