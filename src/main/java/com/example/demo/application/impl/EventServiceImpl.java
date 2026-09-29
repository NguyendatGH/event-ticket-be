package com.example.demo.application.impl;

import com.example.demo.application.EventDateRange;
import com.example.demo.application.EventService;
import com.example.demo.application.dto.EventFacets;
import com.example.demo.application.dto.EventOrganizer;
import com.example.demo.application.dto.EventResponse;
import com.example.demo.application.dto.PageResponse;
import com.example.demo.application.support.Pages;
import com.example.demo.application.support.SqlWhere;
import com.example.demo.domain.common.DomainException;
import com.example.demo.domain.common.VietnamTime;
import com.example.demo.domain.event.Event;
import com.example.demo.domain.event.EventStatus;
import com.example.demo.domain.event.TicketTier;
import com.example.demo.domain.inventory.Inventory;
import com.example.demo.domain.organizer.Organizer;
import com.example.demo.infrastructure.persistence.EventRepository;
import com.example.demo.infrastructure.persistence.InventoryRepository;
import com.example.demo.infrastructure.persistence.OrganizerRepository;
import com.example.demo.infrastructure.persistence.TicketTierRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.example.demo.application.support.Texts.blankToNull;
import static com.example.demo.application.support.Texts.likePattern;
import static com.example.demo.application.support.Texts.parseUuid;

/**
 * API đọc sự kiện công khai (ui-api-contract §4.3), controller: EventController.
 * "Liệt kê" = status PUBLISHED/UPCOMING và (trừ khi includePast) chưa qua {@code coalesce(ends_at, starts_at)}.
 * DRAFT không xem được (404).
 * <p>
 * Cách đọc danh sách, 2 bước:
 * <ol>
 *   <li>Lọc + sắp xếp + phân trang bằng SQL (JdbcClient), chỉ lấy ra danh sách id. Lọc theo jsonb venue, giá thấp nhất,
 *       số vé đã bán... viết bằng SQL dễ hơn nhiều so với JPA.</li>
 *   <li>{@link #summaries} nạp entity theo lô cho các id đó: 4 query cho cả trang, không bị N+1.</li>
 * </ol>
 */
@Service
@Transactional(readOnly = true)
public class EventServiceImpl implements EventService {

    private static final int FEATURED_LIMIT = 50;
    private static final int MAX_UPCOMING = 24;
    private static final int MAX_RELATED = 20;
    private static final String BY_DATE = "e.starts_at, e.id";

    private final EventRepository events;
    private final TicketTierRepository tiers;
    private final InventoryRepository inventory;
    private final OrganizerRepository organizers;
    private final JdbcClient jdbc;

    public EventServiceImpl(EventRepository events, TicketTierRepository tiers, InventoryRepository inventory,
                            OrganizerRepository organizers, JdbcClient jdbc) {
        this.events = events;
        this.tiers = tiers;
        this.inventory = inventory;
        this.organizers = organizers;
        this.jdbc = jdbc;
    }

    /** GET /events */
    @Override
    public PageResponse<EventResponse> list(Filter f, String sort, int page, int size) {
        SqlWhere w = listed(f.includePast());
        String q = blankToNull(f.q());
        if (q != null) {
            w.add("(e.name ilike :q or e.tagline ilike :q or o.name ilike :q or e.venue ->> 'name' ilike :q)", "q", likePattern(q));
        }
        String category = blankToNull(f.category());
        if (category != null && !"all".equalsIgnoreCase(category)) {
            w.add("e.category = :category", "category", category);
        }
        String city = blankToNull(f.city());
        if (city != null) w.add("e.venue ->> 'city' = :city", "city", city);
        addDateFilter(w, f.from(), f.to(), blankToNull(f.when()));
        if (f.priceMin() != null) w.add("p.min_price >= :priceMin", "priceMin", f.priceMin());
        if (f.priceMax() != null) w.add("p.min_price <= :priceMax", "priceMax", f.priceMax());
        String organizer = blankToNull(f.organizer());
        if (organizer != null) {
            Optional<UUID> organizerId = parseUuid(organizer);
            if (organizerId.isPresent()) w.add("e.organizer_id = :organizerId", "organizerId", organizerId.get());
            else w.add("o.slug = :organizerSlug", "organizerSlug", organizer);
        }
        return page(w, orderBy(blankToNull(sort)), page, size);
    }

