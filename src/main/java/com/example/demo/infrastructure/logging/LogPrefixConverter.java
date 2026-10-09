package com.example.demo.infrastructure.logging;

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.example.demo.domain.common.LogContext;
import com.example.demo.infrastructure.web.TraceIdFilter;

import java.util.Map;

public class LogPrefixConverter extends ClassicConverter {

    @Override
    public String convert(ILoggingEvent event) {
        Map<String, String> mdc = event.getMDCPropertyMap();
        StringBuilder sb = new StringBuilder(128)
                .append('[').append(value(mdc, LogContext.SOURCE, LogContext.BACKEND)).append(']')
                .append('[').append(value(mdc, LogContext.MER_NO, "-"))
                .append('-').append(value(mdc, LogContext.TER_NO, "-"))
                .append('-').append(value(mdc, LogContext.TRADE_NO, "-"))
                .append('-').append(value(mdc, LogContext.ORDER_NO, "-")).append(']');
        append(sb, "refundId=", mdc.get(LogContext.REFUND_ID));
        append(sb, "trace=", mdc.get(TraceIdFilter.MDC_KEY));
        return sb.append(' ').toString();
    }

    private static String value(Map<String, String> mdc, String key, String fallback) {
        String value = mdc.get(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static void append(StringBuilder sb, String label, String value) {
        if (value != null) sb.append('[').append(label).append(value).append(']');
    }
}
