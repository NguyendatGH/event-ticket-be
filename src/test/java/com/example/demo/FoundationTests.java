package com.example.demo;

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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Nền chung cho mọi tính năng (ui-api-contract §2, §4.7, §5): Flyway V1+V2 khớp Hibernate validate, file seed chạy lại
 * được (idempotent) và nhất quán (kho = tổng − đã bán), event nhúng organizer từ bảng organizers, DRAFT ẩn với public,
 * ma trận quyền /organizer/**.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.jwt.secret=test-secret-test-secret-test-secret-1234",
        "DB_URL=unused", "DB_USERNAME=unused", "DB_PASSWORD=unused"
})
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FoundationTests {

    @TestConfiguration(proxyBeanMethods = false)
    static class Containers {
        @Bean
        @ServiceConnection
        PostgreSQLContainer postgres() {
            return new PostgreSQLContainer("postgres:16-alpine");
        }
    }

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;

    /** Chạy đúng file DevDataSeeder dùng (classpath seed/seed-dev.sql) hai lần: lần hai không được lỗi hay nhân đôi. */
    @BeforeAll
    void seedTwice() throws Exception {
        String sql = new ClassPathResource("seed/seed-dev.sql").getContentAsString(StandardCharsets.UTF_8);
        jdbc.execute(sql);
        jdbc.execute(sql);
    }

    private RestClient http() {
        return RestClient.builder().baseUrl("http://localhost:" + port)
                .defaultStatusHandler(status -> true, (req, res) -> { }).build();
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<Map> get(String path, String token) {
        return http().get().uri(path).headers(h -> { if (token != null) h.setBearerAuth(token); }).retrieve().toEntity(Map.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> login(String email) {
        return http().post().uri("/api/v1/auth/login").body(Map.of("email", email, "password", "password123")).retrieve().body(Map.class);
    }

    private long count(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }

    @Test
    void configIsPublicAndMatchesApplicationYaml() {
        ResponseEntity<Map> res = get("/api/v1/config", null);
        assertEquals(200, res.getStatusCode().value());
        assertEquals(12000, ((Number) res.getBody().get("checkoutFee")).intValue());
    }

    @Test
    void seedIsIdempotentAndConsistent() {
        assertEquals(5, count("select count(*) from users where email in "
                + "('a@example.com','b@example.com','admin@example.com','organizer@example.com','organizer2@example.com')"));
        assertEquals(15, count("select count(*) from events"));
        assertEquals(62, count("select count(*) from orders where status = 'PAID' and paid_at is not null"));
        assertEquals(0, count("select count(*) from tickets where owner_id is null"));
        assertEquals(0, count("""
                select count(*) from ticket_tiers t join inventory i on i.ticket_tier_id = t.id
                where t.total_quantity <> i.available + (select count(*) from tickets k where k.ticket_tier_id = t.id)"""),
                "kho = tổng − đã bán");
        assertEquals(0, count("select count(*) from orders where paid_at > now() or created_at < now() - interval '46 days' and event_id in "
                + "(select id from events where starts_at > now())"), "đơn của sự kiện sắp tới nằm trong 45 ngày gần nhất");
        assertEquals("sunrise-live", jdbc.queryForObject(
                "select o.slug from organizers o join users u on u.id = o.user_id where u.email = 'organizer@example.com'", String.class));
        assertEquals(0, count("select count(*) from events where organizer_id is null"));
    }

    @Test
    void eventsEmbedOrganizerAndHideDrafts() {
        Map<?, ?> detail = get("/api/v1/events/the-lumiere-tour", null).getBody();
        Map<?, ?> organizer = (Map<?, ?>) detail.get("organizer");
        assertEquals("sunrise-live", organizer.get("slug"), String.valueOf(detail));
        assertEquals(true, organizer.get("verified"));
        assertNotNull(organizer.get("description"));
        assertEquals(3, ((Number) organizer.get("eventsCount")).intValue(), "Lumière, Đà Nẵng, Summer Sessions; không tính DRAFT");

        Map<?, ?> page = get("/api/v1/events?size=50", null).getBody();
        Map<?, ?> first = (Map<?, ?>) ((List<?>) page.get("content")).getFirst();
        Map<?, ?> summaryOrg = (Map<?, ?>) first.get("organizer");
        assertNotNull(summaryOrg.get("slug"));
        assertNull(summaryOrg.get("eventsCount"), "summary không có eventsCount");

        assertEquals("ENDED", get("/api/v1/events/sunrise-summer-sessions-2026", null).getBody().get("status"));
        ResponseEntity<Map> draft = get("/api/v1/events/sunrise-countdown-2027", null);
        assertEquals(404, draft.getStatusCode().value());
        assertEquals("EVENT_NOT_FOUND", draft.getBody().get("code"));
    }

    @Test
    void organizerLoginAndSecurityMatrix() {
        Map<String, Object> org = login("organizer@example.com");
        Map<?, ?> user = (Map<?, ?>) org.get("user");
        assertEquals("ORGANIZER", user.get("role"));
        assertEquals("sunrise-live", ((Map<?, ?>) user.get("organizer")).get("slug"));
        String orgToken = (String) org.get("accessToken");
        String customerToken = (String) login("a@example.com").get("accessToken");

        assertEquals(401, get("/api/v1/organizer/anything", null).getStatusCode().value());
        ResponseEntity<Map> forbidden = get("/api/v1/organizer/anything", customerToken);
        assertEquals(403, forbidden.getStatusCode().value());
        assertEquals("FORBIDDEN", forbidden.getBody().get("code"));
        assertEquals(404, get("/api/v1/organizer/anything", orgToken).getStatusCode().value(), "qua được lớp quyền, chỉ chưa có endpoint");

        assertNotEquals(401, get("/api/v1/organizers/sunrise-live", null).getStatusCode().value());
        assertEquals(401, get("/api/v1/me/tickets", null).getStatusCode().value());
        assertEquals(403, get("/api/v1/users", orgToken).getStatusCode().value(), "GET /users chỉ ADMIN");
    }

    @Test
    @SuppressWarnings("unchecked")
    void ticketDetailHasIssuedHistoryDerivedFromTicket() {
        String token = (String) login("a@example.com").get("accessToken");
        Map<String, Object> page = get("/api/v1/me/tickets?scope=all&size=1", token).getBody();
        Map<String, Object> first = ((List<Map<String, Object>>) page.get("content")).get(0);
        assertNull(first.get("history"), "danh sách không kèm history");

        Map<String, Object> detail = get("/api/v1/me/tickets/" + first.get("id"), token).getBody();
        List<Map<String, Object>> history = (List<Map<String, Object>>) detail.get("history");
        assertEquals(1, history.size());
        Map<String, Object> issued = history.get(0);
        assertEquals("ISSUED", issued.get("type"));
        assertEquals(((Number) detail.get("price")).longValue(), ((Number) issued.get("price")).longValue());
        assertEquals(detail.get("issuedAt"), issued.get("at"));
        assertNull(issued.get("from"));
        assertEquals(Map.of("displayName", "Nguyen A."), issued.get("to"));
    }
}
