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
import com.example.demo.infrastructure.persistence.EventRepository;
import com.example.demo.infrastructure.persistence.InventoryRepository;
import com.example.demo.infrastructure.persistence.RefundRepository;
import com.example.demo.infrastructure.persistence.TicketTierRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        return events.save(Event.builder().slug("ev-" + UUID.randomUUID()).name("Refund test").category("music")
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

    @SuppressWarnings("unchecked")
    private ResponseEntity<Map> requestRefund(Object orderId, List<String> ticketIds, String key, Map<String, String> destination) {
        Map<String, Object> body = destination == null
                ? Map.of("ticketIds", ticketIds, "reason", "khách đổi ý")
                : Map.of("ticketIds", ticketIds, "reason", "khách đổi ý", "destination", destination);
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
    void refundInstructionGivesAdminAScannableVietQr() {
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
    void otherDestinationWaitsForAdminThenRunsOnRetry() {
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
        refundService.resolve(refundId, new ResolveRefundRequest(ResolveRefundRequest.Outcome.RETRY, "admin duyệt", null));

        Map<?, ?> after = refund(refundId);
        assertEquals("FAILED", after.get("status"));
        assertEquals("INVALID_DESTINATION", after.get("failureCode"));
        assertEquals(9, available(t), "provider từ chối thì tiền chưa đi, kho giữ nguyên");
    }

    @Test
    void adminCannotRetryWhenProviderAlreadyHasTheOrder() {
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

        // Đường đúng: admin tra dashboard rồi chốt tay
        refundService.resolve(refundId, new ResolveRefundRequest(ResolveRefundRequest.Outcome.SUCCEEDED, "đã thấy tiền đi", null));
        assertEquals("SUCCEEDED", refund(refundId).get("status"));
        assertEquals(10, available(t));
    }

    /* ---------- phải là chủ đơn mới hoàn được ---------- */

    @Test
    void guestCannotRequestRefund() {
        Event e = event();
        TicketTier t = tier(e, 10, 200_000);
        Map<?, ?> order = paidOrder(e, t, 1);

        ResponseEntity<Map> res = anon().post().uri("/api/v1/orders/" + order.get("id") + "/refunds")
                .headers(h -> h.set("Idempotency-Key", UUID.randomUUID().toString()))
                .body(Map.of("ticketIds", ticketIds(order))).retrieve().toEntity(Map.class);

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
                .body(Map.of("ticketIds", ticketIds(order))).retrieve().toEntity(Map.class);

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
}
