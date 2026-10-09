package com.example.demo.events;

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
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.jwt.secret=test-secret-test-secret-test-secret-1234",
        "DB_URL=unused", "DB_USERNAME=unused", "DB_PASSWORD=unused"
})
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EventsApiTests {

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

    String organizer;
    String organizer2;
    String customer;
    String admin;

    @BeforeAll
    void seed() throws Exception {
        jdbc.execute(new ClassPathResource("seed/seed-dev.sql").getContentAsString(StandardCharsets.UTF_8));
        organizer = token("organizer@example.com");
        organizer2 = token("organizer2@example.com");
        customer = token("a@example.com");
        admin = token("admin@example.com");
    }


    private RestClient http() {
        return RestClient.builder().baseUrl("http://localhost:" + port)
                .defaultStatusHandler(status -> true, (req, res) -> { }).build();
    }

    @SuppressWarnings("unchecked")
    private String token(String email) {
        return (String) http().post().uri("/api/v1/auth/login").body(Map.of("email", email, "password", "password123"))
                .retrieve().body(Map.class).get("accessToken");
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<Map> call(HttpMethod method, String path, String token, Object body) {
        var spec = http().method(method).uri(path).headers(h -> { if (token != null) h.setBearerAuth(token); });
        if (body != null) spec = spec.body(body);
        return spec.retrieve().toEntity(Map.class);
    }

    private Map<?, ?> ok(ResponseEntity<Map> res) {
        assertTrue(res.getStatusCode().is2xxSuccessful(), res.getStatusCode() + " " + res.getBody());
        return res.getBody();
    }

    private Map<?, ?> get(String path) {
        return ok(call(HttpMethod.GET, path, null, null));
    }

    @SuppressWarnings("unchecked")
    private List<Object> list(String path) {
        return http().get().uri(path).retrieve().body(List.class);
    }

    private static List<?> content(Map<?, ?> page) {
        return (List<?>) page.get("content");
    }

    private static List<Object> slugs(List<?> events) {
        return events.stream().<Object>map(e -> ((Map<?, ?>) e).get("slug")).toList();
    }

    private void assertError(ResponseEntity<Map> res, int status, String code) {
        assertEquals(status, res.getStatusCode().value(), String.valueOf(res.getBody()));
        assertEquals(code, res.getBody().get("code"), String.valueOf(res.getBody()));
    }

    private String eventId(String slug) {
        return jdbc.queryForObject("select id::text from events where slug = ?", String.class, slug);
    }

    private Map<String, Object> upsertFrom(Map<?, ?> detail) {
        Map<String, Object> body = new HashMap<>();
        for (String k : List.of("name", "category", "tagline", "description", "coverImageUrl", "coverImageAlt", "startsAt",
                "endsAt", "venue", "schedule")) {
            body.put(k, detail.get(k));
        }
        body.put("tiers", new ArrayList<>(((List<?>) detail.get("tiers")).stream().map(t -> {
            Map<?, ?> m = (Map<?, ?>) t;
            Map<String, Object> tier = new HashMap<>();
            for (String k : List.of("id", "name", "description", "price", "totalQuantity", "maxPerOrder")) tier.put(k, m.get(k));
            return tier;
        }).toList()));
        return body;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> tier(Map<String, Object> body, int i) {
        return ((List<Map<String, Object>>) body.get("tiers")).get(i);
    }

    @Test
    void concurrentTierEditsKeepInventoryEqualToTotal() throws Exception {
        String id = (String) ok(call(HttpMethod.POST, "/api/v1/organizer/events", organizer2, Map.of("name", "Race kho",
                "tiers", List.of(Map.of("name", "GA", "price", 100_000, "totalQuantity", 100, "maxPerOrder", 4))))).get("id");

        List<java.util.concurrent.CompletableFuture<ResponseEntity<Map>>> puts = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            Map<String, Object> body = upsertFrom(ok(call(HttpMethod.GET, "/api/v1/organizer/events/" + id, organizer2, null)));
            tier(body, 0).put("totalQuantity", 100 + 10 * i);
            puts.add(java.util.concurrent.CompletableFuture.supplyAsync(() -> call(HttpMethod.PUT, "/api/v1/organizer/events/" + id, organizer2, body)));
        }
        puts.forEach(f -> ok(f.join()));

        Map<?, ?> t0 = (Map<?, ?>) ((List<?>) ok(call(HttpMethod.GET, "/api/v1/organizer/events/" + id, organizer2, null)).get("tiers")).getFirst();
        assertEquals(t0.get("totalQuantity"), t0.get("available"), "chưa bán vé nào thì available phải bằng totalQuantity");
        assertEquals(204, call(HttpMethod.DELETE, "/api/v1/organizer/events/" + id, organizer2, null).getStatusCode().value());
    }


    @Test
    void publicListHidesPastAndDraftUnlessAsked() {
        List<Object> all = slugs(content(get("/api/v1/events?size=50")));
        assertFalse(all.contains("sunrise-summer-sessions-2026"), "sự kiện đã qua bị ẩn mặc định");
        assertFalse(all.contains("sunrise-countdown-2027"), "DRAFT không liệt kê");
        assertTrue(slugs(content(get("/api/v1/events?size=50&includePast=true"))).contains("sunrise-summer-sessions-2026"));
    }

    @Test
    void publicListFiltersAndSorts() {
        List<Object> sunrise = slugs(content(get("/api/v1/events?q=sunrise")));
        assertTrue(sunrise.containsAll(List.of("the-lumiere-tour", "da-nang-beach-fest")), sunrise.toString());
        assertEquals(sunrise, slugs(content(get("/api/v1/events?organizer=sunrise-live"))));
        assertEquals(List.of("hamlet-ban-dung-moi"), slugs(content(get("/api/v1/events?category=theatre"))));
        assertEquals(List.of("workshop-gom-raku", "da-nang-beach-fest"), slugs(content(get("/api/v1/events?city=Đà Nẵng"))));
        assertEquals(List.of("trien-lam-anh-duong-pho"), slugs(content(get("/api/v1/events?priceMax=100000"))));
        assertEquals(List.of("the-lumiere-tour"), slugs(content(get("/api/v1/events?from=2026-10-24&to=2026-10-24"))));

        List<?> byPrice = content(get("/api/v1/events?sort=price&size=50"));
        List<Long> prices = byPrice.stream().map(e -> ((Number) ((Map<?, ?>) e).get("priceFrom")).longValue()).toList();
        assertEquals(prices.stream().sorted().toList(), prices);
        Map<?, ?> page = get("/api/v1/events?size=5&page=1&sort=popular");
        assertEquals(1, page.get("number"));
        assertEquals(5, content(page).size());
        assertTrue(((Number) page.get("totalElements")).longValue() >= 12);

        assertError(call(HttpMethod.GET, "/api/v1/events?when=someday", null, null), 400, "VALIDATION");
        assertError(call(HttpMethod.GET, "/api/v1/events?sort=name", null, null), 400, "VALIDATION");
    }

    @Test
    void featuredUpcomingFacetsAndDetailLinks() {
        assertTrue(slugs(list("/api/v1/events/featured")).contains("the-lumiere-tour"));
        List<Object> upcoming = slugs(list("/api/v1/events/upcoming?limit=3"));
        assertEquals(3, upcoming.size());
        assertEquals("trien-lam-anh-duong-pho", upcoming.getFirst());

        Map<?, ?> facets = get("/api/v1/events/facets");
        assertTrue(facets.get("categories").toString().contains("slug=music"), facets.toString());
        assertTrue(facets.get("cities").toString().contains("name=Đà Nẵng"), facets.toString());
        assertEquals(40_000, ((Number) ((Map<?, ?>) facets.get("price")).get("min")).longValue());

        assertError(call(HttpMethod.GET, "/api/v1/events/sunrise-countdown-2027", null, null), 404, "EVENT_NOT_FOUND");
        List<Object> more = slugs(list("/api/v1/events/the-lumiere-tour/more-from-organizer"));
        assertEquals(List.of("da-nang-beach-fest"), more);
        List<Object> related = slugs(list("/api/v1/events/the-lumiere-tour/related?limit=10"));
        assertFalse(related.contains("the-lumiere-tour"));
        assertTrue(related.contains("dem-nhac-trinh"));
    }

    @Test
    void organizerPublicEventsByScope() {
        assertEquals(List.of("the-lumiere-tour", "da-nang-beach-fest"),
                slugs(content(get("/api/v1/organizers/sunrise-live/events"))));
        assertEquals(List.of("sunrise-summer-sessions-2026"),
                slugs(content(get("/api/v1/organizers/sunrise-live/events?scope=past"))));
        assertError(call(HttpMethod.GET, "/api/v1/organizers/khong-co/events", null, null), 404, "ORGANIZER_NOT_FOUND");
    }


    @Test
    void draftLifecycleCreatePublishDelete() {
        ResponseEntity<Map> created = call(HttpMethod.POST, "/api/v1/organizer/events", organizer2, Map.of("name", "Đêm nhạc Đà Lạt"));
        assertEquals(201, created.getStatusCode().value(), String.valueOf(created.getBody()));
        Map<?, ?> draft = created.getBody();
        assertEquals("DRAFT", draft.get("status"));
        assertEquals("dem-nhac-da-lat", draft.get("slug"));
        String id = (String) draft.get("id");
        assertEquals("dem-nhac-da-lat-2",
                ok(call(HttpMethod.POST, "/api/v1/organizer/events", organizer2, Map.of("name", "Đêm nhạc đà lạt"))).get("slug"));

        ResponseEntity<Map> incomplete = call(HttpMethod.POST, "/api/v1/organizer/events/" + id + "/publish", organizer2, null);
        assertError(incomplete, 400, "EVENT_INCOMPLETE");
        assertTrue(incomplete.getBody().get("errors").toString().contains("venue.city"), incomplete.getBody().toString());

        assertError(call(HttpMethod.POST, "/api/v1/organizer/events", organizer2, Map.of("name", "X", "category", "cooking")), 400, "VALIDATION");

        Map<String, Object> body = new HashMap<>(Map.of(
                "name", "Đêm nhạc Đà Lạt", "category", "music", "description", List.of("Một đêm nhạc"),
                "coverImageUrl", "https://img.example.com/c.jpg",
                "startsAt", Instant.now().plusSeconds(30 * 86_400).toString(),
                "venue", Map.of("name", "Quảng trường Lâm Viên", "city", "Đà Lạt"),
                "tiers", List.of(Map.of("name", "GA", "price", 300_000, "totalQuantity", 100, "maxPerOrder", 4))));
        Map<?, ?> updated = ok(call(HttpMethod.PUT, "/api/v1/organizer/events/" + id, organizer2, body));
        assertEquals("dem-nhac-da-lat", updated.get("slug"), "slug không đổi sau khi tạo");
        Map<?, ?> t0 = (Map<?, ?>) ((List<?>) updated.get("tiers")).getFirst();
        assertEquals(100, t0.get("available"));

        Map<?, ?> published = ok(call(HttpMethod.POST, "/api/v1/organizer/events/" + id + "/publish", organizer2, null));
        assertEquals("PUBLISHED", published.get("status"));
        assertNotNull(published.get("publishedAt"));
        assertEquals("dem-nhac-da-lat", get("/api/v1/events/dem-nhac-da-lat").get("slug"));
        assertError(call(HttpMethod.POST, "/api/v1/organizer/events/" + id + "/publish", organizer2, null), 409, "EVENT_NOT_DRAFT");
        assertError(call(HttpMethod.DELETE, "/api/v1/organizer/events/" + id, organizer2, null), 409, "EVENT_NOT_DELETABLE");

        assertError(call(HttpMethod.GET, "/api/v1/organizer/events/" + id, organizer, null), 404, "EVENT_NOT_FOUND");
        assertEquals(403, call(HttpMethod.GET, "/api/v1/organizer/events", customer, null).getStatusCode().value());
        assertError(call(HttpMethod.GET, "/api/v1/organizer/events", admin, null), 404, "ORGANIZER_NOT_FOUND");

        String draft2 = eventId("dem-nhac-da-lat-2");
        assertEquals(204, call(HttpMethod.DELETE, "/api/v1/organizer/events/" + draft2, organizer2, null).getStatusCode().value());
        assertError(call(HttpMethod.GET, "/api/v1/organizer/events/" + draft2, organizer2, null), 404, "EVENT_NOT_FOUND");
    }

    @Test
    void editingPublishedEventRespectsSoldTickets() {
        String id = eventId("the-lumiere-tour");
        Map<?, ?> detail = ok(call(HttpMethod.GET, "/api/v1/organizer/events/" + id, organizer, null));
        Map<?, ?> stats = (Map<?, ?>) detail.get("stats");
        assertTrue(((Number) stats.get("ticketsSold")).longValue() > 0, stats.toString());
        assertTrue(((Number) stats.get("revenue")).longValue() > 0, stats.toString());

        List<?> tiers = (List<?>) detail.get("tiers");
        int sold = -1;
        for (int i = 0; i < tiers.size(); i++) {
            if (((Number) ((Map<?, ?>) tiers.get(i)).get("sold")).intValue() > 0) { sold = i; break; }
        }
        assertTrue(sold >= 0, tiers.toString());
        Map<?, ?> soldTier = (Map<?, ?>) tiers.get(sold);
        int soldCount = ((Number) soldTier.get("sold")).intValue() + ((Number) soldTier.get("reserved")).intValue();
        int total = ((Number) soldTier.get("totalQuantity")).intValue();
        int available = ((Number) soldTier.get("available")).intValue();

        Map<String, Object> priceChange = upsertFrom(detail);
        tier(priceChange, sold).put("price", 1);
        assertError(call(HttpMethod.PUT, "/api/v1/organizer/events/" + id, organizer, priceChange), 409, "TIER_PRICE_LOCKED");

        Map<String, Object> below = upsertFrom(detail);
        tier(below, sold).put("totalQuantity", soldCount - 1);
        assertError(call(HttpMethod.PUT, "/api/v1/organizer/events/" + id, organizer, below), 409, "TIER_QUANTITY_BELOW_SOLD");

        Map<String, Object> removed = upsertFrom(detail);
        ((List<?>) removed.get("tiers")).remove(sold);
        assertError(call(HttpMethod.PUT, "/api/v1/organizer/events/" + id, organizer, removed), 409, "TIER_HAS_SALES");

        Map<String, Object> more = upsertFrom(detail);
        tier(more, sold).put("totalQuantity", total + 10);
        Map<?, ?> after = ok(call(HttpMethod.PUT, "/api/v1/organizer/events/" + id, organizer, more));
        Map<?, ?> afterTier = (Map<?, ?>) ((List<?>) after.get("tiers")).get(sold);
        assertEquals(available + 10, ((Number) afterTier.get("available")).intValue());

        String past = eventId("sunrise-summer-sessions-2026");
        Map<?, ?> pastDetail = ok(call(HttpMethod.GET, "/api/v1/organizer/events/" + past, organizer, null));
        assertEquals("ENDED", pastDetail.get("status"));
        assertError(call(HttpMethod.PUT, "/api/v1/organizer/events/" + past, organizer, upsertFrom(pastDetail)), 409, "EVENT_NOT_EDITABLE");
    }

    @Test
    void organizerListAndOrders() {
        Map<?, ?> page = ok(call(HttpMethod.GET, "/api/v1/organizer/events?size=50", organizer, null));
        Map<?, ?> lumiere = content(page).stream().map(e -> (Map<?, ?>) e)
                .filter(e -> "the-lumiere-tour".equals(e.get("slug"))).findFirst().orElseThrow();
        assertTrue(((Number) lumiere.get("ticketsSold")).longValue() > 0);
        assertTrue(((Number) lumiere.get("ticketsTotal")).longValue() > ((Number) lumiere.get("ticketsSold")).longValue());
        assertEquals(List.of("sunrise-summer-sessions-2026"),
                slugs(content(ok(call(HttpMethod.GET, "/api/v1/organizer/events?status=ENDED", organizer, null)))));
        assertTrue(slugs(content(ok(call(HttpMethod.GET, "/api/v1/organizer/events?status=DRAFT", organizer, null))))
                .contains("sunrise-countdown-2027"));
        assertError(call(HttpMethod.GET, "/api/v1/organizer/events?status=LIVE", organizer, null), 400, "VALIDATION");

        String id = eventId("the-lumiere-tour");
        Map<?, ?> orders = ok(call(HttpMethod.GET, "/api/v1/organizer/events/" + id + "/orders?status=PAID", organizer, null));
        assertTrue(((Number) orders.get("totalElements")).longValue() > 0, orders.toString());
        Map<?, ?> row = (Map<?, ?>) content(orders).getFirst();
        assertEquals("PAID", row.get("status"));
        assertTrue(((Number) row.get("quantity")).intValue() > 0);
        assertNotNull(((Map<?, ?>) row.get("customer")).get("email"));
        String email = (String) ((Map<?, ?>) row.get("customer")).get("email");
        Map<?, ?> byEmail = ok(call(HttpMethod.GET, "/api/v1/organizer/events/" + id + "/orders?q=" + email, organizer, null));
        assertTrue(content(byEmail).stream().allMatch(o -> email.equals(((Map<?, ?>) ((Map<?, ?>) o).get("customer")).get("email"))));
        assertError(call(HttpMethod.GET, "/api/v1/organizer/events/" + UUID.randomUUID() + "/orders", organizer, null), 404, "EVENT_NOT_FOUND");
    }
}
