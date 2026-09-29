package com.example.demo.application.dto;

import com.example.demo.domain.organizer.Organizer;
import com.example.demo.domain.user.User;
import com.example.demo.domain.user.UserRole;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

@Schema(description = "Thông tin user trả về cho client")
public record UserResponse(
        @Schema(description = "Id của user") UUID id,
        @Schema(description = "Họ tên", example = "Nguyen Van A") String fullName,
        @Schema(description = "Email", example = "a@example.com") String email,
        @Schema(description = "Vai trò") UserRole role,
        @Schema(example = "0912345678", nullable = true) String phone,
        @Schema(example = "/uploads/avatars/x.jpg", nullable = true) String avatarUrl,
        @Schema(nullable = true) String bio,
        @Schema(description = "Thời điểm tạo") Instant createdAt,
        @Schema(nullable = true, description = "Hồ sơ BTC của user, null nếu không phải organizer") OrganizerSummary organizer
) {
    public record OrganizerSummary(UUID id, String slug, String name, String logoUrl) {}

    public static UserResponse from(User u) {
        return from(u, null);
    }

    public static UserResponse from(User u, Organizer o) {
        return new UserResponse(u.getId(), u.getFullName(), u.getEmail(), u.getRole(), u.getPhone(), u.getAvatarUrl(),
                u.getBio(), u.getCreatedAt(),
                o == null ? null : new OrganizerSummary(o.getId(), o.getSlug(), o.getName(), o.getLogoUrl()));
    }
}
