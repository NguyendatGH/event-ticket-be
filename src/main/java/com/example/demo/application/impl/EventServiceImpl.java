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

    @Override
    public List<EventResponse> featured() {
        return summaries(ids(listed(false).add("e.featured"), BY_DATE, FEATURED_LIMIT, 0));
    }

    @Override
    public List<EventResponse> upcoming(int limit) {
        SqlWhere w = listed(false).add("e.starts_at >= now()");
        return summaries(ids(w, BY_DATE, Math.clamp(limit, 1, MAX_UPCOMING), 0));
    }

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

    @Override
    public EventResponse get(String idOrSlug) {
        Event e = find(idOrSlug);
        List<TicketTier> eventTiers = tiers.findAllByEventIdOrderByPriceAsc(e.getId());
        EventOrganizer organizer = e.getOrganizerId() == null ? null : organizers.findById(e.getOrganizerId())
                .map(o -> EventOrganizer.detail(o, events.countByOrganizerIdAndStatusIn(o.getId(), EventStatus.LISTED)))
                .orElse(null);
        return EventResponse.of(e, eventTiers, availableByTier(eventTiers), organizer, true);
    }

    @Override
    public List<EventResponse> related(String idOrSlug, int limit) {
        Event e = find(idOrSlug);
        if (e.getCategory() == null) return List.of();
        SqlWhere w = listed(false)
                .add("e.id <> :self", "self", e.getId())
                .add("e.category = :category", "category", e.getCategory());
        return summaries(ids(w, BY_DATE, Math.clamp(limit, 1, MAX_RELATED), 0));
    }

    @Override
    public List<EventResponse> moreFromOrganizer(String idOrSlug, int limit) {
        Event e = find(idOrSlug);
        if (e.getOrganizerId() == null) return List.of();
        SqlWhere w = listed(false)
                .add("e.id <> :self", "self", e.getId())
                .add("e.organizer_id = :organizerId", "organizerId", e.getOrganizerId());
        return summaries(ids(w, BY_DATE, Math.clamp(limit, 1, MAX_RELATED), 0));
    }

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

    @Override
    public Event find(String idOrSlug) {
        return parseUuid(idOrSlug).flatMap(events::findById).or(() -> events.findBySlug(idOrSlug))
                .filter(e -> e.getStatus() != EventStatus.DRAFT)
                .orElseThrow(() -> DomainException.notFound("EVENT_NOT_FOUND", "Không tìm thấy sự kiện " + idOrSlug));
    }

    private static final String FROM = """
             from events e
             left join organizers o on o.id = e.organizer_id
             left join (select event_id, min(price) as min_price from ticket_tiers group by event_id) p on p.event_id = e.id
            """;

    private static final String POPULAR_JOIN = """
             left join (select tt.event_id, count(*) as sold from tickets t
                        join ticket_tiers tt on tt.id = t.ticket_tier_id group by tt.event_id) s on s.event_id = e.id
            """;

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

    private static void addDateFilter(SqlWhere w, LocalDate from, LocalDate to, String when) {
        LocalDate first = from;
        LocalDate last = to;
        if (when != null) {
            EventDateRange range = EventDateRange.ofWhen(when, LocalDate.now(VietnamTime.ZONE));
            if (range == null) throw DomainException.invalid("when", "when phải là today, weekend, week hoặc month");
            if (first == null || range.from().isAfter(first)) first = range.from();
            if (last == null || range.to().isBefore(last)) last = range.to();
        }
        if (first != null) w.add("coalesce(e.ends_at, e.starts_at) >= :dateFrom", "dateFrom", startOfDay(first));
        if (last != null) {
            w.add("e.starts_at < :dateTo", "dateTo", startOfDay(last.plusDays(1)));
        }
    }

    private static Timestamp startOfDay(LocalDate day) {
        return Timestamp.from(day.atStartOfDay(VietnamTime.ZONE).toInstant());
    }

    private List<EventResponse> summaries(List<UUID> ids) {
        if (ids.isEmpty()) return List.of();
        Map<UUID, Event> byId = events.findAllById(ids).stream()
                .collect(Collectors.toMap(Event::getId, Function.identity()));
        List<Event> list = ids.stream().map(byId::get).filter(Objects::nonNull).toList();
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
