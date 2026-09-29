package com.example.demo.application.dto;

import com.example.demo.domain.organizer.Organizer;

import java.time.Instant;
import java.util.UUID;

/**
 * Hồ sơ BTC, dùng chung cho trang public và trang quản lý. eventsCount = số sự kiện PUBLISHED/UPCOMING.
 * imageUrl lấy theo thứ tự logo → ảnh bìa BTC → ảnh bìa sự kiện gần nhất (nhiều BTC chưa có logo), null nếu không có.
 */
public record OrganizerResponse(
        UUID id, String slug, String name, String description, String logoUrl, String coverUrl, String imageUrl,
        String website, String city, String contactEmail, String contactPhone, boolean verified, long eventsCount,
        Instant createdAt
) {
    public static OrganizerResponse from(Organizer o, long eventsCount, String nextEventCoverUrl) {
        String image = o.getLogoUrl() != null ? o.getLogoUrl() : o.getCoverUrl() != null ? o.getCoverUrl() : nextEventCoverUrl;
        return new OrganizerResponse(o.getId(), o.getSlug(), o.getName(), o.getDescription(), o.getLogoUrl(),
                o.getCoverUrl(), image, o.getWebsite(), o.getCity(), o.getContactEmail(), o.getContactPhone(),
                o.isVerified(), eventsCount, o.getCreatedAt());
    }
}
