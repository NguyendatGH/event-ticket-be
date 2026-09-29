package com.example.demo.infrastructure.gateway.payos;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.demo.domain.common.DomainException;
import com.example.demo.domain.payment.InvalidWebhookSignatureException;
import com.example.demo.domain.payment.PaymentEvent;
import com.example.demo.domain.payment.PaymentProvider;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.core.env.MapPropertySource;
import vn.payos.PayOS;
import vn.payos.crypto.CryptoProviderImpl;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Không mạng: ký payload bằng chính thuật toán của SDK rồi verify qua adapter. */
class PayOsPaymentGatewayTest {

    private static final String CHECKSUM = "test-checksum-key";
    private final PayOsPaymentGateway gw = new PayOsPaymentGateway(new PayOS("cid", "akey", CHECKSUM), "");
    private final ObjectMapper json = new ObjectMapper();

    private Map<String, Object> data() {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("orderCode", 1790267092652192L);
        d.put("amount", 6512000);
        d.put("description", "VE SU KIEN");
        d.put("accountNumber", "0123456789");
        d.put("reference", "FT26267ABC");
        d.put("transactionDateTime", "2026-09-24 23:45:10");
        d.put("currency", "VND");
        d.put("paymentLinkId", "abc123linkid");
        d.put("code", "00");
        d.put("desc", "success");
        d.put("counterAccountBankId", "970422");
        d.put("counterAccountBankName", "MB");
        d.put("counterAccountName", "NGUYEN VAN A");
        d.put("counterAccountNumber", "9876543210");
        d.put("virtualAccountName", null);
        d.put("virtualAccountNumber", null);
        return d;
    }

    private String body(Map<String, Object> data, String signature) throws Exception {
        return json.writeValueAsString(Map.of("code", "00", "desc", "success", "success", true, "data", data, "signature", signature));
    }

    @Test
    void verifiesSignatureAndMapsFields() throws Exception {
        Map<String, Object> data = data();
        String raw = body(data, new CryptoProviderImpl().createSignatureFromObj(data, CHECKSUM));

        PaymentEvent ev = gw.verifyAndParse(raw, Map.of());

        assertEquals("abc123linkid:FT26267ABC", ev.eventId());
        assertEquals("abc123linkid", ev.providerPaymentId());
        assertEquals(1790267092652192L, ev.orderCode());
        assertTrue(ev.success());
        assertEquals(6512000L, ev.amount());
        assertEquals("FT26267ABC", ev.transactionRef());
        assertEquals(Instant.parse("2026-09-24T16:45:10Z"), ev.paidAt());   // 23:45:10 giờ VN
        assertEquals("970422", ev.payerBankBin());
        assertEquals("9876543210", ev.payerAccountNumber());
        assertSame(raw, ev.rawPayload());
    }

    @Test
    void rejectsTamperedPayloadAndGarbage() throws Exception {
        Map<String, Object> data = data();
        String sig = new CryptoProviderImpl().createSignatureFromObj(data, CHECKSUM);
        data.put("amount", 1000);   // đổi số tiền sau khi ký

        assertThrows(InvalidWebhookSignatureException.class, () -> gw.verifyAndParse(body(data, sig), Map.of()));
        assertThrows(DomainException.class, () -> gw.verifyAndParse("{\"hello\":1}", Map.of()));
    }

    @Test
    void truncatesDescriptionTo25Chars() {
        assertEquals("1234567890123456789012345", PayOsPaymentGateway.truncate("1234567890123456789012345XYZ"));
        assertEquals("short", PayOsPaymentGateway.truncate("short"));
    }

    /**
     * Không test nào khác load context với PayOS bật (@Profile("!test")), nên lỗi khiến Spring không dựng
     * được bean này chỉ lộ ra lúc chạy thật. Dựng bean đúng kiểu Spring làm: đọc @Value, chọn constructor,
     * và nối cả kênh chi (PayOsPayoutClient) — đổi bean graph mà quên chỗ nào thì test này đỏ.
     */
    @Test
    void springBuildsTheBeanFromProperties() {
        try (var ctx = new AnnotationConfigApplicationContext()) {
            ctx.getEnvironment().getPropertySources().addFirst(new MapPropertySource("payos", Map.of(
                    "app.payos.client-id", "cid",
                    "app.payos.api-key", "akey",
                    "app.payos.checksum-key", CHECKSUM)));
            ctx.register(PropertySourcesPlaceholderConfigurer.class, PayOsPayoutClient.class, PayOsPaymentGateway.class);
            ctx.refresh();

            assertEquals(PaymentProvider.PAYOS, ctx.getBean(PayOsPaymentGateway.class).provider());
        }
    }

    /**
     * Hai dạng transactionDateTime PayOS thật sự trả về. Chuỗi ISO là bản ghi thật của đơn
     * 1790667702053748 lấy từ GET /v2/payment-requests; trước bản vá nó ném và PaymentServiceImpl
     * đọc thành "chưa trả" rồi hủy oan đơn đã thanh toán.
     */
    @Test
    void parseTime_nhan_ca_dang_webhook_va_dang_iso_cua_rest() {
        assertEquals(Instant.parse("2026-09-24T16:45:10Z"),
                PayOsPaymentGateway.parseTime("2026-09-24 23:45:10"), "dạng webhook, giờ VN không offset");
        assertEquals(Instant.parse("2026-09-29T07:41:55Z"),
                PayOsPaymentGateway.parseTime("2026-09-29T14:41:55+07:00"), "dạng ISO có offset của REST");
        assertNull(PayOsPaymentGateway.parseTime(null));
        assertNull(PayOsPaymentGateway.parseTime("   "));
    }
}
