package com.example.demo.infrastructure.logging;

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.example.demo.domain.common.LogContext;

import java.util.Map;

/**
 * In tiền tố ngữ cảnh từ MDC: {@code [BACKEND][PAYOS][orderCode=ORD-...] }. Đăng ký làm conversion word
 * {@code %logPrefix} trong logback-spring.xml, đặt ngay trước {@code %maskedMsg}.
 *
 * <p>Không set MDC thì trả về chuỗi rỗng, nên log thường (startup, SQL, ...) không bị thêm {@code [][]}.
 * Đọc qua {@link ILoggingEvent#getMDCPropertyMap()} chứ không đọc thẳng MDC để vẫn đúng khi dùng async appender.
 */
public class LogPrefixConverter extends ClassicConverter {

    @Override
    public String convert(ILoggingEvent event) {
        Map<String, String> mdc = event.getMDCPropertyMap();
        String source = mdc.get(LogContext.SOURCE);
        String provider = mdc.get(LogContext.PROVIDER);
        String orderCode = mdc.get(LogContext.ORDER_CODE);
        if (source == null && provider == null && orderCode == null) return "";

        StringBuilder sb = new StringBuilder(48);
        if (source != null) sb.append('[').append(source).append(']');
        if (provider != null) sb.append('[').append(provider).append(']');
        if (orderCode != null) sb.append("[orderCode=").append(orderCode).append(']');
        return sb.append(' ').toString();
    }
}
