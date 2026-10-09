package com.example.demo.domain.event;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import com.example.demo.domain.common.DomainException;
import com.example.demo.domain.common.DomainException.FieldError;
import com.example.demo.domain.common.Slugs;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "events")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class Event {

    @Id
    @Builder.Default
    private UUID id = UUID.randomUUID();

    @Column(nullable = false, length = 200)
    private String slug;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(length = 50)
    private String category;

    @Builder.Default
    @Column(name = "refund_deadline_hours", nullable = false)
    private int refundDeadlineHours = 48;

    @Column(name = "starts_at")
    private Instant startsAt;

    @Column(name = "ends_at")
    private Instant endsAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EventStatus status;

    @JdbcTypeCode(SqlTypes.JSON)
    private Venue venue;

    @Column(name = "cover_image_url")
    private String coverImageUrl;

    @Column(name = "cover_image_alt")
    private String coverImageAlt;

    private String tagline;

    @JdbcTypeCode(SqlTypes.JSON)
    private List<String> description;

    @JdbcTypeCode(SqlTypes.JSON)
    private List<ScheduleItem> schedule;

    @Column(name = "organizer_id")
    private UUID organizerId;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(nullable = false)
    private boolean featured;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static final Set<String> CATEGORIES = Set.of("music", "theatre", "sport", "conference", "exhibition", "workshop");

    public boolean isOnSale(Instant now) {
        return status == EventStatus.PUBLISHED && !isEnded(now);
    }

    public static Event draft(UUID organizerId, String slug) {
        return Event.builder().organizerId(organizerId).slug(slug).status(EventStatus.DRAFT).featured(false).build();
    }

    public void updateDetails(String name, String category, String tagline, List<String> description, String coverImageUrl,
                              String coverImageAlt, Instant startsAt, Instant endsAt, Venue venue, List<ScheduleItem> schedule) {
        this.name = name;
        this.category = category;
        this.tagline = tagline;
        this.description = description == null ? List.of() : description;
        this.coverImageUrl = coverImageUrl;
        this.coverImageAlt = coverImageAlt;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.venue = venue;
        this.schedule = schedule == null ? List.of() : schedule;
    }

    public boolean isEnded(Instant now) {
        return isEnded(status, startsAt, endsAt, now);
    }

    public boolean isRefundOpen(Instant now) {
        return startsAt != null && now.isBefore(startsAt.minus(Duration.ofHours(refundDeadlineHours)));
    }

    private static boolean isEnded(EventStatus status, Instant startsAt, Instant endsAt, Instant now) {
        Instant end = endsAt != null ? endsAt : startsAt;
        return status.isListed() && end != null && end.isBefore(now);
    }

    public static String displayStatus(EventStatus status, Instant startsAt, Instant endsAt, boolean soldOut, Instant now) {
        if (isEnded(status, startsAt, endsAt, now)) return "ENDED";
        return soldOut ? "SOLD_OUT" : status.name();
    }

    public String displayStatus(boolean soldOut, Instant now) {
        return displayStatus(status, startsAt, endsAt, soldOut, now);
    }

    public void ensureEditable(Instant now) {
        if (status == EventStatus.CANCELLED || isEnded(now)) {
            throw DomainException.conflict("EVENT_NOT_EDITABLE", "Sự kiện đã kết thúc hoặc đã hủy, không sửa được");
        }
    }

    public void publish(List<TicketTier> tiers, Instant now) {
        if (status != EventStatus.DRAFT) throw DomainException.conflict("EVENT_NOT_DRAFT", "Chỉ publish được sự kiện nháp");
        List<FieldError> problems = publishProblems(tiers, now, true);
        if (!problems.isEmpty()) {
            throw DomainException.badRequest("EVENT_INCOMPLETE", "Sự kiện chưa đủ thông tin để publish").withErrors(problems);
        }
        status = EventStatus.PUBLISHED;
        publishedAt = now;
    }

    public List<FieldError> publishProblems(List<TicketTier> tiers, Instant now, boolean publishing) {
        List<FieldError> errors = new ArrayList<>();
        if (category == null || !CATEGORIES.contains(category)) errors.add(new FieldError("category", "Chọn danh mục"));
        if (description == null || description.stream().allMatch(p -> p == null || p.isBlank())) {
            errors.add(new FieldError("description", "Cần mô tả sự kiện"));
        }
        if (coverImageUrl == null || coverImageUrl.isBlank()) errors.add(new FieldError("coverImageUrl", "Cần ảnh bìa"));
        if (startsAt == null) errors.add(new FieldError("startsAt", "Cần thời gian bắt đầu"));
        else if (publishing && !startsAt.isAfter(now)) errors.add(new FieldError("startsAt", "Thời gian bắt đầu phải ở tương lai"));
        if (endsAt != null && startsAt != null && !endsAt.isAfter(startsAt)) {
            errors.add(new FieldError("endsAt", "Thời gian kết thúc phải sau thời gian bắt đầu"));
        }
        if (venue == null || venue.name() == null || venue.name().isBlank()) errors.add(new FieldError("venue.name", "Cần tên địa điểm"));
        if (venue == null || venue.city() == null || venue.city().isBlank()) errors.add(new FieldError("venue.city", "Cần thành phố"));
        if (tiers.isEmpty()) errors.add(new FieldError("tiers", "Cần ít nhất một hạng vé"));
        for (int i = 0; publishing && i < tiers.size(); i++) {
            if (tiers.get(i).getTotalQuantity() < 1) errors.add(new FieldError("tiers[" + i + "].totalQuantity", "Số lượng vé phải ≥ 1"));
        }
        return errors;
    }

    public static String slugify(String name) {
        return Slugs.slugify(name, "su-kien");
    }
}
