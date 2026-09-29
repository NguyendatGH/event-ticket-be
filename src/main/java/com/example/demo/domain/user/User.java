package com.example.demo.domain.user;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users",
        uniqueConstraints = @UniqueConstraint(name = "uk_users_email", columnNames = "email"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User {

    @Id
    private UUID id;

    @Column(name = "full_name", nullable = false, length = 200)
    private String fullName;

    @Column(nullable = false, length = 200)
    private String email;

    /** Hash BCrypt (60 ký tự). Không map ra DTO, không bao giờ trả về client. */
    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserRole role;

    @Column(length = 30)
    private String phone;

    @Column(name = "avatar_url")
    private String avatarUrl;

    private String bio;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static User create(String fullName, String email, String passwordHash, UserRole role) {
        User u = new User();
        u.id = UUID.randomUUID();
        u.fullName = fullName;
        u.email = email;
        u.passwordHash = passwordHash;
        u.role = role;
        return u;
    }

    /**
     * User tạo ra từ lần đăng nhập Google đầu tiên: lấy tên + ảnh đại diện của Google, role CUSTOMER.
     * passwordHash là chuỗi ngẫu nhiên (xem AuthServiceImpl) vì tài khoản này không có mật khẩu do user đặt.
     */
    public static User createFromGoogle(String fullName, String email, String passwordHash, String avatarUrl) {
        User u = create(fullName, email, passwordHash, UserRole.CUSTOMER);
        u.avatarUrl = avatarUrl;
        return u;
    }

    public void updateProfile(String fullName, String phone, String bio, String avatarUrl) {
        this.fullName = fullName;
        this.phone = phone;
        this.bio = bio;
        this.avatarUrl = avatarUrl;
    }

    public void changePasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public void promoteToOrganizer() {
        if (role == UserRole.CUSTOMER) role = UserRole.ORGANIZER;
    }

    /**
     * Tên hiển thị công khai đã che (lịch sử vé): giữ từ đầu + chữ cái đầu của từ cuối.
     * "Tran Thi B" → "Tran B.", "Nguyen Van An" → "Nguyen A."; một từ giữ nguyên.
     */
    public static String maskedName(String fullName) {
        if (fullName == null) return null;
        String[] words = fullName.trim().split("\\s+");
        if (words.length < 2) return words[0];
        return words[0] + " " + words[words.length - 1].substring(0, 1).toUpperCase() + ".";
    }
}
