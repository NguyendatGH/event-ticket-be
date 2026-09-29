package com.example.demo.infrastructure.logging;

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.example.demo.domain.common.LogContext;
import com.example.demo.infrastructure.web.TraceIdFilter;

import java.util.Map;

/** {@code %logPrefix}: dựng {@code [BACKEND][PAYOS][orderCode=...][trace=...] } từ MDC, thiếu key nào bỏ đoạn đó. */
public class LogPrefixConverter extends ClassicConverter {

    @Override
    public String convert(ILoggingEvent event) {
        Map<String, String> mdc = event.getMDCPropertyMap();
        StringBuilder sb = new StringBuilder(64);
        append(sb, "", mdc.get(LogContext.SOURCE));
        append(sb, "", mdc.get(LogContext.PROVIDER));
        append(sb, "orderCode=", mdc.get(LogContext.ORDER_CODE));
        append(sb, "refundId=", mdc.get(LogContext.REFUND_ID));
        append(sb, "trace=", mdc.get(TraceIdFilter.MDC_KEY));
        return sb.isEmpty() ? "" : sb.append(' ').toString();
    }

    private static void append(StringBuilder sb, String label, String value) {
        if (value != null) sb.append('[').append(label).append(value).append(']');
    }
}
