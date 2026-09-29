package com.example.demo.application.impl;

import com.example.demo.application.OrganizerService;
import com.example.demo.application.dto.OrganizerProfileRequest;
import com.example.demo.application.dto.OrganizerResponse;
import com.example.demo.domain.common.DomainException;
import com.example.demo.domain.common.Slugs;
import com.example.demo.domain.event.EventStatus;
import com.example.demo.domain.organizer.Organizer;
import com.example.demo.infrastructure.persistence.EventRepository;
import com.example.demo.infrastructure.persistence.OrganizerRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.example.demo.application.support.Texts.blankToNull;
import static com.example.demo.application.support.Texts.parseUuid;

/**
 * Hồ sơ ban tổ chức: tạo (đăng ký organizer / nâng cấp tài khoản), xem, sửa, trang public, danh sách nổi bật.
 * Controller: OrganizerController (/organizer/profile, /organizers, /organizers/{idOrSlug}); AuthService gọi {@link #create}.
 */
@Service
public class OrganizerServiceImpl implements OrganizerService {

    private static final int MAX_FEATURED = 24;

    /**
     * Thứ tự hàng "Ban tổ chức nổi bật": đã xác minh trước, rồi nhiều sự kiện SẮP/ĐANG diễn ra hơn, rồi theo tên.
     * Bỏ BTC không còn sự kiện nào chưa kết thúc (trang chủ chỉ giới thiệu BTC đang có cái để mua).
     * Cột "listed" = mọi sự kiện PUBLISHED/UPCOMING (kể cả đã qua), cùng nghĩa eventsCount ở trang chi tiết BTC.
     */
    private static final String FEATURED_SQL = """
            select o.id, count(*) as listed,
                   (select c.cover_image_url from events c
                     where c.organizer_id = o.id and c.status in ('PUBLISHED', 'UPCOMING') and c.cover_image_url is not null
                       and coalesce(c.ends_at, c.starts_at) >= now()
                     order by c.starts_at limit 1) as next_cover
              from organizers o
              join events e on e.organizer_id = o.id and e.status in ('PUBLISHED', 'UPCOMING')
             group by o.id, o.verified, o.name
            having count(*) filter (where coalesce(e.ends_at, e.starts_at) >= now()) > 0
             order by o.verified desc, count(*) filter (where coalesce(e.ends_at, e.starts_at) >= now()) desc, o.name, o.id
             limit :limit
            """;

    private final OrganizerRepository organizers;
    private final EventRepository events;
    private final JdbcClient jdbc;

    public OrganizerServiceImpl(OrganizerRepository organizers, EventRepository events, JdbcClient jdbc) {
        this.organizers = organizers;
        this.events = events;
        this.jdbc = jdbc;
    }

    /** Tạo hồ sơ cho user, slug duy nhất sinh từ tên. Nơi gọi đã kiểm tra user chưa có hồ sơ. */
    @Override
    @Transactional
    public Organizer create(UUID userId, OrganizerProfileRequest req) {
        String name = req.name().trim();
        String slug = Slugs.unique(Organizer.slugify(name), organizers::existsBySlug);
        Organizer o = Organizer.create(userId, slug, name);
        apply(o, req);
        return organizers.save(o);
    }

    @Override
    @Transactional(readOnly = true)
    public OrganizerResponse mine(UUID userId) {
        return toResponse(requireMine(userId));
    }

    @Override
    @Transactional
    public OrganizerResponse updateMine(UUID userId, OrganizerProfileRequest req) {
        Organizer o = requireMine(userId);
        apply(o, req);
        return toResponse(organizers.saveAndFlush(o));
    }

    /** Trang public: tìm theo slug trước, không thấy thì thử coi là UUID. */
    @Override
    @Transactional(readOnly = true)
    public OrganizerResponse publicProfile(String idOrSlug) {
        return organizers.findBySlug(idOrSlug).or(() -> parseUuid(idOrSlug).flatMap(organizers::findById))
                .map(this::toResponse)
                .orElseThrow(OrganizerServiceImpl::notFound);
    }

    /**
     * GET /organizers: BTC nổi bật cho trang chủ. size mặc định 12, kẹp vào [1, 24].
     * Cố định 2 query (id + số sự kiện, rồi nạp entity theo lô), không N+1.
     */
    @Override
    @Transactional(readOnly = true)
    public List<OrganizerResponse> featured(int size) {
        Map<UUID, Long> countById = new LinkedHashMap<>();
        Map<UUID, String> coverById = new LinkedHashMap<>();   // ảnh sự kiện gần nhất, dự phòng khi BTC chưa có logo
        jdbc.sql(FEATURED_SQL).param("limit", Math.clamp(size, 1, MAX_FEATURED))
                .query((rs, i) -> {
                    UUID id = rs.getObject("id", UUID.class);
                    countById.put(id, rs.getLong("listed"));
                    coverById.put(id, rs.getString("next_cover"));
                    return id;
                })
                .list();
        if (countById.isEmpty()) return List.of();
        Map<UUID, Organizer> byId = organizers.findAllById(countById.keySet()).stream()
                .collect(Collectors.toMap(Organizer::getId, Function.identity()));
        // Giữ đúng thứ tự SQL (findAllById không đảm bảo thứ tự)
        return countById.entrySet().stream()
                .map(en -> OrganizerResponse.from(byId.get(en.getKey()), en.getValue(), coverById.get(en.getKey())))
                .toList();
    }

    private Organizer requireMine(UUID userId) {
        return organizers.findByUserId(userId).orElseThrow(OrganizerServiceImpl::notFound);
    }

    private static void apply(Organizer o, OrganizerProfileRequest req) {
        o.updateProfile(req.name().trim(), blankToNull(req.description()), blankToNull(req.logoUrl()),
                blankToNull(req.coverUrl()), blankToNull(req.website()), blankToNull(req.city()),
                blankToNull(req.contactEmail()), blankToNull(req.contactPhone()));
    }

    /** eventsCount = số sự kiện đang liệt kê, cùng con số với EventOrganizer.eventsCount ở trang chi tiết sự kiện. */
    private OrganizerResponse toResponse(Organizer o) {
        return OrganizerResponse.from(o, events.countByOrganizerIdAndStatusIn(o.getId(), EventStatus.LISTED), nextEventCover(o.getId()));
    }

    /** Ảnh bìa sự kiện sắp diễn ra gần nhất của BTC (dự phòng cho avatar khi chưa có logo/ảnh bìa). */
    private String nextEventCover(UUID organizerId) {
        return jdbc.sql("""
                        select cover_image_url from events
                         where organizer_id = :id and status in ('PUBLISHED', 'UPCOMING') and cover_image_url is not null
                           and coalesce(ends_at, starts_at) >= now()
                         order by starts_at limit 1""")
                .param("id", organizerId).query(String.class).optional().orElse(null);
    }

    private static DomainException notFound() {
        return DomainException.notFound("ORGANIZER_NOT_FOUND", "Không tìm thấy ban tổ chức");
    }
}
