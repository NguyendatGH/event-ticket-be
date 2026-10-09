package com.example.demo.identity;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.jwt.secret=test-secret-test-secret-test-secret-1234",
        "app.storage.local-dir=target/test-uploads",
        "DB_URL=unused", "DB_USERNAME=unused", "DB_PASSWORD=unused"
})
@ActiveProfiles({"test", "dev"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IdentityFlowTests {

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

    @BeforeAll
    void seed() throws Exception {
        jdbc.execute(new ClassPathResource("seed/seed-dev.sql").getContentAsString(StandardCharsets.UTF_8));
    }


    private RestClient http() {
        return RestClient.builder().baseUrl("http://localhost:" + port)
                .defaultStatusHandler(status -> true, (req, res) -> { }).build();
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<Map> call(HttpMethod method, String path, String token, Object body) {
        var spec = http().method(method).uri(path).headers(h -> { if (token != null) h.setBearerAuth(token); });
        if (body != null) spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
        return spec.retrieve().toEntity(Map.class);
    }

    private ResponseEntity<Map> post(String path, String token, Object body) {
        return call(HttpMethod.POST, path, token, body);
    }

    private static String email() {
        return "u" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
    }

    private Map<?, ?> register(String email) {
        var res = post("/api/v1/auth/register", null, Map.of("fullName", "Nguyen Van T", "email", email, "password", "password123"));
        assertEquals(201, res.getStatusCode().value(), String.valueOf(res.getBody()));
        return res.getBody();
    }

    private Map<?, ?> login(String email, String password) {
        return post("/api/v1/auth/login", null, Map.of("email", email, "password", password)).getBody();
    }

    private static String access(Map<?, ?> auth) {
        return (String) auth.get("accessToken");
    }

    private static Object code(ResponseEntity<Map> res) {
        return res.getBody() == null ? null : res.getBody().get("code");
    }


    @Test
    void registerIssuesSessionAndRejectsDuplicateEmail() {
        String email = email();
        Map<?, ?> auth = register(email);
        assertTrue(((String) auth.get("refreshToken")).matches("[A-Za-z0-9_-]{43}"));
        assertEquals(1209600, ((Number) auth.get("refreshExpiresIn")).intValue());
        assertEquals(900, ((Number) auth.get("expiresIn")).intValue());
        assertEquals("CUSTOMER", ((Map<?, ?>) auth.get("user")).get("role"));
        assertNull(((Map<?, ?>) auth.get("user")).get("organizer"));

        var dup = post("/api/v1/auth/register", null, Map.of("fullName", "X", "email", email.toUpperCase(), "password", "password123"));
        assertEquals(409, dup.getStatusCode().value());
        assertEquals("EMAIL_ALREADY_USED", code(dup));

        var bad = post("/api/v1/auth/login", null, Map.of("email", email, "password", "wrong-password"));
        assertEquals(401, bad.getStatusCode().value());
        assertEquals("BAD_CREDENTIALS", code(bad));
    }

    @Test
    void refreshRotatesAndReuseRevokesWholeFamily() {
        String r1 = (String) register(email()).get("refreshToken");

        var first = post("/api/v1/auth/refresh", null, Map.of("refreshToken", r1));
        assertEquals(200, first.getStatusCode().value());
        String r2 = (String) first.getBody().get("refreshToken");
        assertNotEquals(r1, r2);
        assertEquals(200, call(HttpMethod.GET, "/api/v1/auth/me", access(first.getBody()), null).getStatusCode().value());

        var reuse = post("/api/v1/auth/refresh", null, Map.of("refreshToken", r1));
        assertEquals(401, reuse.getStatusCode().value());
        assertEquals("REFRESH_TOKEN_INVALID", code(reuse));
        assertEquals("REFRESH_TOKEN_INVALID", code(post("/api/v1/auth/refresh", null, Map.of("refreshToken", r2))));
        assertEquals("REFRESH_TOKEN_INVALID", code(post("/api/v1/auth/refresh", null, Map.of("refreshToken", "garbage"))));
    }

    @Test
    void logoutRevokesAndAlwaysReturns204() {
        String r = (String) register(email()).get("refreshToken");
        assertEquals(204, post("/api/v1/auth/logout", null, Map.of("refreshToken", r)).getStatusCode().value());
        assertEquals(401, post("/api/v1/auth/refresh", null, Map.of("refreshToken", r)).getStatusCode().value());
        assertEquals(204, post("/api/v1/auth/logout", null, Map.of("refreshToken", "unknown")).getStatusCode().value());
        assertEquals(204, post("/api/v1/auth/logout", null, Map.of("refreshToken", r)).getStatusCode().value());
    }

    @Test
    void registerOrganizerCreatesProfileWithUniqueSlug() {
        String name = "Đêm Nhạc " + UUID.randomUUID().toString().substring(0, 6);
        List<String> slugs = new java.util.ArrayList<>();
        for (int i = 0; i < 2; i++) {
            var res = post("/api/v1/auth/register-organizer", null, Map.of("fullName", "Tran B", "email", email(),
                    "password", "password123", "organizerName", name, "city", "Hà Nội", "website", "https://example.com"));
            assertEquals(201, res.getStatusCode().value(), String.valueOf(res.getBody()));
            Map<?, ?> user = (Map<?, ?>) res.getBody().get("user");
            assertEquals("ORGANIZER", user.get("role"));
            slugs.add((String) ((Map<?, ?>) user.get("organizer")).get("slug"));
            assertNotNull(res.getBody().get("refreshToken"));
        }
        String base = "dem-nhac-" + name.substring(9).toLowerCase();
        assertEquals(List.of(base, base + "-2"), slugs);

        var pub = call(HttpMethod.GET, "/api/v1/organizers/" + slugs.get(1), null, null);
        assertEquals(200, pub.getStatusCode().value());
        assertEquals("Hà Nội", pub.getBody().get("city"));
        assertEquals(0, ((Number) pub.getBody().get("eventsCount")).intValue());
        assertEquals(slugs.get(1), call(HttpMethod.GET, "/api/v1/organizers/" + pub.getBody().get("id"), null, null).getBody().get("slug"));

        var bad = post("/api/v1/auth/register-organizer", null, Map.of("fullName", "Tran B", "email", email(),
                "password", "password123", "organizerName", "X", "website", "ftp://x"));
        assertEquals(400, bad.getStatusCode().value());
        assertEquals("VALIDATION", code(bad));
    }

    @Test
    void organizerSetsOnePayoutAccount() {
        var registered = post("/api/v1/auth/register-organizer", null, Map.of(
                "fullName", "Organizer Bank Test", "email", email(), "password", "password123",
                "organizerName", "Bank Account Test " + UUID.randomUUID().toString().substring(0, 6)));
        assertEquals(201, registered.getStatusCode().value(), String.valueOf(registered.getBody()));
        String token = access(registered.getBody());
        Object organizerId = ((Map<?, ?>) ((Map<?, ?>) registered.getBody().get("user")).get("organizer")).get("id");

        var empty = call(HttpMethod.GET, "/api/v1/organizer/payout-account", token, null);
        assertEquals(200, empty.getStatusCode().value());
        assertNull(empty.getBody().get("bankBin"));
        assertEquals(false, empty.getBody().get("payoutSynced"), "Chưa khai tài khoản thì chưa có gì để đồng bộ");

        var missingBin = call(HttpMethod.PUT, "/api/v1/organizer/payout-account", token, Map.of(
                "accountName", "NGUYEN VAN A", "accountNumber", "1234567890"));
        assertEquals(400, missingBin.getStatusCode().value());
        assertEquals("BANK_BIN_REQUIRED", code(missingBin));

        var unknownBin = call(HttpMethod.PUT, "/api/v1/organizer/payout-account", token, Map.of(
                "bankBin", "999999", "accountName", "NGUYEN VAN A", "accountNumber", "1234567890"));
        assertEquals(400, unknownBin.getStatusCode().value(), String.valueOf(unknownBin.getBody()));
        assertEquals("UNKNOWN_BANK_BIN", code(unknownBin), "Đúng 6 chữ số nhưng không có trong danh mục thì phải trượt");

        var shortAccount = call(HttpMethod.PUT, "/api/v1/organizer/payout-account", token, Map.of(
                "bankBin", "970436", "accountName", "NGUYEN VAN A", "accountNumber", "1234 5"));
        assertEquals(400, shortAccount.getStatusCode().value(), String.valueOf(shortAccount.getBody()));
        assertEquals("INVALID_ACCOUNT_NUMBER", code(shortAccount));

        var first = call(HttpMethod.PUT, "/api/v1/organizer/payout-account", token, Map.of(
                "bankBin", "970436", "accountName", "NGUYEN VAN A", "accountNumber", "1234567890"));
        assertEquals(200, first.getStatusCode().value(), String.valueOf(first.getBody()));
        assertEquals("Vietcombank", first.getBody().get("bankName"), "Tên ngân hàng lấy từ danh mục theo BIN");
        assertEquals("******7890", first.getBody().get("maskedAccountNumber"));
        assertNull(first.getBody().get("accountNumber"), "API không trả số tài khoản đầy đủ");

        var changed = call(HttpMethod.PUT, "/api/v1/organizer/payout-account", token, Map.of(
                "bankBin", "970407", "accountName", "NGUYEN VAN A", "accountNumber", "9876543210"));
        assertEquals(200, changed.getStatusCode().value(), String.valueOf(changed.getBody()));
        assertEquals("Techcombank", changed.getBody().get("bankName"));
        assertEquals(1, jdbc.queryForObject("select count(*) from organizer_bank_accounts where organizer_id = ?::uuid",
                Integer.class, organizerId.toString()), "Đổi tài khoản là sửa, không chồng thêm dòng");
    }

    @Test
    void publicOrganizerCountsPublishedEvents() {
        var res = call(HttpMethod.GET, "/api/v1/organizers/sunrise-live", null, null);
        assertEquals(200, res.getStatusCode().value());
        assertTrue(((Number) res.getBody().get("eventsCount")).intValue() >= 1);
        assertEquals("ORGANIZER_NOT_FOUND", code(call(HttpMethod.GET, "/api/v1/organizers/khong-ton-tai", null, null)));
        assertEquals(404, call(HttpMethod.GET, "/api/v1/organizers/" + UUID.randomUUID(), null, null).getStatusCode().value());
    }

    private void insertEvent(Object organizerId, String status, int startDays) {
        jdbc.update("""
                insert into events (id, slug, name, status, starts_at, ends_at, organizer_id)
                values (gen_random_uuid(), ?, 'Sự kiện test', ?, now() + make_interval(days => ?),
                        now() + make_interval(days => ?, hours => 3), ?::uuid)""",
                "ev-" + UUID.randomUUID(), status, startDays, startDays, organizerId.toString());
    }

    private Object registerOrganizerId(String name) {
        var res = post("/api/v1/auth/register-organizer", null, Map.of("fullName", "Tran C", "email", email(),
                "password", "password123", "organizerName", name));
        assertEquals(201, res.getStatusCode().value(), String.valueOf(res.getBody()));
        return ((Map<?, ?>) ((Map<?, ?>) res.getBody().get("user")).get("organizer")).get("id");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> organizers(String query) {
        var res = http().get().uri("/api/v1/organizers" + query).retrieve().toEntity(List.class);
        assertEquals(200, res.getStatusCode().value());
        return res.getBody();
    }

    @Test
    void featuredOrganizersOrderedByVerifiedThenUpcomingEvents() {
        String suffix = UUID.randomUUID().toString().substring(0, 6);
        Object onlyPast = registerOrganizerId("BTC Đã Qua " + suffix);
        insertEvent(onlyPast, "PUBLISHED", -3);
        insertEvent(onlyPast, "DRAFT", 5);
        Object busy = registerOrganizerId("BTC Bận Rộn " + suffix);
        for (int d = 1; d <= 3; d++) insertEvent(busy, "PUBLISHED", d * 10);
        insertEvent(busy, "UPCOMING", -10);

        List<Map<String, Object>> list = organizers("?size=24");
        List<Object> ids = list.stream().map(o -> o.get("id")).toList();
        assertFalse(ids.contains(onlyPast.toString()));
        Map<String, Object> y = list.stream().filter(o -> o.get("id").equals(busy.toString())).findFirst().orElseThrow();
        assertEquals(4, ((Number) y.get("eventsCount")).intValue());
        assertEquals(y.get("eventsCount"), call(HttpMethod.GET, "/api/v1/organizers/" + y.get("slug"), null, null).getBody().get("eventsCount"));

        Map<String, Long> upcoming = new java.util.HashMap<>();
        jdbc.query("""
                select organizer_id::text, count(*) from events
                 where status in ('PUBLISHED', 'UPCOMING') and coalesce(ends_at, starts_at) >= now() and organizer_id is not null
                 group by organizer_id""", rs -> { upcoming.put(rs.getString(1), rs.getLong(2)); });
        assertEquals(upcoming.keySet(), new java.util.HashSet<>(ids), "đủ mọi BTC còn sự kiện, không thừa BTC nào");
        for (int i = 1; i < list.size(); i++) {
            Map<String, Object> prev = list.get(i - 1), cur = list.get(i);
            boolean pv = (Boolean) prev.get("verified"), cv = (Boolean) cur.get("verified");
            assertTrue(pv || !cv, "BTC đã xác minh phải đứng trước");
            if (pv == cv) assertTrue(upcoming.get((String) prev.get("id")) >= upcoming.get((String) cur.get("id")));
            assertTrue(((Number) cur.get("eventsCount")).longValue() >= upcoming.get((String) cur.get("id")));
        }

        assertEquals(2, organizers("?size=2").size());
        assertEquals(1, organizers("?size=0").size());
        assertTrue(organizers("").size() <= 12);
        assertEquals(organizers("?size=2"), organizers("?size=5").subList(0, 2));
    }

    @Test
    void becomeOrganizerUpgradesRoleAndIssuesFreshSession() {
        String oldToken = access(register(email()));
        assertEquals(403, call(HttpMethod.GET, "/api/v1/organizer/profile", oldToken, null).getStatusCode().value());

        var res = post("/api/v1/me/organizer", oldToken, Map.of("name", "Nhà Hát Nhỏ " + UUID.randomUUID(), "contactEmail", "lh@example.com"));
        assertEquals(201, res.getStatusCode().value(), String.valueOf(res.getBody()));
        assertEquals("ORGANIZER", ((Map<?, ?>) res.getBody().get("user")).get("role"));
        String newToken = access(res.getBody());

        var profile = call(HttpMethod.GET, "/api/v1/organizer/profile", newToken, null);
        assertEquals(200, profile.getStatusCode().value());
        assertEquals("lh@example.com", profile.getBody().get("contactEmail"));

        var again = post("/api/v1/me/organizer", newToken, Map.of("name", "Khác"));
        assertEquals(409, again.getStatusCode().value());
        assertEquals("ALREADY_ORGANIZER", code(again));

        var put = call(HttpMethod.PUT, "/api/v1/organizer/profile", newToken, Map.of("name", "Tên Mới", "description", "Mô tả",
                "website", "https://nhahat.vn", "contactPhone", ""));
        assertEquals(200, put.getStatusCode().value(), String.valueOf(put.getBody()));
        assertEquals("Tên Mới", put.getBody().get("name"));
        assertEquals(profile.getBody().get("slug"), put.getBody().get("slug"));
        assertNull(put.getBody().get("contactEmail"));

        var invalid = call(HttpMethod.PUT, "/api/v1/organizer/profile", newToken, Map.of("name", "", "contactEmail", "not-an-email"));
        assertEquals(400, invalid.getStatusCode().value());
        assertEquals("VALIDATION", code(invalid));
    }

    @Test
    void adminWithoutOrganizerProfileGets404() {
        var res = call(HttpMethod.GET, "/api/v1/organizer/profile", access(login("admin@example.com", "password123")), null);
        assertEquals(404, res.getStatusCode().value());
        assertEquals("ORGANIZER_NOT_FOUND", code(res));

        var mine = call(HttpMethod.GET, "/api/v1/organizer/profile", access(login("organizer@example.com", "password123")), null);
        assertEquals("sunrise-live", mine.getBody().get("slug"));
    }

    @Test
    void passwordOver72BytesIsValidationError() {
        String longPw = "ệ".repeat(30);
        var reg = post("/api/v1/auth/register", null, Map.of("fullName", "Byte", "email", email(), "password", longPw));
        assertEquals(400, reg.getStatusCode().value(), String.valueOf(reg.getBody()));
        assertEquals("password", ((Map<?, ?>) ((List<?>) reg.getBody().get("errors")).get(0)).get("field"));

        var reset = post("/api/v1/auth/reset-password", null, Map.of("token", "x", "password", longPw));
        assertEquals("VALIDATION", code(reset));

        String token = access(register(email()));
        var change = call(HttpMethod.PUT, "/api/v1/users/me/password", token, Map.of("currentPassword", "password123", "newPassword", longPw));
        assertEquals(400, change.getStatusCode().value());
        assertEquals("newPassword", ((Map<?, ?>) ((List<?>) change.getBody().get("errors")).get(0)).get("field"));

        assertEquals(201, post("/api/v1/auth/register", null,
                Map.of("fullName", "Byte", "email", email(), "password", "ệ".repeat(24))).getStatusCode().value());
    }

    @Test
    void errorPathIsNotHiddenBehind401() {
        assertNotEquals(401, call(HttpMethod.GET, "/error", null, null).getStatusCode().value());
    }


    @Test
    void updateProfileAndChangePassword() {
        String email = email();
        String token = access(register(email));

        var put = call(HttpMethod.PUT, "/api/v1/users/me", token, Map.of("fullName", "Le Van C", "phone", "0912 345 678",
                "bio", "Xin chào", "avatarUrl", "/uploads/avatars/x.png"));
        assertEquals(200, put.getStatusCode().value(), String.valueOf(put.getBody()));
        assertEquals("0912 345 678", put.getBody().get("phone"));
        assertEquals("Le Van C", call(HttpMethod.GET, "/api/v1/users/me", token, null).getBody().get("fullName"));

        var badPhone = call(HttpMethod.PUT, "/api/v1/users/me", token, Map.of("fullName", "A", "phone", "abc"));
        assertEquals("VALIDATION", code(badPhone));

        var wrong = call(HttpMethod.PUT, "/api/v1/users/me/password", token, Map.of("currentPassword", "nope-nope", "newPassword", "newpass123"));
        assertEquals(400, wrong.getStatusCode().value());
        assertEquals("WRONG_PASSWORD", code(wrong));
        assertEquals("currentPassword", ((Map<?, ?>) ((List<?>) wrong.getBody().get("errors")).get(0)).get("field"));

        var ok = call(HttpMethod.PUT, "/api/v1/users/me/password", token, Map.of("currentPassword", "password123", "newPassword", "newpass123"));
        assertEquals(204, ok.getStatusCode().value());
        assertNotNull(access(login(email, "newpass123")));

        assertEquals(401, call(HttpMethod.GET, "/api/v1/users/me", null, null).getStatusCode().value());
    }


    @Test
    void forgotAndResetPassword() {
        String email = email();
        String refresh = (String) register(email).get("refreshToken");

        var unknown = post("/api/v1/auth/forgot-password", null, Map.of("email", email()));
        assertEquals(202, unknown.getStatusCode().value());
        assertEquals(Boolean.TRUE, unknown.getBody().get("sent"));
        assertEquals(30, ((Number) unknown.getBody().get("expiresInMinutes")).intValue());
        assertFalse(unknown.getBody().containsKey("devResetUrl"));

        String oldUrl = (String) post("/api/v1/auth/forgot-password", null, Map.of("email", email)).getBody().get("devResetUrl");
        var known = post("/api/v1/auth/forgot-password", null, Map.of("email", email));
        String url = (String) known.getBody().get("devResetUrl");
        assertTrue(url.startsWith("http://localhost:3000/auth/reset-password?token="), url);
        String token = url.substring(url.indexOf("token=") + 6);

        var old = post("/api/v1/auth/reset-password", null, Map.of("token", oldUrl.substring(oldUrl.indexOf("token=") + 6), "password", "reset12345"));
        assertEquals("TOKEN_INVALID", code(old));

        var ok = post("/api/v1/auth/reset-password", null, Map.of("token", token, "password", "reset12345"));
        assertEquals(200, ok.getStatusCode().value());
        assertEquals(Boolean.TRUE, ok.getBody().get("updated"));
        assertNotNull(access(login(email, "reset12345")));
        assertEquals("REFRESH_TOKEN_INVALID", code(post("/api/v1/auth/refresh", null, Map.of("refreshToken", refresh))));

        var reused = post("/api/v1/auth/reset-password", null, Map.of("token", token, "password", "reset12345"));
        assertEquals(400, reused.getStatusCode().value());
        assertEquals("TOKEN_INVALID", code(reused));

        String next = (String) post("/api/v1/auth/forgot-password", null, Map.of("email", email)).getBody().get("devResetUrl");
        jdbc.update("update password_reset_tokens set expires_at = now() - interval '1 minute' where used_at is null "
                + "and user_id = (select id from users where email = ?)", email);
        var expired = post("/api/v1/auth/reset-password", null, Map.of("token", next.substring(next.indexOf("token=") + 6), "password", "reset12345"));
        assertEquals(410, expired.getStatusCode().value());
        assertEquals("TOKEN_EXPIRED", code(expired));
    }


    @SuppressWarnings("unchecked")
    private ResponseEntity<Map> upload(String token, byte[] data, String folder) {
        var form = new LinkedMultiValueMap<String, Object>();
        form.add("file", new ByteArrayResource(data) {
            @Override
            public String getFilename() { return "photo.png"; }
        });
        if (folder != null) form.add("folder", folder);
        return http().post().uri("/api/v1/uploads/images").headers(h -> { if (token != null) h.setBearerAuth(token); })
                .contentType(MediaType.MULTIPART_FORM_DATA).body(form).retrieve().toEntity(Map.class);
    }

    @Test
    void uploadImageStoresLocallyAndServesIt() {
        String token = access(register(email()));
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 1, 2, 3};

        var res = upload(token, png, "avatars");
        assertEquals(201, res.getStatusCode().value(), String.valueOf(res.getBody()));
        String url = (String) res.getBody().get("url");
        assertTrue(url.matches("/uploads/avatars/[0-9a-f-]{36}\\.png"), url);
        assertEquals("image/png", res.getBody().get("contentType"));
        assertEquals(11, ((Number) res.getBody().get("bytes")).intValue());
        assertArrayEquals(png, http().get().uri(url).retrieve().body(byte[].class));

        assertTrue(((String) upload(token, png, null).getBody().get("url")).startsWith("/uploads/misc/"));
        assertEquals("UNSUPPORTED_FILE_TYPE", code(upload(token, "<svg></svg>".getBytes(StandardCharsets.UTF_8), "events")));
        assertEquals("VALIDATION", code(upload(token, png, "../etc")));
        assertEquals(401, upload(null, png, "avatars").getStatusCode().value());

        var tooLarge = upload(token, new byte[6 * 1024 * 1024], "avatars");
        assertEquals(413, tooLarge.getStatusCode().value());
        assertEquals("FILE_TOO_LARGE", code(tooLarge));
    }


    @Test
    void contactMessageIsStoredWithUserWhenLoggedIn() {
        var anon = post("/api/v1/contact", null, Map.of("name", "Khách", "email", "k@example.com", "message", "Cho mình hỏi về vé nhé"));
        assertEquals(201, anon.getStatusCode().value(), String.valueOf(anon.getBody()));
        assertNotNull(anon.getBody().get("createdAt"));

        String token = access(login("a@example.com", "password123"));
        var mine = post("/api/v1/contact", token, Map.of("name", "A", "email", "a@example.com", "subject", "Hỏi", "message", "Nội dung đủ dài"));
        assertEquals(201, mine.getStatusCode().value());
        assertEquals(1, jdbc.queryForObject("select count(*) from contact_messages m join users u on u.id = m.user_id "
                + "where m.id = ?::uuid and u.email = 'a@example.com'", Integer.class, mine.getBody().get("id")));

        var bad = post("/api/v1/contact", null, Map.of("name", "", "email", "x", "message", "ngắn"));
        assertEquals(400, bad.getStatusCode().value());
        assertEquals("VALIDATION", code(bad));
        assertEquals(3, ((List<?>) bad.getBody().get("errors")).size());
    }
}
