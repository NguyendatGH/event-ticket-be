package com.example.demo.application.impl;

import com.example.demo.application.OrganizerAccess;
import com.example.demo.application.OrganizerEventQueries;
import com.example.demo.application.dto.OrganizerEventDetail;
import com.example.demo.application.dto.OrganizerEventSummary;
import com.example.demo.application.dto.OrganizerOrderRow;
import com.example.demo.application.dto.PageResponse;
import com.example.demo.application.support.Pages;
import com.example.demo.application.support.SqlWhere;
import com.example.demo.domain.common.DomainException;
import com.example.demo.domain.event.Event;
import com.example.demo.domain.event.EventStatus;
import com.example.demo.domain.event.TicketTier;
import com.example.demo.infrastructure.persistence.EventRepository;
import com.example.demo.infrastructure.persistence.TicketTierRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.example.demo.application.support.SqlRows.instant;
import static com.example.demo.application.support.Texts.blankToNull;
import static com.example.demo.application.support.Texts.likePattern;


@Service
public class OrganizerEventQueriesImpl implements OrganizerEventQueries {

    private final EventRepository events;
    private final TicketTierRepository tiers;
    private final OrganizerAccess access;
    private final JdbcClient jdbc;

    public OrganizerEventQueriesImpl(EventRepository events, TicketTierRepository tiers, OrganizerAccess access, JdbcClient jdbc) {
        this.events = events;
        this.tiers = tiers;
        this.access = access;
        this.jdbc = jdbc;
    }

    /** GET /organizer/events. status nhận thêm ENDED (trạng thái tính: đang liệt kê nhưng đã qua giờ kết thúc). */
    @Override
    @Transactional(readOnly = true)
    public PageResponse<OrganizerEventSummary> list(UUID userId, String status, String q, int page, int size) {
        UUID organizerId = access.currentOrganizer(userId).getId();
        int p = Pages.page(page);
        int s = Pages.clampSize(size);

        SqlWhere w = new SqlWhere().add("e.organizer_id = :org", "org", organizerId);
        String st = blankToNull(status);
        if (st != null) {
            switch (st) {
                case "DRAFT", "CANCELLED" -> w.add("e.status = :status", "status", st);
                case "PUBLISHED", "UPCOMING" -> w.add("e.status = :status", "status", st)
                        .add("coalesce(e.ends_at, e.starts_at) >= now()");
                case "ENDED" -> w.add("e.status in ('PUBLISHED', 'UPCOMING')")
                        .add("coalesce(e.ends_at, e.starts_at) < now()");
                default -> throw DomainException.invalid("status", "status phải là DRAFT, PUBLISHED, UPCOMING, CANCELLED hoặc ENDED");
            }
        }
        String text = blankToNull(q);
        if (text != null) w.add("e.name ilike :q", "q", likePattern(text));

        long total = jdbc.sql("select count(*) from events e" + w.sql()).params(w.params()).query(Long.class).single();
        List<UUID> ids = total == 0 ? List.of()
                : jdbc.sql("select e.id from events e" + w.sql() + " order by e.created_at desc, e.id limit :limit offset :offset")
                .params(w.params())
                .param("limit", s)
                .param("offset", Pages.offset(p, s))
                .query(UUID.class)
                .list();

        // Nạp entity + số liệu cho cả trang bằng 2 query (không N+1), rồi giữ đúng thứ tự id của SQL
        Map<UUID, Event> byId = events.findAllById(ids).stream().collect(Collectors.toMap(Event::getId, Function.identity()));
        Map<UUID, EventStats> stats = stats(ids);
        Instant now = Instant.now();
        List<OrganizerEventSummary> content = ids.stream().map(byId::get).filter(Objects::nonNull).map(e -> {
            EventStats es = stats.getOrDefault(e.getId(), EventStats.EMPTY);
            return OrganizerEventSummary.from(e, displayStatus(e, es, now), es.sold(), es.total(), es.revenue());
        }).toList();
        return Pages.response(content, p, s, total);
    }

