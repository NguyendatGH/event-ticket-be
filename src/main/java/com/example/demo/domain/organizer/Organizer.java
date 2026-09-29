package com.example.demo.domain.organizer;

import com.example.demo.domain.common.Slugs;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "organizers")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Organizer {

    @Id
    private UUID id;

    @Column(name = "user_id")
    private UUID userId;

    @Column(nullable = false, length = 200)
    private String slug;

    @Column(nullable = false, length = 200)
    private String name;

    private String description;

    @Column(name = "logo_url")
    private String logoUrl;

    @Column(name = "cover_url")
    private String coverUrl;

    private String website;

    @Column(length = 100)
    private String city;

    @Column(name = "contact_email", length = 200)
    private String contactEmail;

    @Column(name = "contact_phone", length = 30)
    private String contactPhone;

    @Column(nullable = false)
    private boolean verified;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Slug gốc từ tên BTC; trùng thì nơi gọi thêm hậu tố (Slugs.unique). */
    public static String slugify(String name) {
        return Slugs.slugify(name, "organizer");
    }

    public static Organizer create(UUID userId, String slug, String name) {
        Organizer o = new Organizer();
        o.id = UUID.randomUUID();
        o.userId = userId;
        o.slug = slug;
        o.name = name;
        return o;
    }

    public void updateProfile(String name, String description, String logoUrl, String coverUrl, String website,
                              String city, String contactEmail, String contactPhone) {
        this.name = name;
        this.description = description;
        this.logoUrl = logoUrl;
        this.coverUrl = coverUrl;
        this.website = website;
        this.city = city;
        this.contactEmail = contactEmail;
        this.contactPhone = contactPhone;
    }
}
