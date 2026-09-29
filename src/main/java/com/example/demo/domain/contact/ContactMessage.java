package com.example.demo.domain.contact;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/** Tin nhắn từ trang Liên hệ. userId có khi người gửi đã đăng nhập. */
@Entity
@Table(name = "contact_messages")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ContactMessage {

    @Id
    private UUID id;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(nullable = false, length = 200)
    private String email;

    @Column(length = 200)
    private String subject;

    @Column(nullable = false)
    private String message;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public static ContactMessage create(String name, String email, String subject, String message, UUID userId) {
        ContactMessage m = new ContactMessage();
        m.id = UUID.randomUUID();
        m.name = name;
        m.email = email;
        m.subject = subject;
        m.message = message;
        m.userId = userId;
        m.createdAt = Instant.now();
        return m;
    }
}
