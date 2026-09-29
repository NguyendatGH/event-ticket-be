package com.example.demo.infrastructure.persistence;

import com.example.demo.domain.auth.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;

import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /** Revoke nguyên tử: trả 0 nếu token đã bị revoke trước đó (bị dùng lại hoặc hai request refresh đua nhau). */
    @Modifying
    @Query("update RefreshToken t set t.revokedAt = :now where t.id = :id and t.revokedAt is null")
    int revokeIfActive(UUID id, Instant now);

    /** Đặt lại mật khẩu / phát hiện token bị dùng lại: revoke mọi token còn hiệu lực của user. */
    @Modifying
    @Query("update RefreshToken t set t.revokedAt = :now where t.userId = :userId and t.revokedAt is null")
    int revokeAllByUserId(UUID userId, Instant now);
}
