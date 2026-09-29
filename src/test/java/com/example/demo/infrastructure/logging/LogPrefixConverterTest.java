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
    void buildsPrefixFromMdcAndSkipsMissingKeys() {
        assertThat(convert(Map.of())).isEmpty();
        assertThat(convert(Map.of("source", "BACKEND", "provider", "PAYOS", "orderCode", "42")))
                .isEqualTo("[BACKEND][PAYOS][orderCode=42] ");
        assertThat(convert(Map.of("source", "BACKEND", "provider", "PAYOS", "trace_id", "t-1")))
                .as("thiếu orderCode thì bỏ hẳn đoạn đó").isEqualTo("[BACKEND][PAYOS][trace=t-1] ");
    }

    @Test
    void nestedScopeRestoresOuterContext() {
        UUID refundId = UUID.randomUUID();
        try (LogContext.Scope outer = LogContext.order("PAYOS", 42)) {
            try (LogContext.Scope inner = LogContext.refund("PAYOS", refundId)) {
                assertThat(MDC.get(LogContext.REFUND_ID)).isEqualTo(refundId.toString());
                assertThat(MDC.get(LogContext.ORDER_CODE)).as("scope con không mang orderCode").isNull();
            }
            assertThat(MDC.get(LogContext.ORDER_CODE)).as("đóng scope con phải trả lại orderCode").isEqualTo("42");
            assertThat(MDC.get(LogContext.REFUND_ID)).isNull();
        }
        assertThat(MDC.get(LogContext.SOURCE)).as("đóng scope ngoài phải sạch MDC").isNull();
        assertThat(MDC.get(LogContext.ORDER_CODE)).isNull();
    }

    private static String convert(Map<String, String> mdc) {
        LoggingEvent event = new LoggingEvent("x", new LoggerContext().getLogger("test"), Level.INFO, "msg", null, null);
        event.setMDCPropertyMap(mdc);
        return CONVERTER.convert(event);
    }
}