    /** GET /events/featured */
    @Override
    public List<EventResponse> featured() {
        return summaries(ids(listed(false).add("e.featured"), BY_DATE, FEATURED_LIMIT, 0));
    }

    /** GET /events/upcoming */
    @Override
    public List<EventResponse> upcoming(int limit) {
        SqlWhere w = listed(false).add("e.starts_at >= now()");
        return summaries(ids(w, BY_DATE, Math.clamp(limit, 1, MAX_UPCOMING), 0));
    }

    /** GET /events/facets: đếm theo danh mục, theo thành phố và khoảng giá của mọi sự kiện đang liệt kê. */
    @Override
    public EventFacets facets() {
        SqlWhere w = listed(false);
        List<EventFacets.CategoryCount> categories = jdbc.sql("select e.category, count(*)" + FROM + w.sql()
                        + " group by e.category order by count(*) desc, e.category")
                .params(w.params())
                .query((rs, i) -> new EventFacets.CategoryCount(rs.getString(1), rs.getLong(2)))
                .list();
        List<EventFacets.CityCount> cities = jdbc.sql("select e.venue ->> 'city', count(*)" + FROM + w.sql()
                        + " and e.venue ->> 'city' is not null group by 1 order by 2 desc, 1")
                .params(w.params())
                .query((rs, i) -> new EventFacets.CityCount(rs.getString(1), rs.getLong(2)))
                .list();
        EventFacets.PriceRange price = jdbc.sql("select coalesce(min(p.min_price), 0), coalesce(max(p.min_price), 0)" + FROM + w.sql())
                .params(w.params())
                .query((rs, i) -> new EventFacets.PriceRange(rs.getLong(1), rs.getLong(2)))
                .single();
        return new EventFacets(categories, cities, price);
    }

    /** GET /events/{idOrSlug}: chi tiết kèm tiers (số vé còn lại) và BTC (kèm số sự kiện đang liệt kê). */
    @Override
    public EventResponse get(String idOrSlug) {
        Event e = find(idOrSlug);
        List<TicketTier> eventTiers = tiers.findAllByEventIdOrderByPriceAsc(e.getId());
        EventOrganizer organizer = e.getOrganizerId() == null ? null : organizers.findById(e.getOrganizerId())
                .map(o -> EventOrganizer.detail(o, events.countByOrganizerIdAndStatusIn(o.getId(), EventStatus.LISTED)))
                .orElse(null);
        return EventResponse.of(e, eventTiers, availableByTier(eventTiers), organizer, true);
    }

    /** GET /events/{idOrSlug}/related: cùng danh mục, trừ chính nó. */
    @Override
    public List<EventResponse> related(String idOrSlug, int limit) {
        Event e = find(idOrSlug);
        if (e.getCategory() == null) return List.of();
        SqlWhere w = listed(false)
                .add("e.id <> :self", "self", e.getId())
                .add("e.category = :category", "category", e.getCategory());
        return summaries(ids(w, BY_DATE, Math.clamp(limit, 1, MAX_RELATED), 0));
    }

    /** GET /events/{idOrSlug}/more-from-organizer: sự kiện khác của cùng BTC. */
    @Override
    public List<EventResponse> moreFromOrganizer(String idOrSlug, int limit) {
        Event e = find(idOrSlug);
        if (e.getOrganizerId() == null) return List.of();
        SqlWhere w = listed(false)
                .add("e.id <> :self", "self", e.getId())
                .add("e.organizer_id = :organizerId", "organizerId", e.getOrganizerId());
        return summaries(ids(w, BY_DATE, Math.clamp(limit, 1, MAX_RELATED), 0));
    }

