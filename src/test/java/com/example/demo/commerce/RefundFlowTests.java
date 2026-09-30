package com.example.demo.commerce;

import com.example.demo.application.RefundService;
import com.example.demo.application.WalletService;
import com.example.demo.application.dto.RefundInstruction;
import com.example.demo.application.dto.ResolveRefundRequest;
import com.example.demo.domain.common.DomainException;
import com.example.demo.domain.event.Event;
import com.example.demo.domain.event.EventStatus;
import com.example.demo.domain.event.TicketTier;
import com.example.demo.domain.event.Venue;
import com.example.demo.domain.inventory.Inventory;
import com.example.demo.domain.refund.RefundStatus;
import com.example.demo.infrastructure.mail.CustomerRefundMailInfo;
import com.example.demo.infrastructure.mail.Mailer;
import com.example.demo.infrastructure.mail.RefundMailInfo;
import com.example.demo.infrastructure.persistence.EventRepository;
import com.example.demo.infrastructure.persistence.InventoryRepository;
import com.example.demo.infrastructure.persistence.RefundRepository;
import com.example.demo.infrastructure.persistence.TicketTierRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.RestClient;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Refund end-to-end qua test double (không tốn tiền, không cần mạng): tạo đơn -> trả tiền -> hoàn theo vé ->
 * chốt kết quả -> hoàn kho. Phủ các nhánh dễ mất tiền: gửi trùng, timeout lúc gửi, ví thiếu, đích lạ, hoàn quá số đã thu.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.jwt.secret=test-secret-test-secret-test-secret-1234",
        "DB_URL=unused", "DB_USERNAME=unused", "DB_PASSWORD=unused",
        "app.refund.poll-interval=PT1H",        // job tự chạy sẽ làm test bất định; test tự gọi
        "app.refund.queue-interval=PT1H",
        "app.refund.recovery-interval=PT1H"
})
@ActiveProfiles("test")
class RefundFlowTests {

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
    @Autowired RefundRepository refunds;
    @Autowired RefundService refundService;
    @Autowired WalletService wallet;
    @Autowired JdbcTemplate jdbc;

    /**
     * Thay Mailer thật bằng mock: test đếm được số mail mà không cần SMTP (và không gửi mail thật khi chạy CI).
     * Spring tự reset mock sau MỖI test nên số đếm của test này không lẫn sang test khác.
     */
    @MockitoBean Mailer mailer;

    /* ---------- helpers ---------- */

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

    /** BTC thật: user role ORGANIZER + hồ sơ BTC trong một bước. Trả accessToken. */
    @SuppressWarnings("unchecked")
    private String registerOrganizer(String email) {
        Map<?, ?> auth = anon().post().uri("/api/v1/auth/register-organizer")
                .body(Map.of("fullName", "Ban to chuc", "email", email, "password", "password123",
                        "organizerName", "BTC " + UUID.randomUUID()))
                .retrieve().toEntity(Map.class).getBody();
        return (String) auth.get("accessToken");
    }

    private UUID organizerId(String email) {
        return jdbc.queryForObject("select o.id from organizers o join users u on u.id = o.user_id where u.email = ?",
                UUID.class, email);
    }

