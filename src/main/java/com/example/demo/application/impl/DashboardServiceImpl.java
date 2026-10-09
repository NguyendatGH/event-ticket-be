package com.example.demo.application.impl;

import com.example.demo.application.DashboardRange;
import com.example.demo.application.DashboardService;
import com.example.demo.application.dto.DashboardRevenuePoint;
import com.example.demo.application.dto.DashboardSalesPoint;
import com.example.demo.application.dto.DashboardSummary;
import com.example.demo.application.dto.DashboardSummary.EventCounts;
import com.example.demo.application.dto.DashboardSummary.Metric;
import com.example.demo.application.dto.DashboardSummary.TicketCounts;
import com.example.demo.application.dto.DashboardTopEvent;
import com.example.demo.domain.common.DomainException;
import com.example.demo.domain.common.VietnamTime;
import com.example.demo.domain.organizer.Organizer;
import com.example.demo.infrastructure.persistence.OrganizerRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class DashboardServiceImpl implements DashboardService {

    private static final String PAID_ORDERS = """
            from orders o
            join events e on e.id = o.event_id
            cross join lateral (select coalesce(sum(i.quantity), 0) as qty from order_items i where i.order_id = o.id) q
            where e.organizer_id = :org and o.status = 'PAID'
              and o.paid_at >= :start and o.paid_at < :end
            """;

    private static final String DISPLAY_STATUS = """
            case when e.status in ('PUBLISHED', 'UPCOMING') and coalesce(e.ends_at, e.starts_at) < now() then 'ENDED'
                 when e.status = 'PUBLISHED' and exists (select 1 from ticket_tiers t where t.event_id = e.id)
                      and not exists (select 1 from ticket_tiers t join inventory iv on iv.ticket_tier_id = t.id
                                      where t.event_id = e.id and iv.available > 0) then 'SOLD_OUT'
                 else e.status end""";

    private record Totals(long revenue, long tickets, long orders) {}

    private record Bucket(LocalDate date, long tickets, long orders, long revenue) {}

    private final JdbcClient jdbc;
    private final OrganizerRepository organizers;

    public DashboardServiceImpl(JdbcClient jdbc, OrganizerRepository organizers) {
        this.jdbc = jdbc;
        this.organizers = organizers;
    }

    @Override
    public DashboardSummary summary(UUID userId, DashboardRange range) {
        UUID org = organizerOf(userId, range);
        Totals now = totals(org, range);
        Totals before = totals(org, range.previous());
        return new DashboardSummary(range.from(), range.to(),
                Metric.of(now.revenue(), before.revenue()),
                Metric.of(now.tickets(), before.tickets()),
                Metric.of(now.orders(), before.orders()),
                eventCounts(org), ticketCounts(org, range.eventId()));
    }

    @Override
    public List<DashboardSalesPoint> sales(UUID userId, DashboardRange range) {
        return buckets(organizerOf(userId, range), range).stream()
                .map(b -> new DashboardSalesPoint(b.date(), b.tickets(), b.orders())).toList();
    }

    @Override
    public List<DashboardRevenuePoint> revenue(UUID userId, DashboardRange range) {
        return buckets(organizerOf(userId, range), range).stream()
                .map(b -> new DashboardRevenuePoint(b.date(), b.revenue())).toList();
    }

    @Override
    public List<DashboardTopEvent> topEvents(UUID userId, DashboardRange range, int limit) {
        UUID org = organizerOf(userId, range);
        return jdbc.sql("select e.id, e.slug, e.name, e.starts_at, " + DISPLAY_STATUS + " as status,"
                        + " sum(q.qty) as tickets, sum(o.subtotal_amount) as revenue "
                        + PAID_ORDERS + eventFilter(range.eventId())
                        + " group by e.id order by revenue desc, tickets desc, e.starts_at limit :limit")
                .params(params(org, range)).param("limit", limit)
                .query((rs, i) -> new DashboardTopEvent(rs.getObject("id", UUID.class), rs.getString("slug"),
                        rs.getString("name"), rs.getTimestamp("starts_at").toInstant(), rs.getString("status"),
                        rs.getLong("tickets"), rs.getLong("revenue")))
                .list();
    }

    private UUID organizerOf(UUID userId, DashboardRange range) {
        UUID org = organizers.findByUserId(userId).map(Organizer::getId)
                .orElseThrow(() -> DomainException.notFound("ORGANIZER_NOT_FOUND", "Bạn chưa có hồ sơ ban tổ chức"));
        if (range.eventId() != null) {
            boolean owned = jdbc.sql("select exists (select 1 from events where id = :id and organizer_id = :org)")
                    .param("id", range.eventId()).param("org", org).query(Boolean.class).single();
            if (!owned) throw DomainException.notFound("EVENT_NOT_FOUND", "Không tìm thấy sự kiện");
        }
        return org;
    }

    private Totals totals(UUID org, DashboardRange range) {
        return jdbc.sql("select coalesce(sum(o.subtotal_amount), 0) as revenue, coalesce(sum(q.qty), 0) as tickets,"
                        + " count(*) as orders " + PAID_ORDERS + eventFilter(range.eventId()))
                .params(params(org, range))
                .query((rs, i) -> new Totals(rs.getLong("revenue"), rs.getLong("tickets"), rs.getLong("orders")))
                .single();
    }

    private List<Bucket> buckets(UUID org, DashboardRange range) {
        String unit = range.interval().name();
        String sql = """
                with b as (
                    select g::date as bucket
                    from generate_series(date_trunc('%1$s', cast(:fromDay as timestamp)),
                                         date_trunc('%1$s', cast(:toDay as timestamp)), interval '1 %1$s') g
                ), s as (
                    select date_trunc('%1$s', o.paid_at at time zone '%2$s')::date as bucket,
                           sum(q.qty) as tickets, count(*) as orders, sum(o.subtotal_amount) as revenue
                    %3$s %4$s
                    group by 1
                )
                select b.bucket, coalesce(s.tickets, 0) as tickets, coalesce(s.orders, 0) as orders,
                       coalesce(s.revenue, 0) as revenue
                from b left join s on s.bucket = b.bucket
                order by b.bucket
                """.formatted(unit, VietnamTime.ZONE_ID, PAID_ORDERS, eventFilter(range.eventId()));
        return jdbc.sql(sql).params(params(org, range))
                .param("fromDay", range.from()).param("toDay", range.to())
                .query((rs, i) -> new Bucket(rs.getObject("bucket", LocalDate.class), rs.getLong("tickets"),
                        rs.getLong("orders"), rs.getLong("revenue")))
                .list();
    }

    private EventCounts eventCounts(UUID org) {
        return jdbc.sql("""
                        select count(*) as total,
                               count(*) filter (where status = 'DRAFT') as draft,
                               count(*) filter (where status = 'PUBLISHED' and coalesce(ends_at, starts_at) >= now()) as published,
                               count(*) filter (where status = 'UPCOMING' and coalesce(ends_at, starts_at) >= now()) as upcoming,
                               count(*) filter (where status in ('PUBLISHED', 'UPCOMING') and coalesce(ends_at, starts_at) < now()) as ended,
                               count(*) filter (where status = 'CANCELLED') as cancelled
                        from events where organizer_id = :org""")
                .param("org", org)
                .query((rs, i) -> new EventCounts(rs.getLong("total"), rs.getLong("draft"), rs.getLong("published"),
                        rs.getLong("upcoming"), rs.getLong("ended"), rs.getLong("cancelled")))
                .single();
    }

    private TicketCounts ticketCounts(UUID org, UUID eventId) {
        String filter = eventId == null ? "" : " and e.id = :eventId";
        JdbcClient.StatementSpec spec = jdbc.sql("""
                        select coalesce(sum(t.total_quantity), 0) as total, coalesce(sum(iv.available), 0) as available,
                               (select count(*) from tickets k join ticket_tiers kt on kt.id = k.ticket_tier_id
                                join events e on e.id = kt.event_id
                                where e.organizer_id = :org and k.status <> 'REFUNDED'%1$s) as sold
                        from ticket_tiers t join events e on e.id = t.event_id
                        left join inventory iv on iv.ticket_tier_id = t.id
                        where e.organizer_id = :org%1$s""".formatted(filter))
                .param("org", org);
        if (eventId != null) spec = spec.param("eventId", eventId);
        return spec.query((rs, i) -> new TicketCounts(rs.getLong("total"), rs.getLong("sold"), rs.getLong("available")))
                .single();
    }

    private static String eventFilter(UUID eventId) {
        return eventId == null ? "" : " and o.event_id = :eventId";
    }

    private static Map<String, Object> params(UUID org, DashboardRange range) {
        Map<String, Object> p = new HashMap<>();
        p.put("org", org);
        p.put("start", Timestamp.from(range.start().toInstant()));
        p.put("end", Timestamp.from(range.end().toInstant()));
        if (range.eventId() != null) p.put("eventId", range.eventId());
        return p;
    }
}