    /** GET /organizers/{idOrSlug}/events: upcoming (mặc định, gần nhất trước) hoặc past (mới diễn ra trước). */
    @Override
    public PageResponse<EventResponse> byOrganizer(String idOrSlug, String scope, int page, int size) {
        Organizer o = parseUuid(idOrSlug).flatMap(organizers::findById).or(() -> organizers.findBySlug(idOrSlug))
                .orElseThrow(() -> DomainException.notFound("ORGANIZER_NOT_FOUND", "Không tìm thấy ban tổ chức " + idOrSlug));
        boolean past = "past".equalsIgnoreCase(scope);
        if (!past && scope != null && !scope.isBlank() && !"upcoming".equalsIgnoreCase(scope)) {
            throw DomainException.invalid("scope", "scope phải là upcoming hoặc past");
        }
        SqlWhere w = listed(true).add("e.organizer_id = :organizerId", "organizerId", o.getId());
        if (past) {
            w.add("coalesce(e.ends_at, e.starts_at) < now()");
            return page(w, "e.starts_at desc, e.id", page, size);
        }
        w.add("coalesce(e.ends_at, e.starts_at) >= now()");
        return page(w, BY_DATE, page, size);
    }

    /** Nhận UUID hoặc slug (FE dùng slug trên URL). DRAFT coi như không tồn tại với public. */
    @Override
    public Event find(String idOrSlug) {
        return parseUuid(idOrSlug).flatMap(events::findById).or(() -> events.findBySlug(idOrSlug))
                .filter(e -> e.getStatus() != EventStatus.DRAFT)
                .orElseThrow(() -> DomainException.notFound("EVENT_NOT_FOUND", "Không tìm thấy sự kiện " + idOrSlug));
    }

    /* ---------- bước 1: truy vấn id bằng SQL ---------- */

    /** Bảng chung cho mọi query: p = giá thấp nhất mỗi sự kiện (lọc/sắp theo giá), o = BTC (tìm theo tên, lọc theo slug). */
    private static final String FROM = """
             from events e
             left join organizers o on o.id = e.organizer_id
             left join (select event_id, min(price) as min_price from ticket_tiers group by event_id) p on p.event_id = e.id
            """;

    /** Chỉ join khi sort=popular: s.sold = số vé đã phát của sự kiện. */
    private static final String POPULAR_JOIN = """
             left join (select tt.event_id, count(*) as sold from tickets t
                        join ticket_tiers tt on tt.id = t.ticket_tier_id group by tt.event_id) s on s.event_id = e.id
            """;

    /** Điều kiện "đang liệt kê" (cùng nghĩa EventStatus.isListed), thêm "chưa kết thúc" nếu không lấy sự kiện đã qua. */
    private static SqlWhere listed(boolean includePast) {
        SqlWhere w = new SqlWhere().add("e.status in ('PUBLISHED', 'UPCOMING')");
        if (!includePast) w.add("coalesce(e.ends_at, e.starts_at) >= now()");
        return w;
    }

    private List<UUID> ids(SqlWhere w, String orderBy, int limit, long offset) {
        String join = orderBy.contains("s.sold") ? POPULAR_JOIN : "";
        return jdbc.sql("select e.id" + FROM + join + w.sql() + " order by " + orderBy + " limit :limit offset :offset")
                .params(w.params())
                .param("limit", limit)
                .param("offset", offset)
                .query(UUID.class)
                .list();
    }

    private PageResponse<EventResponse> page(SqlWhere w, String orderBy, int page, int size) {
        int p = Pages.page(page);
        int s = Pages.clampSize(size);
        long total = jdbc.sql("select count(*)" + FROM + w.sql()).params(w.params()).query(Long.class).single();
        List<EventResponse> content = total == 0 ? List.of() : summaries(ids(w, orderBy, s, Pages.offset(p, s)));
        return Pages.response(content, p, s, total);
    }

