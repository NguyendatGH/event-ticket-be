package com.example.demo.application.impl;

import com.example.demo.application.OrganizerAccess;
import com.example.demo.domain.common.DomainException;
import com.example.demo.domain.event.Event;
import com.example.demo.domain.organizer.Organizer;
import com.example.demo.infrastructure.persistence.EventRepository;
import com.example.demo.infrastructure.persistence.OrganizerRepository;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Kiểm tra quyền sở hữu cho các API /organizer/events (dùng chung bởi OrganizerEventQueries và OrganizerEventService).
 * SecurityConfig chỉ chặn theo role; service phải tự kiểm tra "sự kiện này có phải của BTC đang đăng nhập không".
 * Sự kiện của BTC khác trả 404 (không phải 403) để không lộ là id đó có tồn tại.
 */
@Component
public class OrganizerAccessImpl implements OrganizerAccess {

    private final OrganizerRepository organizers;
    private final EventRepository events;

    public OrganizerAccessImpl(OrganizerRepository organizers, EventRepository events) {
        this.organizers = organizers;
        this.events = events;
    }

    /** Hồ sơ BTC của user. Không có hồ sơ (kể cả ADMIN) → 404 ORGANIZER_NOT_FOUND. */
    @Override
    public Organizer currentOrganizer(UUID userId) {
        return organizers.findByUserId(userId)
                .orElseThrow(() -> DomainException.notFound("ORGANIZER_NOT_FOUND", "Tài khoản chưa có hồ sơ ban tổ chức"));
    }

    /** Sự kiện của BTC đang đăng nhập, đọc bình thường (không khóa). */
    @Override
    public Event ownEvent(UUID userId, UUID eventId) {
        UUID organizerId = currentOrganizer(userId).getId();
        return events.findById(eventId)
                .filter(e -> organizerId.equals(e.getOrganizerId()))
                .orElseThrow(() -> eventNotFound(eventId));
    }

    /**
     * Như {@link #ownEvent} nhưng khóa dòng event (SELECT ... FOR UPDATE) tới hết transaction.
     * Dùng khi sửa/publish: hai request sửa cùng một sự kiện sẽ chạy lần lượt thay vì ghi đè lẫn nhau.
     */
    @Override
    public Event ownEventLocked(UUID userId, UUID eventId) {
        UUID organizerId = currentOrganizer(userId).getId();
        return events.findWithLockById(eventId)
                .filter(e -> organizerId.equals(e.getOrganizerId()))
                .orElseThrow(() -> eventNotFound(eventId));
    }

    private static DomainException eventNotFound(UUID eventId) {
        return DomainException.notFound("EVENT_NOT_FOUND", "Không tìm thấy sự kiện " + eventId);
    }
}