    /** RestClient mang token của BTC; http() mặc định là token người mua. */
    private RestClient as(String token) {
        return RestClient.builder().baseUrl("http://localhost:" + port)
                .defaultStatusHandler(status -> true, (req, res) -> { })
                .defaultHeaders(h -> h.setBearerAuth(token)).build();
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<Map> resolveAsOrganizer(String token, UUID refundId, String outcome) {
        return as(token).post().uri("/api/v1/organizer/refunds/" + refundId + "/resolve")
                .body(Map.of("outcome", outcome, "note", "da chuyen khoan tay")).retrieve().toEntity(Map.class);
    }

    /** Body resolve tự do: cần cho ca gửi thiếu field (CANCELLED mà không có note). */
    @SuppressWarnings("unchecked")
    private ResponseEntity<Map> resolveAsOrganizer(String token, UUID refundId, Map<String, Object> body) {
        return as(token).post().uri("/api/v1/organizer/refunds/" + refundId + "/resolve")
                .body(body).retrieve().toEntity(Map.class);
    }

    /** Lệnh hủy yêu cầu hoàn tiền (không đổi đích nên destination = null). */
    private static ResolveRefundRequest cancel(String note) {
        return new ResolveRefundRequest(ResolveRefundRequest.Outcome.CANCELLED, note, null);
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<Map> get(String path) {
        return http().get().uri(path).retrieve().toEntity(Map.class);
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<Map> post(String path) {
        return http().post().uri(path).retrieve().toEntity(Map.class);
    }

    /** startsAt xa hơn hạn hủy 48h, nếu không mọi refund đều bị REFUND_DEADLINE_PASSED. */
    private Event event() {
        return event(Instant.now().plusSeconds(10 * 86_400));
    }

    private Event event(Instant startsAt) {
        return event(startsAt, null);
    }

    private Event event(Instant startsAt, UUID organizerId) {
        return events.save(Event.builder().organizerId(organizerId).slug("ev-" + UUID.randomUUID()).name("Refund test").category("music")
                .startsAt(startsAt).status(EventStatus.PUBLISHED).venue(new Venue("Nơi", "Hà Nội", "Địa chỉ"))
                .description(List.of("mô tả")).schedule(List.of()).featured(false).build());
    }

    private TicketTier tier(Event e, int available, long price) {
        TicketTier t = tiers.save(new TicketTier(e.getId(), "GA", "", price, available, 10));
        inventory.save(new Inventory(t.getId(), available));
        return t;
    }

    private int available(TicketTier t) {
        return inventory.findById(t.getId()).orElseThrow().getAvailable();
    }

    /** Đơn đã PAID kèm vé đã cấp. */
    @SuppressWarnings("unchecked")
    private Map<?, ?> paidOrder(Event e, TicketTier t, int qty) {
        Map<String, Object> body = Map.of("eventId", e.getId(),
                "items", List.of(Map.of("tierId", t.getId(), "quantity", qty)),
                "customer", Map.of("name", "Nguyen Van A", "email", "a@example.com"));
        Map<?, ?> created = http().post().uri("/api/v1/orders")
                .headers(h -> h.set("Idempotency-Key", UUID.randomUUID().toString()))
                .body(body).retrieve().toEntity(Map.class).getBody();
        Map<?, ?> paid = post("/mock-gateway/payments/" + created.get("id") + "/succeed").getBody();
        assertEquals("PAID", paid.get("status"), String.valueOf(paid));
        return paid;
    }

    /** Đơn đã PAID, với mã ngân hàng người trả do test chỉ định (rỗng = ví điện tử không trả bank). */
    @SuppressWarnings("unchecked")
    private Map<?, ?> paidOrderWithPayerBank(Event e, TicketTier t, String payerBankBin) {
        Map<String, Object> body = Map.of("eventId", e.getId(),
                "items", List.of(Map.of("tierId", t.getId(), "quantity", 1)),
                "customer", Map.of("name", "Nguyen Van A", "email", "a@example.com"));
        Map<?, ?> created = http().post().uri("/api/v1/orders")
                .headers(h -> h.set("Idempotency-Key", UUID.randomUUID().toString()))
                .body(body).retrieve().toEntity(Map.class).getBody();
        Map<?, ?> paid = post("/mock-gateway/payments/" + created.get("id") + "/succeed?payerBankBin=" + payerBankBin).getBody();
        assertEquals("PAID", paid.get("status"), String.valueOf(paid));
        return paid;
    }

    private List<String> ticketIds(Map<?, ?> order) {
        return ((List<Map<String, Object>>) order.get("tickets")).stream().map(t -> (String) t.get("id")).toList();
    }

    /**
     * Email liên hệ mặc định cho các test không quan tâm tới nó. Cố ý KHÁC "a@example.com" (email lúc mua
     * của paidOrder) để nếu code nào lẫn hai thứ này thì test mail hủy refund sẽ chỉ ra ngay.
     */
    private static final String CONTACT_EMAIL = "lien-he@example.com";

    /** contactEmail là field BẮT BUỘC của body nên gom vào đây một chỗ, mọi test tạo refund đi qua hàm này. */
    private ResponseEntity<Map> requestRefund(Object orderId, List<String> ticketIds, String key, Map<String, String> destination) {
        return requestRefund(orderId, ticketIds, key, destination, CONTACT_EMAIL);
    }

    /** Bản chỉ định rõ contactEmail: cần cho ca kiểm chuẩn hóa email và ca mail hủy phải đi tới đúng hộp thư. */
    private ResponseEntity<Map> requestRefund(Object orderId, List<String> ticketIds, String key,
                                              Map<String, String> destination, String contactEmail) {
        Map<String, Object> body = destination == null
                ? Map.of("ticketIds", ticketIds, "reason", "khách đổi ý", "contactEmail", contactEmail)
                : Map.of("ticketIds", ticketIds, "reason", "khách đổi ý", "contactEmail", contactEmail,
                        "destination", destination);
        return postRefund(orderId, key, body);
    }

    /** Body tự do: cần cho ca gửi thiếu field (không có contactEmail) hoặc email sai định dạng. */
    @SuppressWarnings("unchecked")
    private ResponseEntity<Map> postRefund(Object orderId, String key, Map<String, Object> body) {
        return http().post().uri("/api/v1/orders/" + orderId + "/refunds")
                .headers(h -> h.set("Idempotency-Key", key))
                .body(body).retrieve().toEntity(Map.class);
    }

    private Map<?, ?> refund(Object refundId) {
        return get("/api/v1/refunds/" + refundId).getBody();
    }

    private String ticketStatus(String ticketId) {
        return jdbc.queryForObject("select status from tickets where id = ?::uuid", String.class, ticketId);
    }

    /* ---------- happy path ---------- */

    @Test
    void refundAllTicketsSettlesOrderAndReturnsInventory() {
        Event e = event();
        TicketTier t = tier(e, 10, 200_000);
        Map<?, ?> order = paidOrder(e, t, 2);
        assertEquals(8, available(t));
        List<String> ids = ticketIds(order);

        ResponseEntity<Map> res = requestRefund(order.get("id"), ids, UUID.randomUUID().toString(), null);
        assertEquals(202, res.getStatusCode().value(), String.valueOf(res.getBody()));
        Map<?, ?> r = res.getBody();
        assertEquals("PROCESSING", r.get("status"), String.valueOf(r));
        assertEquals(400_000, ((Number) r.get("amount")).longValue(), "hoàn giá vé, không hoàn phí dịch vụ");
        assertEquals("PAYOUT", r.get("executionMethod"));
        assertEquals(Boolean.TRUE, r.get("destinationIsPayer"));
        assertTrue(((String) r.get("destinationAccountMasked")).startsWith("*"), "số tài khoản phải mask");
        assertEquals("REFUND_PROCESSING", get("/api/v1/orders/" + order.get("id")).getBody().get("status"));
        ids.forEach(id -> assertEquals("REFUND_PENDING", ticketStatus(id)));

        // provider chốt thành công -> webhook về
        post("/mock-gateway/refunds/" + r.get("id") + "/succeed");

        assertEquals("SUCCEEDED", refund(r.get("id")).get("status"));
        assertEquals("REFUNDED", get("/api/v1/orders/" + order.get("id")).getBody().get("status"));
        ids.forEach(id -> assertEquals("REFUNDED", ticketStatus(id)));
        assertEquals(10, available(t), "kho phải được cộng lại");
    }

    @Test
    void ledgerBalancesAndRecordsBothSidesOfPaymentAndRefund() {
        Event e = event();
        TicketTier t = tier(e, 10, 200_000);
        Map<?, ?> order = paidOrder(e, t, 2);
        String orderId = (String) order.get("id");

        assertEquals(400_000L, ledgerSum(orderId, "BANK_COLLECTION", "DEBIT"), "tiền vào tài khoản thu");
        assertEquals(400_000L, ledgerSum(orderId, "CUSTOMER_LIABILITY", "CREDIT"), "sinh nợ với khách");
        assertEquals(0L, ledgerSum(orderId, "FEES", "CREDIT"), "không còn phí sàn");
        assertBalanced("ORDER", orderId);

        Map<?, ?> r = requestRefund(orderId, ticketIds(order), UUID.randomUUID().toString(), null).getBody();
        String refundId = (String) r.get("id");
        assertEquals(0, countLedger("REFUND", refundId), "lệnh đang bay chưa được vào sổ");

        post("/mock-gateway/refunds/" + refundId + "/succeed");

        assertEquals("SUCCEEDED", refund(refundId).get("status"));
        assertEquals(400_000L, ledgerSum(refundId, "CUSTOMER_LIABILITY", "DEBIT"), "giảm nợ với khách");
        assertEquals(400_000L, ledgerSum(refundId, "PAYOUT_WALLET", "CREDIT"), "tiền rời ví chi");
        assertBalanced("REFUND", refundId);
    }

    @Test
    void refundInstructionGivesOrganizerAScannableVietQr() {
        Event e = event();
        TicketTier t = tier(e, 10, 200_000);
        Map<?, ?> order = paidOrder(e, t, 1);
        Map<?, ?> r = requestRefund(order.get("id"), ticketIds(order), UUID.randomUUID().toString(), null).getBody();

        RefundInstruction ins = refundService.instruction(UUID.fromString((String) r.get("id")));

        assertEquals(200_000, ins.amount());
        assertTrue(ins.qrImageUrl().startsWith("https://img.vietqr.io/image/"), ins.qrImageUrl());
        assertTrue(ins.qrPayload().startsWith("000201"), "chuỗi EMVCo");
        assertTrue(ins.content().length() <= 25, "nội dung CK tối đa 25 ký tự");
        assertEquals(ins.bankBin(), ins.bankBin().replaceAll("\\D", ""), "BIN phải là số");
    }

    private long ledgerSum(String refId, String account, String direction) {
        return jdbc.queryForObject("select coalesce(sum(amount), 0) from ledger_entries where ref_id = ?::uuid"
                + " and account = ? and direction = ?", Long.class, refId, account, direction);
    }

    private int countLedger(String refType, String refId) {
        return jdbc.queryForObject("select count(*) from ledger_entries where ref_type = ? and ref_id = ?::uuid",
                Integer.class, refType, refId);
    }

    private void assertBalanced(String refType, String refId) {
        assertEquals(
                jdbc.queryForObject("select coalesce(sum(amount),0) from ledger_entries where ref_type=? and ref_id=?::uuid and direction='DEBIT'", Long.class, refType, refId),
                jdbc.queryForObject("select coalesce(sum(amount),0) from ledger_entries where ref_type=? and ref_id=?::uuid and direction='CREDIT'", Long.class, refType, refId),
                "bút toán phải cân: nợ == có");
    }

    @Test
    void refundSomeTicketsLeavesOrderPartiallyRefunded() {
        Event e = event();
        TicketTier t = tier(e, 10, 200_000);
        Map<?, ?> order = paidOrder(e, t, 2);
        List<String> ids = ticketIds(order);

        Map<?, ?> r = requestRefund(order.get("id"), List.of(ids.get(0)), UUID.randomUUID().toString(), null).getBody();
        post("/mock-gateway/refunds/" + r.get("id") + "/succeed");

        assertEquals("SUCCEEDED", refund(r.get("id")).get("status"));
        assertEquals("PARTIALLY_REFUNDED", get("/api/v1/orders/" + order.get("id")).getBody().get("status"));
        assertEquals("REFUNDED", ticketStatus(ids.get(0)));
        assertEquals("ACTIVE", ticketStatus(ids.get(1)));
        assertEquals(9, available(t), "chỉ cộng lại 1 vé");
    }

    /* ---------- các nhánh dễ mất tiền ---------- */

    @Test
    void sameIdempotencyKeyDoesNotCreateSecondRefund() {
        Event e = event();
        TicketTier t = tier(e, 10, 200_000);
        Map<?, ?> order = paidOrder(e, t, 1);
        String key = UUID.randomUUID().toString();

        Map<?, ?> first = requestRefund(order.get("id"), ticketIds(order), key, null).getBody();
        Map<?, ?> replay = requestRefund(order.get("id"), ticketIds(order), key, null).getBody();

        assertEquals(first.get("id"), replay.get("id"));
        assertEquals(1, refunds.findAllByOrderIdOrderByCreatedAt(UUID.fromString((String) order.get("id"))).size());
    }

    @Test
    void secondRefundWhileOneIsRunningIsRejected() {
        Event e = event();
        TicketTier t = tier(e, 10, 200_000);
        Map<?, ?> order = paidOrder(e, t, 2);
        List<String> ids = ticketIds(order);
        requestRefund(order.get("id"), List.of(ids.get(0)), UUID.randomUUID().toString(), null);

        ResponseEntity<Map> second = requestRefund(order.get("id"), List.of(ids.get(1)), UUID.randomUUID().toString(), null);

        assertEquals(409, second.getStatusCode().value(), String.valueOf(second.getBody()));
        assertEquals("ORDER_NOT_REFUNDABLE", second.getBody().get("code"));
    }

    /**
     * Bài học từ refund-mvp: provider ĐÃ nhận lệnh nhưng response rớt. Gửi lại phải dùng CÙNG key
     * để provider trả lệnh cũ, tuyệt đối không tạo lệnh chi thứ hai.
     */
    @Test
    void timeoutAfterProviderReceivedDoesNotPayTwice() {
        Event e = event();
        TicketTier t = tier(e, 10, 200_000);
        Map<?, ?> order = paidOrder(e, t, 1);
        post("/mock-gateway/refunds/next-timeout");
        long reservedBefore = ((Number) get("/mock-gateway/balance").getBody().get("reserved")).longValue();

        Map<?, ?> r = requestRefund(order.get("id"), ticketIds(order), UUID.randomUUID().toString(), null).getBody();

        // submit() tra lại theo key ngay khi timeout -> nhận đúng lệnh provider đã tạo
        assertEquals("PROCESSING", r.get("status"), String.valueOf(r));
        assertNotNull(r.get("providerRefundId"));
        long reservedAfter = ((Number) get("/mock-gateway/balance").getBody().get("reserved")).longValue();
        assertEquals(reservedBefore + 200_000, reservedAfter, "ví chỉ được giữ tiền MỘT lần");
        assertEquals(1, ((Number) r.get("attempt")).intValue());

        post("/mock-gateway/refunds/" + r.get("id") + "/succeed");
        assertEquals("SUCCEEDED", refund(r.get("id")).get("status"));
        assertEquals(10, available(t));
    }

    @Test
    void emptyWalletQueuesRefundAndDrainsAfterTopUp() {
        Event e = event();
        TicketTier t = tier(e, 10, 200_000);
        Map<?, ?> order = paidOrder(e, t, 1);
        long original = ((Number) get("/mock-gateway/balance").getBody().get("balance")).longValue();
        post("/mock-gateway/balance?amount=1000");                       // ví gần cạn

        Map<?, ?> r = requestRefund(order.get("id"), ticketIds(order), UUID.randomUUID().toString(), null).getBody();
        assertEquals("AWAITING_FUNDS", r.get("status"), String.valueOf(r));
        assertEquals("INSUFFICIENT_PAYOUT_BALANCE", r.get("failureCode"));

        post("/mock-gateway/balance?amount=" + original);                // nạp ví
        refundService.drainQueue();

        assertEquals("PROCESSING", refund(r.get("id")).get("status"));
        post("/mock-gateway/refunds/" + r.get("id") + "/succeed");
        assertEquals("SUCCEEDED", refund(r.get("id")).get("status"));
    }

    @Test
    void providerFailureRestoresTicketsAndKeepsInventory() {
        Event e = event();
        TicketTier t = tier(e, 10, 200_000);
        Map<?, ?> order = paidOrder(e, t, 1);
        List<String> ids = ticketIds(order);

        Map<?, ?> r = requestRefund(order.get("id"), ids, UUID.randomUUID().toString(), null).getBody();
        post("/mock-gateway/refunds/" + r.get("id") + "/fail");

        Map<?, ?> after = refund(r.get("id"));
        assertEquals("FAILED", after.get("status"));
        assertEquals("BANK_REJECTED", after.get("failureCode"));
        assertEquals("ACTIVE", ticketStatus(ids.get(0)), "vé dùng lại được");
        assertEquals("REFUND_FAILED", get("/api/v1/orders/" + order.get("id")).getBody().get("status"));
        assertEquals(9, available(t), "kho KHÔNG được cộng khi tiền chưa đi");
    }

    @Test
    void duplicateWebhookReleasesInventoryOnlyOnce() {
        Event e = event();
        TicketTier t = tier(e, 10, 200_000);
        Map<?, ?> order = paidOrder(e, t, 1);
        Map<?, ?> r = requestRefund(order.get("id"), ticketIds(order), UUID.randomUUID().toString(), null).getBody();

        post("/mock-gateway/refunds/" + r.get("id") + "/succeed?duplicate=true");

        assertEquals("SUCCEEDED", refund(r.get("id")).get("status"));
        assertEquals(10, available(t), "webhook trùng không được cộng kho hai lần");
        List<String> results = jdbc.queryForList(
                "select processing_result from webhook_events where event_type = 'refund' and event_id like ? order by received_at",
                String.class, "%" + r.get("id") + "%");
        assertEquals(List.of("PROCESSED"), results,
                "lần hai bị unique (provider, event_id) chặn ngay ở INSERT nên không sinh row thứ hai");
    }

    @Test
    void badSignatureWebhookIsRejectedAndRecorded() {
        Event e = event();
        TicketTier t = tier(e, 10, 200_000);
        Map<?, ?> order = paidOrder(e, t, 1);
        Map<?, ?> r = requestRefund(order.get("id"), ticketIds(order), UUID.randomUUID().toString(), null).getBody();

        post("/mock-gateway/refunds/" + r.get("id") + "/succeed?badSignature=true");

        assertEquals("PROCESSING", refund(r.get("id")).get("status"), "ký sai không được chốt refund");
        assertEquals(9, available(t));
        Long rejected = jdbc.queryForObject(
                "select count(*) from webhook_events where signature_valid = false and raw_payload::text like ?",
                Long.class, "%" + r.get("id") + "%");
        assertEquals(1L, rejected, "vẫn phải ghi sổ webhook ký sai");
    }

    /* ---------- chính sách và đích đến ---------- */

    @Test
    void refundAfterDeadlineIsRejected() {
        Event e = event(Instant.now().plusSeconds(24 * 3600));       // trong hạn 48h -> quá hạn hủy
        TicketTier t = tier(e, 10, 200_000);
        Map<?, ?> order = paidOrder(e, t, 1);

        ResponseEntity<Map> res = requestRefund(order.get("id"), ticketIds(order), UUID.randomUUID().toString(), null);

        assertEquals(409, res.getStatusCode().value(), String.valueOf(res.getBody()));
        assertEquals("REFUND_DEADLINE_PASSED", res.getBody().get("code"));
        assertEquals("PAID", get("/api/v1/orders/" + order.get("id")).getBody().get("status"), "đơn không được đổi trạng thái");
    }

    @Test
    void otherDestinationWaitsForOrganizerThenRunsOnRetry() {
        Event e = event();
        TicketTier t = tier(e, 10, 200_000);
        Map<?, ?> order = paidOrder(e, t, 1);

        Map<?, ?> r = requestRefund(order.get("id"), ticketIds(order), UUID.randomUUID().toString(),
                Map.of("bin", "970422", "accountNumber", "1234567891")).getBody();

        assertEquals("MANUAL_REVIEW", r.get("status"), String.valueOf(r));
        assertEquals("DESTINATION_REVIEW", r.get("failureCode"));
        assertEquals(Boolean.FALSE, r.get("destinationIsPayer"));
        assertEquals(9, available(t), "chưa chi thì chưa hoàn kho");

        UUID refundId = UUID.fromString((String) r.get("id"));
        refundService.resolve(refundId, new ResolveRefundRequest(ResolveRefundRequest.Outcome.RETRY, "đã gọi khách xác nhận", null));

        assertEquals("PROCESSING", refund(refundId).get("status"));
        post("/mock-gateway/refunds/" + refundId + "/succeed");
        assertEquals("SUCCEEDED", refund(refundId).get("status"));
        assertEquals(10, available(t));
    }

    @Test
    void invalidDestinationFromProviderFailsRefund() {
        Event e = event();
        TicketTier t = tier(e, 10, 200_000);
        Map<?, ?> order = paidOrder(e, t, 1);

        // cửa test của mock: số tài khoản tận cùng 000 -> provider từ chối đích
        Map<?, ?> r = requestRefund(order.get("id"), ticketIds(order), UUID.randomUUID().toString(),
                Map.of("bin", "970422", "accountNumber", "1234567000")).getBody();
        UUID refundId = UUID.fromString((String) r.get("id"));
        refundService.resolve(refundId, new ResolveRefundRequest(ResolveRefundRequest.Outcome.RETRY, "BTC duyệt", null));

        Map<?, ?> after = refund(refundId);
        assertEquals("FAILED", after.get("status"));
        assertEquals("INVALID_DESTINATION", after.get("failureCode"));
        assertEquals(9, available(t), "provider từ chối thì tiền chưa đi, kho giữ nguyên");
    }

    @Test
    void organizerCannotRetryWhenProviderAlreadyHasTheOrder() {
        Event e = event();
        TicketTier t = tier(e, 10, 200_000);
        Map<?, ?> order = paidOrder(e, t, 1);
        Map<?, ?> r = requestRefund(order.get("id"), ticketIds(order), UUID.randomUUID().toString(), null).getBody();
        UUID refundId = UUID.fromString((String) r.get("id"));
        post("/mock-gateway/refunds/" + refundId + "/hold");            // provider treo -> MANUAL_REVIEW
        assertEquals("MANUAL_REVIEW", refund(refundId).get("status"));

        // Lệnh đã nằm ở provider: RETRY là chi hai lần, phải bị chặn
        DomainException ex = org.junit.jupiter.api.Assertions.assertThrows(DomainException.class,
                () -> refundService.resolve(refundId, new ResolveRefundRequest(ResolveRefundRequest.Outcome.RETRY, "thử lại", null)));
        assertEquals("REFUND_ALREADY_AT_PROVIDER", ex.getCode());

        // Đường đúng: BTC tra dashboard rồi chốt tay
        refundService.resolve(refundId, new ResolveRefundRequest(ResolveRefundRequest.Outcome.SUCCEEDED, "đã thấy tiền đi", null));
        assertEquals("SUCCEEDED", refund(refundId).get("status"));
        assertEquals(10, available(t));
    }

    /* ---------- BTC duyệt refund: đường DUY NHẤT chốt tay, admin không nắm tiền nên không có cửa ---------- */

    /**
     * Một lượt đủ ba bước của BTC: thấy hàng chờ -> lấy QR -> chốt SUCCEEDED, và BTC khác thì 404.
     * Vỡ bất kỳ bước nào là refund tài khoản lạ không còn ai chốt được.
     */
    @Test
    @SuppressWarnings("unchecked")
    void organizerListsInstructsAndResolvesOnlyOwnRefund() {
        String email = "btc-" + UUID.randomUUID() + "@example.com";
        String btc = registerOrganizer(email);
        Event e = event(Instant.now().plusSeconds(10 * 86_400), organizerId(email));
        TicketTier t = tier(e, 10, 200_000);
        Map<?, ?> order = paidOrder(e, t, 1);

        // đích khác tài khoản đã trả -> MANUAL_REVIEW, chờ chính BTC duyệt
        Map<?, ?> r = requestRefund(order.get("id"), ticketIds(order), UUID.randomUUID().toString(),
                Map.of("bin", "970422", "accountNumber", "1234567891")).getBody();
        UUID refundId = UUID.fromString((String) r.get("id"));
        assertEquals("MANUAL_REVIEW", r.get("status"), String.valueOf(r));
        assertEquals(9, available(t), "chưa chi thì chưa hoàn kho");

        List<Map<String, Object>> queue = as(btc).get().uri("/api/v1/organizer/refunds?status=MANUAL_REVIEW")
                .retrieve().body(List.class);
        assertEquals(List.of(refundId.toString()), queue.stream().map(x -> x.get("id")).toList());

        Map<String, Object> ins = as(btc).get().uri("/api/v1/organizer/refunds/" + refundId + "/instruction")
                .retrieve().body(Map.class);
        assertEquals(200_000, ((Number) ins.get("amount")).longValue());
        assertTrue(((String) ins.get("qrImageUrl")).startsWith("https://img.vietqr.io/image/"), String.valueOf(ins));

        // BTC của sự kiện khác: 404, không lộ id có tồn tại
        ResponseEntity<Map> stranger = resolveAsOrganizer(
                registerOrganizer("btc2-" + UUID.randomUUID() + "@example.com"), refundId, "SUCCEEDED");
        assertEquals(404, stranger.getStatusCode().value(), String.valueOf(stranger.getBody()));
        assertEquals("MANUAL_REVIEW", refund(refundId).get("status"), "BTC khác không được chốt");

        // chuyển khoản xong -> chốt; đi chung RefundResultHandler nên kho cộng đúng một lần
        assertEquals(200, resolveAsOrganizer(btc, refundId, "SUCCEEDED").getStatusCode().value());
        assertEquals("SUCCEEDED", refund(refundId).get("status"));
        assertEquals(10, available(t));
    }

    /* ---------- thông báo cho BTC: chỉ họ nạp được ví / chuyển khoản tay ---------- */

    /**
     * Ví chi hết tiền: BTC phải nhận ĐÚNG MỘT mail, kể cả khi RefundQueueJob quét lại nhiều vòng.
     * Vỡ test này nghĩa là một trong hai điều tệ: refund kẹt mà không ai được báo, hoặc BTC bị spam mỗi 60 giây.
     */
    @Test
    @SuppressWarnings("unchecked")
    void emptyWalletMailsOrganizerExactlyOnce() {
        String email = "btc-" + UUID.randomUUID() + "@example.com";
        registerOrganizer(email);   // BTC chưa khai contactEmail -> mail phải fallback về email user chủ tài khoản
        Event e = event(Instant.now().plusSeconds(10 * 86_400), organizerId(email));
        TicketTier t = tier(e, 10, 200_000);
        Map<?, ?> order = paidOrder(e, t, 1);
        long original = ((Number) get("/mock-gateway/balance").getBody().get("balance")).longValue();
        post("/mock-gateway/balance?amount=1000");                        // ví gần cạn

        Map<?, ?> r = requestRefund(order.get("id"), ticketIds(order), UUID.randomUUID().toString(), null).getBody();
        assertEquals("AWAITING_FUNDS", r.get("status"), String.valueOf(r));

        ArgumentCaptor<RefundMailInfo> sent = ArgumentCaptor.forClass(RefundMailInfo.class);
        verify(mailer).sendRefundAwaitingFunds(eq(email), sent.capture());
        assertEquals(((Number) order.get("orderCode")).longValue(), sent.getValue().orderCode());
        assertEquals(200_000, sent.getValue().amount());
        assertEquals(1, sent.getValue().ticketCount());
        assertEquals("INSUFFICIENT_PAYOUT_BALANCE", sent.getValue().reasonCode());
        assertEquals("a@example.com", sent.getValue().customerEmail());

        // Job quét lại khi ví VẪN thiếu: nhánh thiếu tiền break chứ không submit lại, nên không có mail thứ hai.
        refundService.drainQueue();
        refundService.drainQueue();
        verify(mailer, times(1)).sendRefundAwaitingFunds(eq(email), any());
        verify(mailer, never()).sendRefundNeedsReview(any(), any());

        post("/mock-gateway/balance?amount=" + original);                 // nạp ví -> chạy tiếp như thường
        refundService.drainQueue();
        assertEquals("PROCESSING", refund(r.get("id")).get("status"));
        verify(mailer, times(1)).sendRefundAwaitingFunds(eq(email), any());
        post("/mock-gateway/refunds/" + r.get("id") + "/succeed");
        assertEquals(10, available(t));
    }

    /**
     * Chờ ví quá {@code app.refund.awaiting-funds-timeout} -> MANUAL_REVIEW: BTC nhận đúng một mail "xử lý tay",
     * và vòng job kế tiếp không gửi lại (lệnh đã rời hàng chờ nên không còn được quét).
     */
    @Test
    @SuppressWarnings("unchecked")
    void awaitingFundsTimeoutMailsOrganizerOnce() {
        String email = "btc-" + UUID.randomUUID() + "@example.com";
        registerOrganizer(email);
        Event e = event(Instant.now().plusSeconds(10 * 86_400), organizerId(email));
        TicketTier t = tier(e, 10, 200_000);
        Map<?, ?> order = paidOrder(e, t, 1);
        long original = ((Number) get("/mock-gateway/balance").getBody().get("balance")).longValue();
        post("/mock-gateway/balance?amount=1000");

        Map<?, ?> r = requestRefund(order.get("id"), ticketIds(order), UUID.randomUUID().toString(), null).getBody();
        assertEquals("AWAITING_FUNDS", r.get("status"), String.valueOf(r));

        // Lùi thời điểm vào hàng chờ về 48h trước để không phải chờ 24h thật
        jdbc.update("update refunds set queued_since = now() - interval '48 hours' where id = ?::uuid", r.get("id"));

        refundService.drainQueue();
        assertEquals("MANUAL_REVIEW", refund(r.get("id")).get("status"));
        ArgumentCaptor<RefundMailInfo> sent = ArgumentCaptor.forClass(RefundMailInfo.class);
        verify(mailer).sendRefundNeedsReview(eq(email), sent.capture());
        assertEquals("AWAITING_FUNDS_TIMEOUT", sent.getValue().reasonCode());

        refundService.drainQueue();                                       // vòng job kế tiếp
        verify(mailer, times(1)).sendRefundNeedsReview(eq(email), any());

        post("/mock-gateway/balance?amount=" + original);                 // trả ví về cho các test sau
    }

    /* ---------- email liên hệ của khách ---------- */

    /**
     * contactEmail là đường DUY NHẤT để báo cho khách khi BTC hủy yêu cầu hoàn vé, nên phải bắt buộc ngay ở API
     * (cột DB nullable vì không backfill được cho row cũ — xem V7__refund_contact_email.sql).
     * Thiếu hoặc sai định dạng thì 400 và KHÔNG được tạo refund nào, vé cũng không được đổi trạng thái.
     */
    @Test
    void contactEmailIsRequiredAndMustLookLikeAnEmail() {
        Event e = event();
        TicketTier t = tier(e, 10, 200_000);
        Map<?, ?> order = paidOrder(e, t, 1);
        List<String> ids = ticketIds(order);

        ResponseEntity<Map> missing = postRefund(order.get("id"), UUID.randomUUID().toString(),
                Map.of("ticketIds", ids, "reason", "khách đổi ý"));
        assertEquals(400, missing.getStatusCode().value(), String.valueOf(missing.getBody()));
        assertEquals("VALIDATION", missing.getBody().get("code"));

        ResponseEntity<Map> blank = postRefund(order.get("id"), UUID.randomUUID().toString(),
                Map.of("ticketIds", ids, "contactEmail", "   "));
        assertEquals(400, blank.getStatusCode().value(), String.valueOf(blank.getBody()));

        ResponseEntity<Map> notAnEmail = postRefund(order.get("id"), UUID.randomUUID().toString(),
                Map.of("ticketIds", ids, "contactEmail", "khong-phai-email"));
        assertEquals(400, notAnEmail.getStatusCode().value(), String.valueOf(notAnEmail.getBody()));
        assertEquals("VALIDATION", notAnEmail.getBody().get("code"));

        assertEquals(0, refunds.findAllByOrderIdOrderByCreatedAt(UUID.fromString((String) order.get("id"))).size(),
                "body sai thì không được tạo refund nào");
        ids.forEach(id -> assertEquals("ACTIVE", ticketStatus(id), "vé không được đổi trạng thái"));
        assertEquals(9, available(t));
    }

    /**
     * Email lưu ở dạng chuẩn hóa (trim + chữ thường). TẠI SAO quan trọng: "  Foo@BAR.com " và "foo@bar.com"
     * là cùng một hộp thư, nhưng khoảng trắng đầu/cuối làm SMTP từ chối địa chỉ, còn chữ hoa thì làm mọi
     * so khớp email sau này sai. Chuẩn hóa một lần lúc lưu là xong.
     */
    @Test
    void contactEmailIsStoredTrimmedAndLowercased() {
        Event e = event();
        TicketTier t = tier(e, 10, 200_000);
        Map<?, ?> order = paidOrder(e, t, 1);

        Map<?, ?> r = requestRefund(order.get("id"), ticketIds(order), UUID.randomUUID().toString(), null,
                "  Foo@BAR.com ").getBody();

        String stored = jdbc.queryForObject("select contact_email from refunds where id = ?::uuid",
                String.class, r.get("id"));
        assertEquals("foo@bar.com", stored);
    }

    /* ---------- phải là chủ đơn mới hoàn được ---------- */

    @Test
    void guestCannotRequestRefund() {
        Event e = event();
        TicketTier t = tier(e, 10, 200_000);
        Map<?, ?> order = paidOrder(e, t, 1);

        ResponseEntity<Map> res = anon().post().uri("/api/v1/orders/" + order.get("id") + "/refunds")
                .headers(h -> h.set("Idempotency-Key", UUID.randomUUID().toString()))
                .body(Map.of("ticketIds", ticketIds(order), "contactEmail", CONTACT_EMAIL))
                .retrieve().toEntity(Map.class);

        assertEquals(401, res.getStatusCode().value(), String.valueOf(res.getBody()));
        assertEquals(0, refunds.findAllByOrderIdOrderByCreatedAt(UUID.fromString((String) order.get("id"))).size());
    }

    /** Hoàn tiền là tiền RA: biết orderId thôi không đủ, phải đúng chủ đơn. 404 để không tiết lộ đơn tồn tại. */
    @Test
    void otherUserCannotRefundSomeoneElsesOrder() {
        Event e = event();
        TicketTier t = tier(e, 10, 200_000);
        Map<?, ?> order = paidOrder(e, t, 1);
        String otherToken = register();

        ResponseEntity<Map> res = anon().post().uri("/api/v1/orders/" + order.get("id") + "/refunds")
                .headers(h -> { h.setBearerAuth(otherToken); h.set("Idempotency-Key", UUID.randomUUID().toString()); })
                // contactEmail phải có: @Valid chạy TRƯỚC thân controller, thiếu field là 400 VALIDATION
                // và ta sẽ không bao giờ kiểm được cái 404 mà test này muốn kiểm.
                .body(Map.of("ticketIds", ticketIds(order), "contactEmail", CONTACT_EMAIL))
                .retrieve().toEntity(Map.class);

        assertEquals(404, res.getStatusCode().value(), String.valueOf(res.getBody()));
        assertEquals("ORDER_NOT_FOUND", res.getBody().get("code"));
        assertEquals(0, refunds.findAllByOrderIdOrderByCreatedAt(UUID.fromString((String) order.get("id"))).size(),
                "không được tạo refund nào");
        ticketIds(order).forEach(id -> assertEquals("ACTIVE", ticketStatus(id), "vé của người ta không bị đổi trạng thái"));
    }

    /* ---------- chọn ngân hàng ---------- */

    /**
     * PayOS có thể trả counterAccountBankId là mã CITAD 8 số (hoặc rỗng với ví điện tử). Chi hộ không dùng được
     * mã đó, nên phải coi như KHÔNG BIẾT ngân hàng và bắt khách tự chọn — chứ không gửi lệnh chi vào mã rác.
     */
    @Test
    void nonBinFromWebhookForcesBuyerToChooseBank() {
        Event e = event();
        TicketTier t = tier(e, 10, 200_000);
        Map<?, ?> order = paidOrderWithPayerBank(e, t, "01201001");        // mã CITAD 8 số
        assertNull(jdbc.queryForObject("select payer_bank_bin from payments where order_id = ?::uuid", String.class,
                order.get("id")), "mã không đúng dạng BIN phải bị bỏ, không lưu vào payment");

        ResponseEntity<Map> auto = requestRefund(order.get("id"), ticketIds(order), UUID.randomUUID().toString(), null);
        assertEquals(409, auto.getStatusCode().value(), String.valueOf(auto.getBody()));
        assertEquals("PAYER_ACCOUNT_UNKNOWN", auto.getBody().get("code"));
        assertEquals("PAID", get("/api/v1/orders/" + order.get("id")).getBody().get("status"), "đơn chưa được đổi trạng thái");

        // Khách chọn ngân hàng + nhập số tài khoản -> đi đường MANUAL_REVIEW
        Map<?, ?> r = requestRefund(order.get("id"), ticketIds(order), UUID.randomUUID().toString(),
                Map.of("bin", "970436", "accountNumber", "1234567891")).getBody();
        assertEquals("MANUAL_REVIEW", r.get("status"), String.valueOf(r));
        assertEquals("970436", r.get("destinationBin"));
    }

    @Test
    void sameAccountWithChosenBankRunsAutomatically() {
        Event e = event();
        TicketTier t = tier(e, 10, 200_000);
        Map<?, ?> order = paidOrderWithPayerBank(e, t, "01201001");        // PayOS trả mã CITAD, chỉ thiếu mã ngân hàng

        // Khách chọn ngân hàng cho ĐÚNG số tài khoản đã thanh toán -> tiền vẫn về chỗ cũ, không cần ai duyệt.
        Map<?, ?> r = requestRefund(order.get("id"), ticketIds(order), UUID.randomUUID().toString(),
                Map.of("bin", "970436", "accountNumber", "0123456789012")).getBody();

        assertEquals("PROCESSING", r.get("status"), String.valueOf(r));
        assertEquals(Boolean.TRUE, r.get("destinationIsPayer"), "khớp số tài khoản thì vẫn tính là người trả");
        assertEquals("970436", r.get("destinationBin"), "dùng ngân hàng khách chọn, không dùng mã CITAD");

        post("/mock-gateway/refunds/" + r.get("id") + "/succeed");
        assertEquals("SUCCEEDED", refund(r.get("id")).get("status"));
        assertEquals("REFUNDED", get("/api/v1/orders/" + order.get("id")).getBody().get("status"));
    }

    @Test
    void emptyPayerBankFromWalletAlsoForcesChoice() {
        Event e = event();
        TicketTier t = tier(e, 10, 200_000);
        Map<?, ?> order = paidOrderWithPayerBank(e, t, "");                // ví điện tử: không trả bank

        ResponseEntity<Map> auto = requestRefund(order.get("id"), ticketIds(order), UUID.randomUUID().toString(), null);

        assertEquals(409, auto.getStatusCode().value(), String.valueOf(auto.getBody()));
        assertEquals("PAYER_ACCOUNT_UNKNOWN", auto.getBody().get("code"));
    }

    @Test
    void destinationBinMustBeSixDigits() {
        Event e = event();
        TicketTier t = tier(e, 10, 200_000);
        Map<?, ?> order = paidOrder(e, t, 1);

        ResponseEntity<Map> res = requestRefund(order.get("id"), ticketIds(order), UUID.randomUUID().toString(),
                Map.of("bin", "01201001", "accountNumber", "1234567891"));

        assertEquals(400, res.getStatusCode().value(), String.valueOf(res.getBody()));
        assertEquals("INVALID_BANK_BIN", res.getBody().get("code"));
    }

    @Test
    void configExposesBankListForDropdown() {
        Map<?, ?> config = get("/api/v1/config").getBody();

        List<Map<String, String>> banks = (List<Map<String, String>>) config.get("banks");
        assertTrue(banks.size() > 20, "phải có danh sách ngân hàng cho dropdown, có " + banks.size());
        assertEquals("970422", banks.get(0).get("bin"), "ngân hàng phổ biến lên đầu");
        assertEquals("MB Bank", banks.get(0).get("name"));
    }

    /* ---------- ví ---------- */

    @Test
    void walletSnapshotCountsCommittedAndLiability() {
        Event e = event();
        TicketTier t = tier(e, 10, 200_000);
        Map<?, ?> order = paidOrder(e, t, 2);
        WalletService.Snapshot before = wallet.snapshot();
        assertTrue(before.liability() >= 400_000, "2 vé ACTIVE phải nằm trong liability: " + before);

        Map<?, ?> r = requestRefund(order.get("id"), ticketIds(order), UUID.randomUUID().toString(), null).getBody();

        WalletService.Snapshot during = wallet.snapshot();
        assertEquals(400_000, during.committed() - before.committed(), "refund đang chạy phải vào committed");
        assertNotEquals(before.available(), during.available());

        post("/mock-gateway/refunds/" + r.get("id") + "/succeed");
        assertEquals(0, wallet.snapshot().committed() - before.committed(), "chốt xong thì hết committed");
    }

    @Test
    void refundIsNeverMoreThanPaid() {
        Event e = event();
        TicketTier t = tier(e, 10, 200_000);
        Map<?, ?> order = paidOrder(e, t, 1);
        UUID orderId = UUID.fromString((String) order.get("id"));
        long paid = jdbc.queryForObject("select paid_amount from orders where id = ?", Long.class, orderId);

        Map<?, ?> r = requestRefund(order.get("id"), ticketIds(order), UUID.randomUUID().toString(), null).getBody();
        post("/mock-gateway/refunds/" + r.get("id") + "/succeed");

        long refunded = jdbc.queryForObject("select refunded_amount from orders where id = ?", Long.class, orderId);
        assertTrue(refunded <= paid, "hoàn " + refunded + " không được vượt đã thu " + paid);
        assertEquals(RefundStatus.SUCCEEDED, refunds.findById(UUID.fromString((String) r.get("id"))).orElseThrow().getStatus());
    }

    /* ---------- BTC hủy yêu cầu hoàn tiền (outcome=CANCELLED) ---------- */

    /**
     * Ca hay gặp nhất: ví chi cạn nên refund xếp hàng AWAITING_FUNDS, khách đồng ý thôi không hoàn nữa.
     * Hủy phải trả vé về cho khách (ACTIVE) mà KHÔNG cộng kho — vé chưa từng nhả ra khỏi kho.
     */
    @Test
    void organizerCancelsRefundWaitingForWalletTopUp() {
        String email = "btc-" + UUID.randomUUID() + "@example.com";
        String btc = registerOrganizer(email);
        Event e = event(Instant.now().plusSeconds(10 * 86_400), organizerId(email));
        TicketTier t = tier(e, 10, 200_000);
        Map<?, ?> order = paidOrder(e, t, 1);
        List<String> ids = ticketIds(order);
        long original = ((Number) get("/mock-gateway/balance").getBody().get("balance")).longValue();
        post("/mock-gateway/balance?amount=1000");                         // ví gần cạn -> vào hàng chờ

        Map<?, ?> r = requestRefund(order.get("id"), ids, UUID.randomUUID().toString(), null).getBody();
        assertEquals("AWAITING_FUNDS", r.get("status"), String.valueOf(r));
        UUID refundId = UUID.fromString((String) r.get("id"));

        ResponseEntity<Map> res = resolveAsOrganizer(btc, refundId,
                Map.of("outcome", "CANCELLED", "note", "khách đổi ý, không hoàn nữa"));

        assertEquals(200, res.getStatusCode().value(), String.valueOf(res.getBody()));
        Map<?, ?> after = refund(refundId);
        assertEquals("FAILED", after.get("status"), "hủy đi chung đường FAILED, không thêm status mới vào DB");
        assertEquals("CANCELLED_BY_ORGANIZER", after.get("failureCode"));
        assertEquals("khách đổi ý, không hoàn nữa", after.get("failureReason"), "lý do hủy phải còn lại trong DB");
        assertEquals("ACTIVE", ticketStatus(ids.get(0)), "khách giữ lại vé");
        assertEquals("REFUND_FAILED", get("/api/v1/orders/" + order.get("id")).getBody().get("status"),
                "đơn về trạng thái xin hoàn lại được");
        assertEquals(9, available(t), "kho KHÔNG được cộng: vé chưa từng nhả ra");

        post("/mock-gateway/balance?amount=" + original);                 // trả ví về cho các test sau
    }

    /** Hủy từ MANUAL_REVIEW (đích lạ chờ BTC duyệt) cho cùng một kết quả. */
    @Test
    void organizerCancelsRefundInManualReview() {
        Event e = event();
        TicketTier t = tier(e, 10, 200_000);
        Map<?, ?> order = paidOrder(e, t, 1);
        List<String> ids = ticketIds(order);

        Map<?, ?> r = requestRefund(order.get("id"), ids, UUID.randomUUID().toString(),
                Map.of("bin", "970422", "accountNumber", "1234567891")).getBody();
        assertEquals("MANUAL_REVIEW", r.get("status"), String.valueOf(r));
        UUID refundId = UUID.fromString((String) r.get("id"));

        refundService.resolve(refundId, cancel("BTC không đồng ý hoàn về tài khoản lạ"));

        Map<?, ?> after = refund(refundId);
        assertEquals("FAILED", after.get("status"));
        assertEquals("CANCELLED_BY_ORGANIZER", after.get("failureCode"));
        assertNull(after.get("providerRefundId"), "không có lệnh nào ở provider");
        assertEquals("ACTIVE", ticketStatus(ids.get(0)));
        assertEquals("REFUND_FAILED", get("/api/v1/orders/" + order.get("id")).getBody().get("status"));
        assertEquals(9, available(t), "kho không tăng");
    }

    /**
     * Hai trạng thái KHÔNG được hủy vì tiền có thể đang đi: PROCESSING (provider đã nhận lệnh) và REQUESTED
     * (submit() có thể đang gọi provider ngay lúc đó). Hủy ở đây là khách vừa giữ vé vừa nhận tiền.
     */
    @Test
    void cancelIsRejectedWhileMoneyMayBeMoving() {
        Event e = event();
        TicketTier t = tier(e, 10, 200_000);
        Map<?, ?> order = paidOrder(e, t, 1);
        List<String> ids = ticketIds(order);
        Map<?, ?> r = requestRefund(order.get("id"), ids, UUID.randomUUID().toString(), null).getBody();
        assertEquals("PROCESSING", r.get("status"), String.valueOf(r));
        UUID refundId = UUID.fromString((String) r.get("id"));

        DomainException processing = org.junit.jupiter.api.Assertions.assertThrows(DomainException.class,
                () -> refundService.resolve(refundId, cancel("thử hủy")));
        assertEquals("REFUND_NOT_CANCELLABLE", processing.getCode());
        assertEquals(409, processing.getStatus().value());

        // Cửa sổ REQUESTED chỉ mở trong lúc submit() đang gọi provider nên không dựng được qua API:
        // lùi trạng thái bằng SQL, giống cách test hàng chờ lùi queued_since.
        jdbc.update("update refunds set status = 'REQUESTED', provider_refund_id = null where id = ?::uuid", refundId);

        DomainException requested = org.junit.jupiter.api.Assertions.assertThrows(DomainException.class,
                () -> refundService.resolve(refundId, cancel("thử hủy")));
        assertEquals("REFUND_NOT_CANCELLABLE", requested.getCode());
        assertEquals("REFUND_PENDING", ticketStatus(ids.get(0)), "vé vẫn đang chờ hoàn, không được nhả về ACTIVE");
        assertEquals(9, available(t));
    }

    /**
     * Cái bẫy chính: MANUAL_REVIEW do provider treo lệnh (hoặc PROCESSING_TIMEOUT) thì trạng thái nằm trong
     * danh sách "hủy được" NHƯNG providerRefundId != null — lệnh có thể vẫn đang bay. Phải chặn, y như RETRY.
     */
    @Test
    void cancelIsRejectedWhenProviderAlreadyHasTheOrder() {
        Event e = event();
        TicketTier t = tier(e, 10, 200_000);
        Map<?, ?> order = paidOrder(e, t, 1);
        Map<?, ?> r = requestRefund(order.get("id"), ticketIds(order), UUID.randomUUID().toString(), null).getBody();
        UUID refundId = UUID.fromString((String) r.get("id"));
        post("/mock-gateway/refunds/" + refundId + "/hold");              // provider treo -> MANUAL_REVIEW
        assertEquals("MANUAL_REVIEW", refund(refundId).get("status"));
        assertNotNull(refund(refundId).get("providerRefundId"));

        DomainException ex = org.junit.jupiter.api.Assertions.assertThrows(DomainException.class,
                () -> refundService.resolve(refundId, cancel("khách đổi ý")));
        assertEquals("REFUND_ALREADY_AT_PROVIDER", ex.getCode());
        assertEquals(409, ex.getStatus().value());
        assertEquals("MANUAL_REVIEW", refund(refundId).get("status"), "không được đổi gì");

        // Đường đúng: tra dashboard PayOS rồi chốt theo sự thật
        refundService.resolve(refundId,
                new ResolveRefundRequest(ResolveRefundRequest.Outcome.SUCCEEDED, "đã thấy tiền đi", null));
        assertEquals("SUCCEEDED", refund(refundId).get("status"));
        assertEquals(10, available(t));
    }

    /** note là lý do hủy: thiếu (hoặc toàn khoảng trắng) thì 400, còn ba outcome cũ vẫn không cần note. */
    @Test
    void cancelWithoutNoteIsRejected() {
        String email = "btc-" + UUID.randomUUID() + "@example.com";
        String btc = registerOrganizer(email);
        Event e = event(Instant.now().plusSeconds(10 * 86_400), organizerId(email));
        TicketTier t = tier(e, 10, 200_000);
        Map<?, ?> order = paidOrder(e, t, 1);
        Map<?, ?> r = requestRefund(order.get("id"), ticketIds(order), UUID.randomUUID().toString(),
                Map.of("bin", "970422", "accountNumber", "1234567891")).getBody();
        UUID refundId = UUID.fromString((String) r.get("id"));

        ResponseEntity<Map> missing = resolveAsOrganizer(btc, refundId, Map.of("outcome", "CANCELLED"));
        assertEquals(400, missing.getStatusCode().value(), String.valueOf(missing.getBody()));
        assertEquals("REFUND_CANCEL_NOTE_REQUIRED", missing.getBody().get("code"));

        ResponseEntity<Map> blank = resolveAsOrganizer(btc, refundId, Map.of("outcome", "CANCELLED", "note", "   "));
        assertEquals(400, blank.getStatusCode().value(), String.valueOf(blank.getBody()));
        assertEquals("MANUAL_REVIEW", refund(refundId).get("status"), "refund không được đổi gì");

        // FAILED không bắt buộc note: hành vi cũ giữ nguyên
        assertEquals(200, resolveAsOrganizer(btc, refundId, Map.of("outcome", "FAILED")).getStatusCode().value());
        assertEquals("ADMIN_REJECTED", refund(refundId).get("failureCode"));
    }

    /** Bấm hủy hai lần: lần hai không đổi thêm gì (vé chỉ về ACTIVE một lần, kho không lệch). */
    @Test
    void cancellingTwiceChangesNothingMore() {
        Event e = event();
        TicketTier t = tier(e, 10, 200_000);
        Map<?, ?> order = paidOrder(e, t, 1);
        List<String> ids = ticketIds(order);
        Map<?, ?> r = requestRefund(order.get("id"), ids, UUID.randomUUID().toString(),
                Map.of("bin", "970422", "accountNumber", "1234567891")).getBody();
        UUID refundId = UUID.fromString((String) r.get("id"));

        refundService.resolve(refundId, cancel("khách đổi ý"));
        Map<?, ?> afterFirst = refund(refundId);

        DomainException second = org.junit.jupiter.api.Assertions.assertThrows(DomainException.class,
                () -> refundService.resolve(refundId, cancel("hủy lần hai")));
        assertEquals("REFUND_NOT_CANCELLABLE", second.getCode(), "đã FAILED thì không hủy lại được nữa");

        Map<?, ?> afterSecond = refund(refundId);
        assertEquals("FAILED", afterSecond.get("status"));
        assertEquals(afterFirst.get("failureReason"), afterSecond.get("failureReason"), "lý do hủy lần đầu còn nguyên");
        assertEquals("ACTIVE", ticketStatus(ids.get(0)));
        assertEquals(9, available(t), "kho không lệch");
    }

    /** Hủy cũng là tiền/vé của sự kiện người ta: BTC khác phải nhận 404 và không đổi được gì. */
    @Test
    void otherOrganizerCannotCancelRefund() {
        String email = "btc-" + UUID.randomUUID() + "@example.com";
        registerOrganizer(email);
        Event e = event(Instant.now().plusSeconds(10 * 86_400), organizerId(email));
        TicketTier t = tier(e, 10, 200_000);
        Map<?, ?> order = paidOrder(e, t, 1);
        Map<?, ?> r = requestRefund(order.get("id"), ticketIds(order), UUID.randomUUID().toString(),
                Map.of("bin", "970422", "accountNumber", "1234567891")).getBody();
        UUID refundId = UUID.fromString((String) r.get("id"));

        ResponseEntity<Map> stranger = resolveAsOrganizer(
                registerOrganizer("btc2-" + UUID.randomUUID() + "@example.com"), refundId,
                Map.of("outcome", "CANCELLED", "note", "hủy hộ"));

        assertEquals(404, stranger.getStatusCode().value(), String.valueOf(stranger.getBody()));
        assertEquals("REFUND_NOT_FOUND", stranger.getBody().get("code"));
        assertEquals("MANUAL_REVIEW", refund(refundId).get("status"), "BTC khác không được hủy");
        assertEquals("REFUND_PENDING", ticketStatus(ticketIds(order).get(0)));
    }

    /**
     * Điểm quan trọng nhất của hủy: phải MỞ LẠI đường hoàn tiền, không để vé kẹt. Hủy vì nhập sai số tài khoản
     * rồi khách xin hoàn lại — lần này để trống destination nên tiền về đúng tài khoản đã trả, chạy tự động.
     */
    @Test
    void customerCanRequestRefundAgainAfterCancel() {
        Event e = event();
        TicketTier t = tier(e, 10, 200_000);
        Map<?, ?> order = paidOrder(e, t, 1);
        List<String> ids = ticketIds(order);
        Map<?, ?> first = requestRefund(order.get("id"), ids, UUID.randomUUID().toString(),
                Map.of("bin", "970422", "accountNumber", "1234567891")).getBody();
        refundService.resolve(UUID.fromString((String) first.get("id")), cancel("nhập sai số tài khoản"));

        ResponseEntity<Map> again = requestRefund(order.get("id"), ids, UUID.randomUUID().toString(), null);

        assertEquals(202, again.getStatusCode().value(), String.valueOf(again.getBody()));
        Map<?, ?> second = again.getBody();
        assertNotEquals(first.get("id"), second.get("id"), "phải là một refund MỚI, không dùng lại cái đã hủy");
        assertEquals("PROCESSING", second.get("status"), String.valueOf(second));

        post("/mock-gateway/refunds/" + second.get("id") + "/succeed");
        assertEquals("SUCCEEDED", refund(second.get("id")).get("status"));
        assertEquals("FAILED", refund(first.get("id")).get("status"), "refund đã hủy vẫn nằm im ở FAILED");
        assertEquals("REFUNDED", get("/api/v1/orders/" + order.get("id")).getBody().get("status"));
        assertEquals(10, available(t), "kho chỉ cộng khi tiền THẬT SỰ đi, và chỉ một lần");
    }

    /* ---------- thông báo cho KHÁCH khi bị hủy yêu cầu hoàn vé ---------- */

    /**
     * BTC hủy -> khách nhận ĐÚNG MỘT mail, ở đúng email khách đã nhập lúc tạo yêu cầu (KHÔNG phải email lúc mua),
     * với số tiền và số vé của chính yêu cầu đã hủy. Vỡ test này nghĩa là khách bị hủy mà không hay biết,
     * hoặc mail bay tới hộp thư khác hộp thư khách chỉ định.
     */
    @Test
    @SuppressWarnings("unchecked")
    void cancelMailsCustomerOnceAtTheEmailFromTheRequest() {
        String btcEmail = "btc-" + UUID.randomUUID() + "@example.com";
        String btc = registerOrganizer(btcEmail);
        Event e = event(Instant.now().plusSeconds(10 * 86_400), organizerId(btcEmail));
        TicketTier t = tier(e, 10, 200_000);
        Map<?, ?> order = paidOrder(e, t, 2);                             // 2 vé x 200k -> yêu cầu hoàn 400k
        List<String> ids = ticketIds(order);
        // Đơn mua bằng a@example.com nhưng khách muốn nhận thông báo ở hộp thư khác -> mail phải đi tới đây.
        String customerEmail = "hop-thu-rieng-" + UUID.randomUUID() + "@example.com";
        Map<?, ?> r = requestRefund(order.get("id"), ids, UUID.randomUUID().toString(),
                Map.of("bin", "970422", "accountNumber", "1234567891"), customerEmail).getBody();
        UUID refundId = UUID.fromString((String) r.get("id"));
        assertEquals("MANUAL_REVIEW", r.get("status"), String.valueOf(r));

        assertEquals(200, resolveAsOrganizer(btc, refundId,
                Map.of("outcome", "CANCELLED", "note", "ghi chu noi bo, khach khong duoc thay"))
                .getStatusCode().value());

        assertEquals("FAILED", refund(refundId).get("status"));
        assertEquals("CANCELLED_BY_ORGANIZER", refund(refundId).get("failureCode"));
        ids.forEach(id -> assertEquals("ACTIVE", ticketStatus(id), "mail nói vé còn dùng được thì vé phải thật sự ACTIVE"));

        ArgumentCaptor<CustomerRefundMailInfo> sent = ArgumentCaptor.forClass(CustomerRefundMailInfo.class);
        verify(mailer, times(1)).sendRefundCancelledToCustomer(eq(customerEmail), sent.capture());
        assertEquals(((Number) order.get("orderCode")).longValue(), sent.getValue().orderCode());
        assertEquals(400_000, sent.getValue().amount(), "số tiền của yêu cầu đã hủy");
        assertEquals(2, sent.getValue().ticketCount());
        verify(mailer, never()).sendRefundCancelledToCustomer(eq("a@example.com"), any());

        // Bấm hủy lần hai: 409 vì guard trạng thái chạy TRƯỚC handler (refund đã FAILED) -> không có mail thứ hai.
        assertEquals(409, resolveAsOrganizer(btc, refundId,
                Map.of("outcome", "CANCELLED", "note", "hủy lần hai")).getStatusCode().value());
        verify(mailer, times(1)).sendRefundCancelledToCustomer(eq(customerEmail), any());
        assertEquals(8, available(t), "kho vẫn trừ 2 vé đã bán: hủy hoàn tiền KHÔNG cộng kho lại");
    }

    /**
     * Ca quan trọng nhất: SMTP chết thì việc HỦY vẫn phải thành công. Hủy là thao tác về vé/trạng thái đơn
     * (vé về ACTIVE, refund về FAILED) và nó đã commit xong trước khi mail được gửi — để exception của mail
     * bay ra là BTC nhận 500 cho một việc đã làm xong, rồi bấm lại và hoang mang vì thấy 409.
     *
     * <p>Có HAI lớp chắn cùng bắt RuntimeException: RefundNotifier tự bọc try/catch, và RefundService bọc
     * thêm sendMailQuietly. Test này đi qua cả hai.
     */
    @Test
    @SuppressWarnings("unchecked")
    void cancelStillSucceedsWhenSendingMailBlowsUp() {
        doThrow(new RuntimeException("SMTP chết")).when(mailer).sendRefundCancelledToCustomer(any(), any());

        String btcEmail = "btc-" + UUID.randomUUID() + "@example.com";
        String btc = registerOrganizer(btcEmail);
        Event e = event(Instant.now().plusSeconds(10 * 86_400), organizerId(btcEmail));
        TicketTier t = tier(e, 10, 200_000);
        Map<?, ?> order = paidOrder(e, t, 1);
        List<String> ids = ticketIds(order);
        Map<?, ?> r = requestRefund(order.get("id"), ids, UUID.randomUUID().toString(),
                Map.of("bin", "970422", "accountNumber", "1234567891")).getBody();
        UUID refundId = UUID.fromString((String) r.get("id"));

        ResponseEntity<Map> res = resolveAsOrganizer(btc, refundId,
                Map.of("outcome", "CANCELLED", "note", "khach doi y"));

        assertEquals(200, res.getStatusCode().value(), String.valueOf(res.getBody()));
        assertEquals("FAILED", refund(refundId).get("status"), "hủy vẫn phải chốt xong dù mail lỗi");
        assertEquals("CANCELLED_BY_ORGANIZER", refund(refundId).get("failureCode"));
        assertEquals("ACTIVE", ticketStatus(ids.get(0)), "vé vẫn phải được nhả về cho khách");
        assertEquals(9, available(t), "kho không lệch");
        verify(mailer, times(1)).sendRefundCancelledToCustomer(eq(CONTACT_EMAIL), any());
    }
}