    /** Tham số sort → ORDER BY. Chỉ trả chuỗi hằng trong code, không nối input của người dùng vào SQL. */
    private static String orderBy(String sort) {
        return switch (sort == null ? "date" : sort) {
            case "date" -> BY_DATE;
            case "-date" -> "e.starts_at desc, e.id";
            case "price" -> "p.min_price asc nulls last, e.starts_at, e.id";
            case "-price" -> "p.min_price desc nulls last, e.starts_at, e.id";
            case "newest" -> "coalesce(e.published_at, e.created_at) desc, e.id";
            case "popular" -> "coalesce(s.sold, 0) desc, e.starts_at, e.id";
            default -> throw DomainException.invalid("sort", "sort phải là date, -date, price, -price, newest hoặc popular");
        };
    }

    /**
     * from/to (ngày VN, gồm cả hai đầu) và when giao nhau thành một khoảng. Sự kiện được lấy khi thời gian diễn ra
     * [starts_at, coalesce(ends_at, starts_at)] chạm khoảng đó, nên lễ hội nhiều ngày đang diễn ra vẫn tính là "hôm nay".
     */
    private static void addDateFilter(SqlWhere w, LocalDate from, LocalDate to, String when) {
        LocalDate first = from;
        LocalDate last = to;
        if (when != null) {
            EventDateRange range = EventDateRange.ofWhen(when, LocalDate.now(VietnamTime.ZONE));
            if (range == null) throw DomainException.invalid("when", "when phải là today, weekend, week hoặc month");
            // Giao hai khoảng: đầu muộn hơn, cuối sớm hơn
            if (first == null || range.from().isAfter(first)) first = range.from();
            if (last == null || range.to().isBefore(last)) last = range.to();
        }
        if (first != null) w.add("coalesce(e.ends_at, e.starts_at) >= :dateFrom", "dateFrom", startOfDay(first));
        if (last != null) {
            w.add("e.starts_at < :dateTo", "dateTo", startOfDay(last.plusDays(1)));   // < 00:00 ngày hôm sau
        }
    }

    private static Timestamp startOfDay(LocalDate day) {
        return Timestamp.from(day.atStartOfDay(VietnamTime.ZONE).toInstant());
    }

    /* ---------- bước 2: nạp theo lô ---------- */

    /** Bốn query cho cả danh sách thay vì N+1: events theo id, tiers theo event, inventory theo tier, organizers theo id. */
    private List<EventResponse> summaries(List<UUID> ids) {
        if (ids.isEmpty()) return List.of();
        Map<UUID, Event> byId = events.findAllById(ids).stream()
                .collect(Collectors.toMap(Event::getId, Function.identity()));
        List<Event> list = ids.stream().map(byId::get).filter(Objects::nonNull).toList();   // giữ đúng thứ tự của SQL
        Map<UUID, List<TicketTier>> tiersByEvent = tiers.findAllByEventIdIn(ids).stream()
                .collect(Collectors.groupingBy(TicketTier::getEventId));
        Map<UUID, Integer> available = availableByTier(tiersByEvent.values().stream().flatMap(List::stream).toList());
        List<UUID> organizerIds = list.stream().map(Event::getOrganizerId).filter(Objects::nonNull).distinct().toList();
        Map<UUID, Organizer> organizerById = organizers.findAllById(organizerIds).stream()
                .collect(Collectors.toMap(Organizer::getId, Function.identity()));
        return list.stream()
                .map(e -> {
                    Organizer o = e.getOrganizerId() == null ? null : organizerById.get(e.getOrganizerId());
                    return EventResponse.of(e, tiersByEvent.getOrDefault(e.getId(), List.of()), available,
                            o == null ? null : EventOrganizer.summary(o), false);
                })
                .toList();
    }

    private Map<UUID, Integer> availableByTier(List<TicketTier> eventTiers) {
        return inventory.findAllById(eventTiers.stream().map(TicketTier::getId).toList()).stream()
                .collect(Collectors.toMap(Inventory::getTicketTierId, Inventory::getAvailable, (a, b) -> a));
    }
}
