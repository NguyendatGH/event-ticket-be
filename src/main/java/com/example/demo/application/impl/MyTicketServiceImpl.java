package com.example.demo.application.impl;

import com.example.demo.application.MyTicketService;
import com.example.demo.application.dto.MyTicketResponse;
import com.example.demo.application.dto.PageResponse;
import com.example.demo.application.dto.TicketHistoryItem;
import com.example.demo.application.support.Pages;
import com.example.demo.domain.common.DomainException;
import com.example.demo.domain.event.Event;
import com.example.demo.domain.event.EventStatus;
import com.example.demo.domain.event.Venue;
import com.example.demo.domain.order.TicketStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.example.demo.application.support.SqlRows.instant;

/**
 * "Vé của tôi" (GET /me/tickets, /me/tickets/{id}, MyTicketsController) và lịch sử vé.
 * Đọc bằng JdbcClient: một query join vé + hạng vé + sự kiện + đơn cho cả trang (không N+1).
 */
@Service
public class MyTicketServiceImpl implements MyTicketService {

    private static final String FROM = """
            from tickets t
            join ticket_tiers tt on tt.id = t.ticket_tier_id
            join events e on e.id = tt.event_id
            join orders o on o.id = t.order_id
            where t.owner_id = :owner
            """;

    private static final String SELECT = """
            select t.id, t.ticket_code, t.status, t.price, t.issued_at, o.id order_id, o.order_code,
                   tt.id tier_id, tt.name tier_name,
                   e.id event_id, e.slug, e.name event_name, e.starts_at, e.ends_at, e.venue::text venue,
                   e.cover_image_url, e.status event_status
            """;

    /** Điều kiện thêm vào sau "where t.owner_id = :owner" và thứ tự sắp xếp của từng tab. */
    private record Scope(String extraWhere, String orderBy) {}

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public MyTicketServiceImpl(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** scope = upcoming (mặc định) | past | all. */
    @Override
    public PageResponse<MyTicketResponse> page(UUID owner, String scope, int page, int size) {
        Scope sc = switch (scope == null ? "upcoming" : scope) {
            case "upcoming" -> new Scope(" and coalesce(e.ends_at, e.starts_at) >= now()", " order by e.starts_at, t.issued_at, t.id");
            case "past" -> new Scope(" and coalesce(e.ends_at, e.starts_at) < now()", " order by e.starts_at desc, t.issued_at, t.id");
            case "all" -> new Scope("", " order by e.starts_at desc, t.issued_at, t.id");
            default -> throw DomainException.badRequest("VALIDATION", "scope phải là upcoming, past hoặc all")
                    .withError("scope", "upcoming|past|all");
        };
        int sz = Pages.size(size);
        int pg = Pages.page(page);
        long total = jdbc.sql("select count(*) " + FROM + sc.extraWhere()).param("owner", owner).query(Long.class).single();
        Instant now = Instant.now();
        List<MyTicketResponse> content = total == 0 ? List.of()
                : jdbc.sql(SELECT + FROM + sc.extraWhere() + sc.orderBy() + " limit :limit offset :offset")
                .param("owner", owner)
                .param("limit", sz)
                .param("offset", Pages.offset(pg, sz))
                .query((rs, i) -> map(rs, now))
                .list();
        return Pages.response(content, pg, sz, total);
    }

    /** Chỉ chủ vé thấy; vé của người khác cũng 404 để không lộ là vé đó có tồn tại. */
    @Override
    public MyTicketResponse detail(UUID owner, UUID ticketId) {
        Instant now = Instant.now();
        MyTicketResponse ticket = jdbc.sql(SELECT + FROM + " and t.id = :id").param("owner", owner).param("id", ticketId)
                .query((rs, i) -> map(rs, now))
                .optional()
                .orElseThrow(() -> DomainException.notFound("TICKET_NOT_FOUND", "Không tìm thấy vé"));
        return ticket.withHistory(history(owner, ticket));
    }

    /** Vé chỉ có một mốc: phát hành cho chủ vé (chủ vé không đổi sau khi cấp), suy ra từ vé + tên chủ vé. */
    private List<TicketHistoryItem> history(UUID owner, MyTicketResponse ticket) {
        String ownerName = jdbc.sql("select full_name from users where id = :id").param("id", owner)
                .query(String.class).single();
        return List.of(TicketHistoryItem.issued(ticket.price(), ticket.issuedAt(), ownerName));
    }

    private MyTicketResponse map(ResultSet rs, Instant now) throws SQLException {
        TicketStatus status = TicketStatus.valueOf(rs.getString("status"));
        long price = rs.getLong("price");
        Instant startsAt = instant(rs, "starts_at");
        Instant endsAt = instant(rs, "ends_at");
        String eventStatus = rs.getString("event_status");
        String displayStatus = Event.displayStatus(EventStatus.valueOf(eventStatus), startsAt, endsAt, false, now);
        return new MyTicketResponse(rs.getObject("id", UUID.class), rs.getString("ticket_code"), status, price,
                instant(rs, "issued_at"),
                new MyTicketResponse.Tier(rs.getObject("tier_id", UUID.class), rs.getString("tier_name")),
                new MyTicketResponse.Event(rs.getObject("event_id", UUID.class), rs.getString("slug"), rs.getString("event_name"),
                        startsAt, endsAt, parseVenue(rs.getString("venue")), rs.getString("cover_image_url"), displayStatus),
                rs.getObject("order_id", UUID.class), rs.getLong("order_code"), null);
    }

    /** Cột jsonb venue được select dạng text ({@code e.venue::text}) rồi parse lại thành record Venue. */
    Venue parseVenue(String raw) {
        return raw == null ? null : json.readValue(raw, Venue.class);
    }
}
