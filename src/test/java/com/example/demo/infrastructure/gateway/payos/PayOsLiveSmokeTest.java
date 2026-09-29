package com.example.demo.infrastructure.gateway.payos;

import com.example.demo.domain.payment.CreatePaymentCommand;
import com.example.demo.domain.payment.PaymentLink;
import com.example.demo.domain.payment.PaymentStatusResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import vn.payos.PayOS;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Gọi PayOS thật (chỉ tạo/đọc/hủy link, không có tiền chuyển). Tắt mặc định, bật bằng:
 *   set -a; . ./.env; set +a; PAYOS_LIVE=true ./mvnw -q test -Dtest=PayOsLiveSmokeTest
 */
@EnabledIfEnvironmentVariable(named = "PAYOS_LIVE", matches = "true")
class PayOsLiveSmokeTest {

    @Test
    void createGetCancelPaymentLink() {
        PayOsPaymentGateway gw = new PayOsPaymentGateway(new PayOS(
                System.getenv("PAYOS_CLIENT_ID"), System.getenv("PAYOS_API_KEY"), System.getenv("PAYOS_CHECKSUM_KEY")), "");
        long orderCode = System.currentTimeMillis();

        PaymentLink link = gw.createPaymentLink(new CreatePaymentCommand(orderCode, 2000, "SMOKE " + orderCode,
                List.of(new CreatePaymentCommand.Item("Ve test", 1, 2000)),
                "http://localhost:3000/checkout/return", "http://localhost:3000/checkout/return",
                Instant.now().plus(Duration.ofMinutes(5))));
        assertNotNull(link.providerPaymentId());
        assertTrue(link.checkoutUrl().startsWith("https://"));
        assertNotNull(link.qrCode());

        PaymentStatusResult status = gw.getPaymentStatus(link.providerPaymentId());
        assertEquals(PaymentStatusResult.Status.PENDING, status.status());
        assertEquals(0, status.amountPaid());

        gw.cancelPaymentLink(link.providerPaymentId(), "smoke test");
        assertEquals(PaymentStatusResult.Status.CANCELLED, gw.getPaymentStatus(link.providerPaymentId()).status());
    }
}