    @Override
    @Transactional(readOnly = true)
    public OrganizerEventDetail get(UUID userId, UUID eventId) {
        return detail(access.ownEvent(userId, eventId));
    }

    /** GET /organizer/events/{id}/orders: q khớp mã đơn, email hoặc tên khách. */
    @Override
    @Transactional(readOnly = true)
    public PageResponse<OrganizerOrderRow> orders(UUID userId, UUID eventId, String status, String q, int page, int size) {
        Event e = access.ownEvent(userId, eventId);
        int p = Pages.page(page);
        int s = Pages.clampSize(size);

        SqlWhere w = new SqlWhere().add("o.event_id = :eventId", "eventId", e.getId());
        String st = blankToNull(status);
        if (st != null) w.add("o.status = :status", "status", st);
        String text = blankToNull(q);
        if (text != null) {
            w.add("(cast(o.order_code as text) like :q or o.customer_email ilike :q or o.customer_name ilike :q)",
                    "q", likePattern(text));
        }

        long total = jdbc.sql("select count(*) from orders o" + w.sql()).params(w.params()).query(Long.class).single();
        List<OrganizerOrderRow> content = total == 0 ? List.of() : jdbc.sql("""
                        select o.id, o.order_code, o.status, o.customer_name, o.customer_email, o.customer_phone,
                               (select coalesce(sum(oi.quantity), 0) from order_items oi where oi.order_id = o.id) as quantity,
                               o.subtotal_amount, o.total_amount, o.created_at, o.paid_at
                        from orders o""" + w.sql() + " order by o.created_at desc, o.id limit :limit offset :offset")
                .params(w.params())
                .param("limit", s)
                .param("offset", Pages.offset(p, s))
                .query((rs, i) -> new OrganizerOrderRow(rs.getObject("id", UUID.class), rs.getLong("order_code"),
                        rs.getString("status"),
                        new OrganizerOrderRow.Customer(rs.getString("customer_name"), rs.getString("customer_email"),
                                rs.getString("customer_phone")),
                        rs.getLong("quantity"), rs.getLong("subtotal_amount"), rs.getLong("total_amount"),
                        instant(rs, "created_at"), instant(rs, "paid_at")))
                .list();
        return Pages.response(content, p, s, total);
    }

    /**
     * Chi tiết + số liệu theo hạng vé. CỐ Ý không @Transactional: OrganizerEventService gọi ngay sau khi ghi nên hàm này
     * chạy trong transaction ghi đang mở; gắn readOnly ở đây có thể lan chế độ chỉ-đọc sang transaction bên gọi.
     */
    @Override
    public OrganizerEventDetail detail(Event e) {
        Map<UUID, TierNumbers> numbers = tierNumbers(e.getId());
        List<OrganizerEventDetail.Tier> tierDtos = tiersOf(e.getId()).stream().map(t -> {
            TierNumbers n = numbers.getOrDefault(t.getId(), TierNumbers.EMPTY);
            // Vé không còn trong kho mà chưa phát ra = đang được giữ bởi đơn chờ thanh toán
            long reserved = Math.max(0, t.getTotalQuantity() - n.available() - n.sold());
            return new OrganizerEventDetail.Tier(t.getId(), t.getName(), t.getDescription(), t.getPrice(),
                    t.getTotalQuantity(), t.getMaxPerOrder(), n.sold(), reserved, n.available(), n.revenue());
        }).toList();
        EventStats st = stats(List.of(e.getId())).getOrDefault(e.getId(), EventStats.EMPTY);
        return OrganizerEventDetail.from(e, displayStatus(e, st, Instant.now()), tierDtos,
                new OrganizerEventDetail.Stats(st.sold(), st.total(), st.revenue(), st.ordersPaid(), st.ordersPending()));
    }

