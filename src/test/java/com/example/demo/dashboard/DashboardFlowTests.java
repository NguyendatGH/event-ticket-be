package com.example.demo.dashboard;

import com.example.demo.domain.common.VietnamTime;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.jwt.secret=test-secret-test-secret-test-secret-1234",
        "DB_URL=unused", "DB_USERNAME=unused", "DB_PASSWORD=unused"
})
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DashboardFlowTests {

    @TestConfiguration(proxyBeanMethods = false)
    static class Containers {
        @Bean
        @ServiceConnection
        PostgreSQLContainer postgres() {
            return new PostgreSQLContainer("postgres:16-alpine");
        }
    }

    static final String BASE = "/api/v1/organizer/dashboard/";
    static final LocalDate TODAY = LocalDate.now(VietnamTime.ZONE);

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    String organizer;

    @BeforeAll
    void seed() throws Exception {
        jdbc.execute(new ClassPathResource("seed/seed-dev.sql").getContentAsString(StandardCharsets.UTF_8));
        organizer = token("organizer@example.com");
    }

    private RestClient http() {
        return RestClient.builder().baseUrl("http://localhost:" + port)
                .defaultStatusHandler(status -> true, (req, res) -> { }).build();
    }

    @SuppressWarnings("unchecked")
    private String token(String email) {
        Map<String, Object> body = http().post().uri("/api/v1/auth/login")
                .body(Map.of("email", email, "password", "password123")).retrieve().body(Map.class);
        return (String) body.get("accessToken");
    }

    @SuppressWarnings("unchecked")
    private <T> ResponseEntity<T> get(String path, String token, Class<T> type) {
        return http().get().uri(BASE + path).headers(h -> { if (token != null) h.setBearerAuth(token); })
                .retrieve().toEntity(type);
    }

    private Map<?, ?> body(String path) {
        ResponseEntity<Map> res = get(path, organizer, Map.class);
        assertEquals(200, res.getStatusCode().value(), String.valueOf(res.getBody()));
        return res.getBody();
    }

    private List<Map<String, Object>> list(String path) {
        ResponseEntity<List> res = get(path, organizer, List.class);
        assertEquals(200, res.getStatusCode().value(), String.valueOf(res.getBody()));
        @SuppressWarnings("unchecked") List<Map<String, Object>> l = res.getBody();
        return l;
    }

    private long[] expected(LocalDate from, LocalDate to) {
        return jdbc.queryForObject("""
                select count(*), coalesce(sum((select count(*) from tickets t where t.order_id = o.id)), 0),
                       coalesce(sum(o.subtotal_amount), 0)
                from orders o join events e on e.id = o.event_id join organizers g on g.id = e.organizer_id
                where g.slug = 'sunrise-live' and o.status = 'PAID'
                  and (o.paid_at at time zone 'Asia/Ho_Chi_Minh')::date between ? and ?""",
                (rs, i) -> new long[]{rs.getLong(1), rs.getLong(2), rs.getLong(3)}, from, to);
    }

    private static long num(Object o) {
        return ((Number) o).longValue();
    }

    @Test
    void summaryMatchesIndependentSql() {
        Map<?, ?> s = body("summary");
        assertEquals(TODAY.minusDays(29).toString(), s.get("from"));
        assertEquals(TODAY.toString(), s.get("to"));
        long[] cur = expected(TODAY.minusDays(29), TODAY);
        long[] prev = expected(TODAY.minusDays(59), TODAY.minusDays(30));
        assertTrue(cur[0] > 0 && prev[0] > 0, "seed có đơn trong cả hai khoảng");
        Map<?, ?> orders = (Map<?, ?>) s.get("orders");
        Map<?, ?> tickets = (Map<?, ?>) s.get("ticketsSold");
        Map<?, ?> revenue = (Map<?, ?>) s.get("revenue");
        assertEquals(cur[0], num(orders.get("value")));
        assertEquals(prev[0], num(orders.get("previous")));
        assertEquals(cur[1], num(tickets.get("value")));
        assertEquals(prev[1], num(tickets.get("previous")));
        assertEquals(cur[2], num(revenue.get("value")));
        assertEquals(prev[2], num(revenue.get("previous")));
        assertEquals(Math.round((cur[2] - prev[2]) * 1000.0 / prev[2]) / 10.0, ((Number) revenue.get("changePct")).doubleValue());

        assertEquals(Map.of("total", 4, "draft", 1, "published", 1, "upcoming", 1, "ended", 1, "cancelled", 0), s.get("events"));
        Map<?, ?> t = (Map<?, ?>) s.get("tickets");
        assertEquals(num(t.get("total")), num(t.get("sold")) + num(t.get("available")), "seed: kho = tổng − đã bán");
        assertEquals(jdbc.queryForObject("""
                select count(*) from tickets k join ticket_tiers tt on tt.id = k.ticket_tier_id join events e on e.id = tt.event_id
                join organizers g on g.id = e.organizer_id where g.slug = 'sunrise-live'""", Long.class), num(t.get("sold")));
    }

    @Test
    void emptyPreviousGivesNullChangePct() {
        Map<?, ?> revenue = (Map<?, ?>) body("summary?from=2025-01-01&to=2025-01-31").get("revenue");
        assertEquals(0L, num(revenue.get("value")));
        assertNull(revenue.get("changePct"));
    }

    @Test
    void seriesHaveEveryBucketAndSumToTotals() {
        List<Map<String, Object>> days = list("sales");
        assertEquals(30, days.size());
        assertEquals(TODAY.minusDays(29).toString(), days.getFirst().get("date"));
        assertEquals(TODAY.toString(), days.getLast().get("date"));
        assertTrue(days.stream().anyMatch(d -> num(d.get("orders")) == 0), "ngày trống vẫn có mặt");
        long[] cur = expected(TODAY.minusDays(29), TODAY);
        assertEquals(cur[0], days.stream().mapToLong(d -> num(d.get("orders"))).sum());
        assertEquals(cur[1], days.stream().mapToLong(d -> num(d.get("tickets"))).sum());

        List<Map<String, Object>> weeks = list("sales?interval=week&from=2026-08-01&to=2026-08-31");
        assertEquals(List.of("2026-07-27", "2026-08-03", "2026-08-10", "2026-08-17", "2026-08-24", "2026-08-31"),
                weeks.stream().map(w -> w.get("date")).toList(), "bucket tuần = thứ 2");

        List<Map<String, Object>> months = list("revenue?interval=month&from=2026-01-15&to=2026-12-31");
        assertEquals(12, months.size());
        assertEquals("2026-01-01", months.getFirst().get("date"));
        LocalDate from = TODAY.minusDays(29);
        assertEquals(cur[2], list("revenue?from=" + from).stream().mapToLong(d -> num(d.get("revenue"))).sum());
    }

    @Test
    void topEventsAndEventFilter() {
        List<Map<String, Object>> top = list("top-events?from=" + TODAY.minusDays(365) + "&limit=2");
        assertEquals(2, top.size());
        assertTrue(num(top.get(0).get("revenue")) >= num(top.get(1).get("revenue")));
        assertTrue(top.stream().anyMatch(e -> "ENDED".equals(e.get("status"))), "Summer Sessions đã qua");
        assertEquals(1, list("top-events?from=" + TODAY.minusDays(365) + "&limit=1").size());

        String lumiere = jdbc.queryForObject("select id::text from events where slug = 'the-lumiere-tour'", String.class);
        Map<?, ?> filtered = body("summary?eventId=" + lumiere);
        assertEquals(jdbc.queryForObject("select sum(total_quantity) from ticket_tiers where event_id = ?::uuid", Long.class, lumiere),
                num(((Map<?, ?>) filtered.get("tickets")).get("total")));

        String jazz = jdbc.queryForObject("select e.id::text from events e join organizers g on g.id = e.organizer_id "
                + "where g.slug = 'saigon-jazz-club' limit 1", String.class);
        ResponseEntity<Map> other = get("sales?eventId=" + jazz, organizer, Map.class);
        assertEquals(404, other.getStatusCode().value());
        assertEquals("EVENT_NOT_FOUND", other.getBody().get("code"));
    }

    @Test
    void errorCodes() {
        assertEquals(401, get("summary", null, Map.class).getStatusCode().value());
        assertEquals(403, get("summary", token("a@example.com"), Map.class).getStatusCode().value());
        ResponseEntity<Map> admin = get("summary", token("admin@example.com"), Map.class);
        assertEquals(404, admin.getStatusCode().value());
        assertEquals("ORGANIZER_NOT_FOUND", admin.getBody().get("code"));

        for (String bad : List.of("summary?from=2026-09-10&to=2026-09-01", "sales?from=2025-01-01&to=2026-09-27",
                "revenue?interval=year", "sales?from=abc", "top-events?limit=51", "summary?eventId=nope")) {
            ResponseEntity<Map> res = get(bad, organizer, Map.class);
            assertEquals(400, res.getStatusCode().value(), bad);
            assertEquals("VALIDATION", res.getBody().get("code"), bad);
            assertFalse(((List<?>) res.getBody().get("errors")).isEmpty(), bad);
        }
    }
}
