package com.example.demo.infrastructure.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import com.example.demo.domain.common.LogContext;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LogPrefixConverterTest {

    private static final LogPrefixConverter CONVERTER = new LogPrefixConverter();

    @Test
    void buildsGatewayStylePrefixAndFillsMissingCodesWithDash() {
        assertThat(convert(Map.of())).as("ngoài mọi luồng vẫn in đủ khung như gateway").isEqualTo("[BACKEND][-------] ");
        assertThat(convert(Map.of("source", "MOCK", "merNo", "MerNo000008", "terNo", "TerNo000018",
                "tradeNo", "TradeNo000003", "orderNo", "1791367931543248")))
                .isEqualTo("[MOCK][MerNo000008-TerNo000018-TradeNo000003-1791367931543248] ");
        assertThat(convert(Map.of("source", "PAYOS", "orderNo", "42", "refundId", "r-1", "trace_id", "t-1")))
                .as("PayOS không có merNo/terNo; refundId và trace nằm sau phần chuẩn")
                .isEqualTo("[PAYOS][------42][refundId=r-1][trace=t-1] ");
    }

    @Test
    void nestedScopeRestoresOuterContext() {
        UUID refundId = UUID.randomUUID();
        try (LogContext.Scope outer = LogContext.order("PAYOS", 42)) {
            try (LogContext.Scope inner = LogContext.refund("PAYOS", refundId, "TradeNo000009")) {
                assertThat(MDC.get(LogContext.REFUND_ID)).isEqualTo(refundId.toString());
                assertThat(MDC.get(LogContext.TRADE_NO)).isEqualTo("TradeNo000009");
                assertThat(MDC.get(LogContext.ORDER_NO)).as("scope con không mang orderNo").isNull();
            }
            assertThat(MDC.get(LogContext.ORDER_NO)).as("đóng scope con phải trả lại orderNo").isEqualTo("42");
            assertThat(MDC.get(LogContext.REFUND_ID)).isNull();
            assertThat(MDC.get(LogContext.TRADE_NO)).isNull();
        }
        assertThat(MDC.get(LogContext.SOURCE)).as("đóng scope ngoài phải sạch MDC").isNull();
        assertThat(MDC.get(LogContext.ORDER_NO)).isNull();
    }

    @Test
    void withTradeAddsCodesOnlyForItsBlock() {
        try (LogContext.Scope order = LogContext.order("MOCK", 42)) {
            LogContext.trade("MerNo000001", "TerNo000001", null);
            try (LogContext.Scope call = LogContext.withTrade(null, null, "TradeNo000001")) {
                assertThat(MDC.get(LogContext.MER_NO)).as("null = giữ giá trị đang có").isEqualTo("MerNo000001");
                assertThat(MDC.get(LogContext.TRADE_NO)).isEqualTo("TradeNo000001");
                assertThat(MDC.get(LogContext.ORDER_NO)).isEqualTo("42");
            }
            assertThat(MDC.get(LogContext.TRADE_NO)).isNull();
            assertThat(MDC.get(LogContext.MER_NO)).isEqualTo("MerNo000001");
        }
        assertThat(MDC.get(LogContext.MER_NO)).isNull();
    }

    private static String convert(Map<String, String> mdc) {
        LoggingEvent event = new LoggingEvent("x", new LoggerContext().getLogger("test"), Level.INFO, "msg", null, null);
        event.setMDCPropertyMap(mdc);
        return CONVERTER.convert(event);
    }
}