    /** Thứ tự ổn định (giá, rồi thời điểm tạo) để chỉ số tiers[i] trong lỗi publish khớp với danh sách FE đang hiển thị. */
    @Override
    public List<TicketTier> tiersOf(UUID eventId) {
        return tiers.findAllByEventIdOrderByPriceAsc(eventId).stream()
                .sorted(Comparator.comparingLong(TicketTier::getPrice)
                        .thenComparing(TicketTier::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(TicketTier::getId))
                .toList();
    }

    private record TierNumbers(long available, long sold, long revenue) {
        static final TierNumbers EMPTY = new TierNumbers(0, 0, 0);
    }

    /** Theo từng hạng vé: còn trong kho, số vé đã phát, doanh thu từ đơn đã PAID. */
    private Map<UUID, TierNumbers> tierNumbers(UUID eventId) {
        Map<UUID, TierNumbers> out = new HashMap<>();
        jdbc.sql("""
                        select tt.id, coalesce(i.available, 0) as available,
                               (select count(*) from tickets t where t.ticket_tier_id = tt.id) as sold,
                               (select coalesce(sum(oi.quantity * oi.unit_price), 0) from order_items oi
                                  join orders o on o.id = oi.order_id
                                 where oi.ticket_tier_id = tt.id and o.status = 'PAID') as revenue
                        from ticket_tiers tt left join inventory i on i.ticket_tier_id = tt.id
                        where tt.event_id = :id
                        """)
                .param("id", eventId)
                .query(rs -> {
                    out.put(rs.getObject("id", UUID.class),
                            new TierNumbers(rs.getLong("available"), rs.getLong("sold"), rs.getLong("revenue")));
                });
        return out;
    }

    private record EventStats(long total, long available, long tierCount, long sold, long revenue,
                              long ordersPaid, long ordersPending) {
        static final EventStats EMPTY = new EventStats(0, 0, 0, 0, 0, 0, 0);
    }

    /** Một query cho cả trang: tổng vé, còn lại, số hạng vé, đã phát, doanh thu và số đơn theo sự kiện. */
    private Map<UUID, EventStats> stats(List<UUID> eventIds) {
        if (eventIds.isEmpty()) return Map.of();
        Map<UUID, EventStats> out = new HashMap<>();
        jdbc.sql("""
                        select e.id,
                               (select coalesce(sum(tt.total_quantity), 0) from ticket_tiers tt where tt.event_id = e.id) as total,
                               (select coalesce(sum(i.available), 0) from ticket_tiers tt
                                  join inventory i on i.ticket_tier_id = tt.id where tt.event_id = e.id) as available,
                               (select count(*) from ticket_tiers tt where tt.event_id = e.id) as tier_count,
                               (select count(*) from tickets t join ticket_tiers tt on tt.id = t.ticket_tier_id
                                 where tt.event_id = e.id) as sold,
                               (select coalesce(sum(o.subtotal_amount), 0) from orders o
                                 where o.event_id = e.id and o.status = 'PAID') as revenue,
                               (select count(*) from orders o
                                 where o.event_id = e.id and o.status = 'PAID') as orders_paid,
                               (select count(*) from orders o
                                 where o.event_id = e.id and o.status = 'PENDING_PAYMENT') as orders_pending
                        from events e where e.id in (:ids)
                        """)
                .param("ids", eventIds)
                .query(rs -> {
                    out.put(rs.getObject("id", UUID.class), new EventStats(rs.getLong("total"), rs.getLong("available"),
                            rs.getLong("tier_count"), rs.getLong("sold"), rs.getLong("revenue"),
                            rs.getLong("orders_paid"), rs.getLong("orders_pending")));
                });
        return out;
    }

    /** Cùng luật với trang công khai: SOLD_OUT khi PUBLISHED, có hạng vé và kho đã hết; ENDED khi đã qua giờ kết thúc. */
    private static String displayStatus(Event e, EventStats st, Instant now) {
        boolean soldOut = e.getStatus() == EventStatus.PUBLISHED && st.tierCount() > 0 && st.available() == 0;
        return e.displayStatus(soldOut, now);
    }
}
