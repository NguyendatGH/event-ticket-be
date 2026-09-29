package com.example.demo.commerce;

import com.example.demo.domain.event.Event;
import com.example.demo.domain.event.EventStatus;
import com.example.demo.domain.event.TicketTier;
import com.example.demo.domain.event.Venue;
import com.example.demo.domain.inventory.Inventory;
import com.example.demo.infrastructure.persistence.EventRepository;
import com.example.demo.infrastructure.persistence.InventoryRepository;
import com.example.demo.infrastructure.persistence.TicketTierRepository;
import com.example.demo.infrastructure.scheduling.OrderExpiryJob;
import com.example.demo.support.MockPaymentGateway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Một Postgres thật trong Docker: chứng minh Flyway V1 + Hibernate validate khớp nhau, luồng checkout
 * (giữ vé có khóa, idempotency, hủy, hết hạn) và luồng thanh toán qua mock gateway (webhook ký HMAC,
 * PAID đúng một lần, trùng, sai chữ ký, thiếu tiền, tiền vào muộn) đúng end-to-end qua HTTP.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.jwt.secret=test-secret-test-secret-test-secret-1234",
        "DB_URL=unused", "DB_USERNAME=unused", "DB_PASSWORD=unused"
})
@ActiveProfiles("test")
class CheckoutFlowTests {

    @TestConfiguration(proxyBeanMethods = false)
    static class Containers {
        @Bean
        @ServiceConnection
        PostgreSQLContainer postgres() {
            return new PostgreSQLContainer("postgres:16-alpine");
        }
    }

    @LocalServerPort int port;
    @Autowired EventRepository events;
    @Autowired TicketTierRepository tiers;
    @Autowired InventoryRepository inventory;
    @Autowired OrderExpiryJob expiryJob;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockPaymentGateway mockGateway;

    /* ---------- helpers (RestClient không ném lỗi ở 4xx để đọc body problem+json) ---------- */

    /** Đơn hàng giờ bắt buộc đăng nhập, nên mọi request mặc định mang token của người mua. */
    private RestClient http() {
        return RestClient.builder().baseUrl("http://localhost:" + port)
                .defaultStatusHandler(status -> true, (req, res) -> { })
                .defaultHeaders(h -> h.setBearerAuth(token())).build();
    }

    /** Không token: dùng để kiểm endpoint có thật sự chặn khách chưa đăng nhập. */
    private RestClient anon() {
        return RestClient.builder().baseUrl("http://localhost:" + port)
                .defaultStatusHandler(status -> true, (req, res) -> { }).build();
    }

    private String token;

    private String token() {
        if (token == null) token = register();
        return token;
    }

    /** Đăng ký một người mua mới và trả accessToken. Email ngẫu nhiên để các test không giẫm nhau. */
    @SuppressWarnings("unchecked")
    private String register() {
        Map<?, ?> auth = anon().post().uri("/api/v1/auth/register")
                .body(Map.of("fullName", "Nguyen Van A", "email", "buyer-" + UUID.randomUUID() + "@example.com",
                        "password", "password123"))
                .retrieve().toEntity(Map.class).getBody();
        return (String) auth.get("accessToken");
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<Map> get(String path, Object... vars) {
        return http().get().uri(path, vars).retrieve().toEntity(Map.class);
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<Map> post(String path) {
        return http().post().uri(path).retrieve().toEntity(Map.class);
    }

    private Event event(EventStatus status, String city) {
        return events.save(Event.builder().slug("ev-" + UUID.randomUUID()).name("Test " + city).category("music")
                .startsAt(Instant.now().plusSeconds(86_400)).status(status).venue(new Venue("Nơi", city, "Địa chỉ"))
                .description(List.of("mô tả")).schedule(List.of()).featured(false).build());
    }

    private TicketTier tier(Event e, int available, int maxPerOrder) {
        return tier(e, available, maxPerOrder, 500_000);
    }

    private TicketTier tier(Event e, int available, int maxPerOrder, long price) {
        TicketTier t = tiers.save(new TicketTier(e.getId(), "GA", "", price, available, maxPerOrder));
        inventory.save(new Inventory(t.getId(), available));
        return t;
    }

    private int available(TicketTier t) {
        return inventory.findById(t.getId()).orElseThrow().getAvailable();
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<Map> createOrder(String key, UUID eventId, UUID tierId, int qty) {
        Map<String, Object> body = Map.of("eventId", eventId,
                "items", List.of(Map.of("tierId", tierId, "quantity", qty)),
                "customer", Map.of("name", "Nguyen Van A", "email", "a@example.com"),
                "paymentMethod", "PAYOS");
        return http().post().uri("/api/v1/orders")
                .headers(h -> { if (key != null) h.set("Idempotency-Key", key); })
                .body(body).retrieve().toEntity(Map.class);
    }

    private Map<?, ?> order(Object id) {
        return get("/api/v1/orders/" + id).getBody();
    }

    private Map<?, ?> payment(Map<?, ?> order) {
        return (Map<?, ?>) order.get("payment");
    }

    /** [signature_valid, processing_result] của các webhook thuộc payment của đơn, theo thứ tự nhận. */
    private List<String> webhooks(Map<?, ?> order) {
        return jdbc.query("select signature_valid, processing_result from webhook_events where event_id like ? order by received_at",
                (rs, i) -> rs.getBoolean(1) + ":" + rs.getString(2), payment(order).get("paymentLinkId") + "%");
    }

    /* ---------- tests: phải đăng nhập mới mua ---------- */

    @Test
    void guestCannotCreateOrder() {
        Event e = event(EventStatus.PUBLISHED, "Hà Nội");
        TicketTier t = tier(e, 10, 4);

        ResponseEntity<Map> res = anon().post().uri("/api/v1/orders")
                .headers(h -> h.set("Idempotency-Key", UUID.randomUUID().toString()))
                .body(Map.of("eventId", e.getId(), "items", List.of(Map.of("tierId", t.getId(), "quantity", 1)),
                        "customer", Map.of("name", "Khach La", "email", "la@example.com")))
                .retrieve().toEntity(Map.class);

        assertEquals(401, res.getStatusCode().value(), String.valueOf(res.getBody()));
        assertEquals(10, available(t), "không giữ vé cho người chưa đăng nhập");
    }

    /** Đơn của người khác: trả 404 chứ không 403, để người lạ dò orderId không biết đơn có tồn tại. */
    @Test
    void otherUserSeesNeitherOrderNorCancel() {
        Event e = event(EventStatus.PUBLISHED, "Hà Nội");
        TicketTier t = tier(e, 10, 4);
        Object orderId = createOrder(UUID.randomUUID().toString(), e.getId(), t.getId(), 1).getBody().get("id");
        String otherToken = register();

        ResponseEntity<Map> read = anon().get().uri("/api/v1/orders/" + orderId)
                .headers(h -> h.setBearerAuth(otherToken)).retrieve().toEntity(Map.class);
        ResponseEntity<Map> cancel = anon().post().uri("/api/v1/orders/" + orderId + "/cancel")
                .headers(h -> h.setBearerAuth(otherToken)).retrieve().toEntity(Map.class);

        assertEquals(404, read.getStatusCode().value(), String.valueOf(read.getBody()));
        assertEquals("ORDER_NOT_FOUND", read.getBody().get("code"));
        assertEquals(404, cancel.getStatusCode().value(), String.valueOf(cancel.getBody()));
        assertEquals("PENDING_PAYMENT", order(orderId).get("status"), "đơn của chủ thật không bị ai hủy");
    }

    /* ---------- tests: checkout ---------- */

    @Test
    void checkoutReservesInventoryAndReturnsContractShape() {
        Event e = event(EventStatus.PUBLISHED, "Hà Nội");
        TicketTier t = tier(e, 10, 4);

        ResponseEntity<Map> res = createOrder(UUID.randomUUID().toString(), e.getId(), t.getId(), 2);

        assertEquals(201, res.getStatusCode().value(), String.valueOf(res.getBody()));
        Map<?, ?> o = res.getBody();
        assertEquals("PENDING_PAYMENT", o.get("status"));
        assertEquals(e.getSlug(), o.get("eventSlug"));
        assertEquals(1_000_000, ((Number) o.get("subtotalAmount")).longValue());
        assertEquals(12_000, ((Number) o.get("feeAmount")).longValue());
        assertEquals(1_012_000, ((Number) o.get("totalAmount")).longValue());
        Map<?, ?> payment = payment(o);
        assertEquals("MOCK", payment.get("provider"));
        assertEquals("PENDING", payment.get("status"));
        assertEquals("http://localhost:3000/mock-gateway/checkout/" + o.get("id"), payment.get("checkoutUrl"));
        assertEquals(List.of(), o.get("tickets"));
        assertEquals("GA", ((Map<?, ?>) ((List<?>) o.get("items")).get(0)).get("tierName"));
        assertNotNull(o.get("expiresAt"));
        assertEquals(8, available(t));

        // GET trả cùng đơn
        Map<?, ?> fetched = get("/api/v1/orders/" + o.get("id")).getBody();
        assertEquals(o.get("orderCode"), fetched.get("orderCode"));
    }

    @Test
    void sameIdempotencyKeyReplaysSameOrderAndDifferentBodyIsRejected() {
        Event e = event(EventStatus.PUBLISHED, "Hà Nội");
        TicketTier t = tier(e, 10, 4);
        String key = UUID.randomUUID().toString();

        ResponseEntity<Map> first = createOrder(key, e.getId(), t.getId(), 1);
        ResponseEntity<Map> replay = createOrder(key, e.getId(), t.getId(), 1);
        ResponseEntity<Map> changed = createOrder(key, e.getId(), t.getId(), 2);

        assertEquals(201, first.getStatusCode().value());
        assertEquals(201, replay.getStatusCode().value());
        assertEquals(first.getBody().get("id"), replay.getBody().get("id"));
        assertEquals(payment(first.getBody()).get("checkoutUrl"), payment(replay.getBody()).get("checkoutUrl"), "replay trả cùng link");
        assertEquals(9, available(t), "replay không trừ kho lần hai");
        assertEquals(422, changed.getStatusCode().value());
        assertEquals("IDEMPOTENCY_KEY_REUSED", changed.getBody().get("code"));

        assertEquals(400, createOrder(null, e.getId(), t.getId(), 1).getStatusCode().value(), "thiếu header");
    }

    @Test
    void concurrentCheckoutsForLastTicketOversellNothing() throws Exception {
        Event e = event(EventStatus.PUBLISHED, "Hà Nội");
        TicketTier t = tier(e, 1, 4);

        List<ResponseEntity<Map>> results = Stream.of(1, 2)
                .map(i -> CompletableFuture.supplyAsync(() -> createOrder(UUID.randomUUID().toString(), e.getId(), t.getId(), 1)))
                .toList().stream().map(CompletableFuture::join).toList();

        List<Integer> statuses = results.stream().map(r -> r.getStatusCode().value()).sorted().toList();
        assertEquals(List.of(201, 409), statuses);
        Map<?, ?> rejected = results.stream().filter(r -> r.getStatusCode().value() == 409).findFirst().orElseThrow().getBody();
        assertEquals("TIER_SOLD_OUT", rejected.get("code"));
        assertEquals(0, available(t));
    }

    @Test
    void validationErrorsUseDomainCodes() {
        Event upcoming = event(EventStatus.UPCOMING, "Đà Nẵng");
        TicketTier t = tier(upcoming, 5, 2);
        assertEquals("EVENT_NOT_ON_SALE", createOrder(UUID.randomUUID().toString(), upcoming.getId(), t.getId(), 1).getBody().get("code"));

        Event e = event(EventStatus.PUBLISHED, "Đà Nẵng");
        TicketTier t2 = tier(e, 5, 2);
        assertEquals("QUANTITY_EXCEEDED", createOrder(UUID.randomUUID().toString(), e.getId(), t2.getId(), 3).getBody().get("code"));
        assertEquals("QUANTITY_INVALID", createOrder(UUID.randomUUID().toString(), e.getId(), t2.getId(), 0).getBody().get("code"));
        assertEquals("TIER_NOT_FOUND", createOrder(UUID.randomUUID().toString(), e.getId(), UUID.randomUUID(), 1).getBody().get("code"));
        assertEquals(404, get("/api/v1/orders/" + UUID.randomUUID()).getStatusCode().value());
    }

    @Test
    void cancelReleasesInventoryOnce() {
        Event e = event(EventStatus.PUBLISHED, "Hà Nội");
        TicketTier t = tier(e, 3, 4);
        String id = (String) createOrder(UUID.randomUUID().toString(), e.getId(), t.getId(), 2).getBody().get("id");
        assertEquals(1, available(t));

        ResponseEntity<Map> cancelled = post("/api/v1/orders/" + id + "/cancel");
        assertEquals("CANCELLED", cancelled.getBody().get("status"));
        assertEquals(3, available(t));

        ResponseEntity<Map> again = post("/api/v1/orders/" + id + "/cancel");
        assertEquals(409, again.getStatusCode().value());
        assertEquals("ORDER_NOT_CANCELLABLE", again.getBody().get("code"));
        assertEquals(3, available(t), "hủy lần hai không cộng kho thêm");
    }

    /**
     * Ca mất tiền thật gặp ở dev 2026-09-29: khách chuyển tiền xong, webhook KHÔNG về (webhook-url chưa
     * đăng ký với PayOS), trang return hết 90s nên khách bấm "Hủy đơn" -> đơn CANCELLED, trả kho, 0 vé,
     * tiền đã vào tài khoản mà không chỗ nào ghi nhận. Hủy phải hỏi cổng trước khi hủy.
     */
    @Test
    void cancelKhongHuyDonKhachDaTraDuTienDuWebhookChuaVe() {
        Event e = event(EventStatus.PUBLISHED, "Hà Nội");
        TicketTier t = tier(e, 3, 4);
        String id = (String) createOrder(UUID.randomUUID().toString(), e.getId(), t.getId(), 2).getBody().get("id");
        assertEquals(1, available(t));

        // Tiền vào ở "provider" nhưng không gửi webhook nào — đúng hiện trường lúc webhook rớt.
        long total = ((Number) order(id).get("totalAmount")).longValue();
        String providerPaymentId = jdbc.queryForObject(
                "select provider_payment_id from payments where order_id = ?::uuid", String.class, id);
        mockGateway.markPaid(providerPaymentId, total, "FT-CANCEL-GUARD", Instant.now());

        ResponseEntity<Map> refused = post("/api/v1/orders/" + id + "/cancel");

        assertEquals(409, refused.getStatusCode().value(), String.valueOf(refused.getBody()));
        assertEquals("ORDER_ALREADY_PAID", refused.getBody().get("code"));
        assertEquals("PAID", order(id).get("status"), "đơn được chốt PAID thay vì bị hủy");
        assertEquals(2, ((List<?>) order(id).get("tickets")).size(), "vé được cấp cho khoản tiền đã nhận");
        assertEquals(1, available(t), "kho KHÔNG được trả lại vì đơn vẫn sống");
    }

    @Test
    void expiryJobExpiresOverdueOrdersAndReleasesInventory() {
        Event e = event(EventStatus.PUBLISHED, "Hà Nội");
        TicketTier t = tier(e, 3, 4);
        String id = (String) createOrder(UUID.randomUUID().toString(), e.getId(), t.getId(), 3).getBody().get("id");
        assertEquals(0, available(t));

        jdbc.update("update orders set expires_at = now() - interval '1 minute' where id = ?::uuid", id);
        expiryJob.run();

        assertEquals("EXPIRED", get("/api/v1/orders/" + id).getBody().get("status"));
        assertEquals(3, available(t));
        assertEquals("EXPIRED", payment(order(id)).get("status"));
    }

    /* ---------- tests: thanh toán qua mock gateway ---------- */

    @Test
    void mockSucceedPaysOnceIssuesTicketsAndIgnoresRepeat() {
        Event e = event(EventStatus.PUBLISHED, "Hà Nội");
        TicketTier t = tier(e, 5, 4);
        String id = (String) createOrder(UUID.randomUUID().toString(), e.getId(), t.getId(), 2).getBody().get("id");

        Map<?, ?> paid = post("/mock-gateway/payments/" + id + "/succeed").getBody();
        assertEquals("PAID", paid.get("status"), String.valueOf(paid));
        assertEquals("PAID", payment(paid).get("status"));
        assertNotNull(payment(paid).get("transactionRef"));
        assertEquals(2, ((List<?>) paid.get("tickets")).size());
        assertEquals("ACTIVE", ((Map<?, ?>) ((List<?>) paid.get("tickets")).get(0)).get("status"));
        assertEquals(3, available(t), "kho đã trừ lúc checkout, thanh toán không trừ thêm");

        Map<?, ?> again = post("/mock-gateway/payments/" + id + "/succeed").getBody();
        assertEquals("PAID", again.get("status"));
        assertEquals(2, ((List<?>) again.get("tickets")).size(), "webhook thứ hai không cấp thêm vé");
        assertEquals(List.of("true:PROCESSED", "true:IGNORED"), webhooks(again));
    }

    @Test
    void duplicateWebhookIsStoredOnceAndChangesNothing() {
        Event e = event(EventStatus.PUBLISHED, "Hà Nội");
        TicketTier t = tier(e, 5, 4);
        String id = (String) createOrder(UUID.randomUUID().toString(), e.getId(), t.getId(), 3).getBody().get("id");

        Map<?, ?> paid = post("/mock-gateway/payments/" + id + "/succeed?duplicate=true").getBody();

        assertEquals("PAID", paid.get("status"));
        assertEquals(3, ((List<?>) paid.get("tickets")).size());
        assertEquals(List.of("true:PROCESSED"), webhooks(paid), "bản trùng bị unique (provider, event_id) chặn, không có dòng thứ hai");
    }

    @Test
    void badSignatureIsRejectedAndExpiryJobLaterFindsThePaymentByPolling() {
        Event e = event(EventStatus.PUBLISHED, "Hà Nội");
        TicketTier t = tier(e, 5, 4);
        String id = (String) createOrder(UUID.randomUUID().toString(), e.getId(), t.getId(), 1).getBody().get("id");

        Map<?, ?> still = post("/mock-gateway/payments/" + id + "/succeed?badSignature=true").getBody();
        assertEquals("PENDING_PAYMENT", still.get("status"));
        assertEquals(List.of(), still.get("tickets"));
        assertEquals(1, jdbc.queryForObject("select count(*) from webhook_events where signature_valid = false and processing_result = 'REJECTED_SIGNATURE'", Integer.class));

        // provider đã ghi nhận tiền nhưng webhook rớt: job hết hạn hỏi provider trước khi hủy đơn
        jdbc.update("update orders set expires_at = now() - interval '1 minute' where id = ?::uuid", id);
        expiryJob.run();
        Map<?, ?> recovered = order(id);
        assertEquals("PAID", recovered.get("status"), "poll thấy PAID thì cấp vé thay vì hết hạn");
        assertEquals(1, ((List<?>) recovered.get("tickets")).size());
        assertEquals(4, available(t));
    }

    @Test
    void underpaidAndFailedKeepOrderPendingUntilRealPayment() {
        Event e = event(EventStatus.PUBLISHED, "Hà Nội");
        TicketTier t = tier(e, 5, 4);
        String id = (String) createOrder(UUID.randomUUID().toString(), e.getId(), t.getId(), 1).getBody().get("id");

        Map<?, ?> under = post("/mock-gateway/payments/" + id + "/succeed?amount=1").getBody();
        assertEquals("PENDING_PAYMENT", under.get("status"));
        assertEquals("UNDERPAID", payment(under).get("status"));

        Map<?, ?> failed = post("/mock-gateway/payments/" + id + "/fail").getBody();
        assertEquals("PENDING_PAYMENT", failed.get("status"));
        assertEquals("UNDERPAID", payment(failed).get("status"), "webhook thất bại không xóa dấu vết tiền đã nhận");

        Map<?, ?> paid = post("/mock-gateway/payments/" + id + "/succeed").getBody();
        assertEquals("PAID", paid.get("status"));
        assertEquals(1, ((List<?>) paid.get("tickets")).size());
    }

    @Test
    void moneyArrivingAfterExpiryGoesToManualReviewWithoutTickets() {
        Event e = event(EventStatus.PUBLISHED, "Hà Nội");
        TicketTier t = tier(e, 2, 4);
        String id = (String) createOrder(UUID.randomUUID().toString(), e.getId(), t.getId(), 2).getBody().get("id");
        jdbc.update("update orders set expires_at = now() - interval '1 minute' where id = ?::uuid", id);
        expiryJob.run();
        assertEquals("EXPIRED", order(id).get("status"));
        assertEquals(2, available(t));

        Map<?, ?> late = post("/mock-gateway/payments/" + id + "/succeed").getBody();

        assertEquals("MANUAL_REVIEW", late.get("status"));
        assertEquals("PAID_LATE", payment(late).get("status"));
        assertEquals(List.of(), late.get("tickets"));
        assertEquals(2, available(t), "kho đã trả, không giữ lại");

        Map<?, ?> failAfter = post("/mock-gateway/payments/" + id + "/fail").getBody();
        assertEquals("MANUAL_REVIEW", failAfter.get("status"));
        assertEquals("PAID_LATE", payment(failAfter).get("status"), "webhook thất bại không ghi đè PAID_LATE");
        assertNotNull(payment(failAfter).get("transactionRef"));
    }

    @Test
    void underpaidOrderThatExpiresGoesToManualReviewKeepingThePayment() {
        Event e = event(EventStatus.PUBLISHED, "Hà Nội");
        TicketTier t = tier(e, 3, 4);
        String id = (String) createOrder(UUID.randomUUID().toString(), e.getId(), t.getId(), 1).getBody().get("id");
        post("/mock-gateway/payments/" + id + "/succeed?amount=1");

        Map<?, ?> expired = post("/mock-gateway/payments/" + id + "/expire").getBody();

        assertEquals("MANUAL_REVIEW", expired.get("status"));
        assertEquals("UNDERPAID", payment(expired).get("status"));
        assertEquals(3, available(t), "kho vẫn trả như đơn hết hạn");
    }

    @Test
    void checkoutRejectsEventThatHasEnded() {
        Event ended = events.save(Event.builder().slug("ev-" + UUID.randomUUID()).name("Đã qua").category("music")
                .startsAt(Instant.now().minusSeconds(2 * 86_400)).endsAt(Instant.now().minusSeconds(86_400))
                .status(EventStatus.PUBLISHED).venue(new Venue("Nơi", "Hà Nội", "Địa chỉ"))
                .description(List.of("mô tả")).schedule(List.of()).featured(false).build());
        TicketTier t = tier(ended, 5, 4);

        ResponseEntity<Map> res = createOrder(UUID.randomUUID().toString(), ended.getId(), t.getId(), 1);

        assertEquals(409, res.getStatusCode().value());
        assertEquals("EVENT_NOT_ON_SALE", res.getBody().get("code"));
        assertEquals(5, available(t));
    }

    @Test
    void mockExpireClosesOrderAndLinkImmediately() {
        Event e = event(EventStatus.PUBLISHED, "Hà Nội");
        TicketTier t = tier(e, 3, 4);
        String id = (String) createOrder(UUID.randomUUID().toString(), e.getId(), t.getId(), 1).getBody().get("id");

        Map<?, ?> expired = post("/mock-gateway/payments/" + id + "/expire").getBody();

        assertEquals("EXPIRED", expired.get("status"));
        assertEquals("EXPIRED", payment(expired).get("status"));
        assertEquals(3, available(t));
    }

    @Test
    void gatewayFailureCancelsOrderReleasesInventoryAndLogsTheCall() {
        Event e = event(EventStatus.PUBLISHED, "Hà Nội");
        TicketTier t = tier(e, 3, 4, 13);   // 13 + phí 12000 = 12013 -> mock provider từ chối
        String key = UUID.randomUUID().toString();

        ResponseEntity<Map> res = createOrder(key, e.getId(), t.getId(), 1);

        assertEquals(502, res.getStatusCode().value(), String.valueOf(res.getBody()));
        assertEquals("PAYMENT_LINK_FAILED", res.getBody().get("code"));
        assertEquals(3, available(t), "kho được trả ngay");
        assertEquals("CANCELLED", jdbc.queryForObject("select status from orders where idempotency_key = ?", String.class, key));
        assertEquals(1, jdbc.queryForObject("select count(*) from gateway_call_logs where endpoint = 'createPaymentLink' and response_raw ->> 'error' is not null", Integer.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from idempotency_records where idem_key = ?", Integer.class, key), "không lưu response lỗi, client thử lại cùng key được");
    }

    @Test
    void adminAuditShowsWebhooksAndGatewayCallsForAdminOnly() {
        Event e = event(EventStatus.PUBLISHED, "Hà Nội");
        TicketTier t = tier(e, 5, 4);
        String id = (String) createOrder(UUID.randomUUID().toString(), e.getId(), t.getId(), 1).getBody().get("id");
        post("/mock-gateway/payments/" + id + "/succeed");

        String email = "admin-" + UUID.randomUUID() + "@example.com";
        http().post().uri("/api/v1/auth/register").body(Map.of("fullName", "Admin", "email", email, "password", "password123")).retrieve().toBodilessEntity();
        String customerToken = (String) http().post().uri("/api/v1/auth/login").body(Map.of("email", email, "password", "password123")).retrieve().body(Map.class).get("accessToken");
        assertEquals(403, http().get().uri("/api/v1/admin/orders/" + id + "/audit").header("Authorization", "Bearer " + customerToken).retrieve().toEntity(Map.class).getStatusCode().value());

        jdbc.update("update users set role = 'ADMIN' where email = ?", email);
        String adminToken = (String) http().post().uri("/api/v1/auth/login").body(Map.of("email", email, "password", "password123")).retrieve().body(Map.class).get("accessToken");
        ResponseEntity<Map> audit = http().get().uri("/api/v1/admin/orders/" + id + "/audit").header("Authorization", "Bearer " + adminToken).retrieve().toEntity(Map.class);

        assertEquals(200, audit.getStatusCode().value(), String.valueOf(audit.getBody()));
        assertEquals("PAID", ((Map<?, ?>) audit.getBody().get("order")).get("status"));
        assertEquals(1, ((List<?>) audit.getBody().get("webhookEvents")).size());
        List<?> calls = (List<?>) audit.getBody().get("gatewayCallLogs");
        assertTrue(calls.size() >= 2, "createPaymentLink OUTBOUND + webhook INBOUND: " + calls);
    }

    @Test
    void eventsApiListsFiltersAndShowsAvailability() {
        Event hn = event(EventStatus.PUBLISHED, "Hải Phòng");
        TicketTier t = tier(hn, 7, 4);
        Event draft = event(EventStatus.DRAFT, "Hải Phòng");
        tier(draft, 1, 1);

        Map<?, ?> page = get("/api/v1/events?city={city}&q={q}", "Hải Phòng", "test").getBody();
        List<?> content = (List<?>) page.get("content");
        assertEquals(1, content.size(), "DRAFT không được liệt kê: " + page);
        assertEquals(hn.getSlug(), ((Map<?, ?>) content.get(0)).get("slug"));
        assertEquals(1, ((Number) page.get("totalElements")).intValue());
        assertNull(((Map<?, ?>) content.get(0)).get("tiers"), "summary không có tiers");

        Map<?, ?> detail = get("/api/v1/events/" + hn.getSlug()).getBody();
        Map<?, ?> tierDto = (Map<?, ?>) ((List<?>) detail.get("tiers")).get(0);
        assertEquals(7, tierDto.get("available"));
        assertEquals(t.getId().toString(), tierDto.get("id"));
        assertEquals("Hải Phòng", ((Map<?, ?>) detail.get("venue")).get("city"));
        assertEquals(List.of("mô tả"), detail.get("description"));

        assertEquals(404, get("/api/v1/events/khong-ton-tai").getStatusCode().value());
    }
}
