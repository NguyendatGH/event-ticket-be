package com.example.demo.domain.common;

import org.slf4j.MDC;

/**
 * Ngữ cảnh log của một luồng xử lý (checkout / webhook). Giá trị nằm trong MDC và được
 * {@code com.example.demo.infrastructure.logging.LogPrefixConverter} in ra đầu message dưới dạng
 * {@code [BACKEND][PAYOS][orderCode=ORD-...]}.
 *
 * <p>MDC là ThreadLocal: phải {@link #set} ở đầu luồng và {@link #clear} trong {@code finally},
 * nếu không giá trị sẽ dính lại trên thread của pool và log nhầm sang request khác.
 */
public final class LogContext {

    public static final String SOURCE = "source";
    public static final String PROVIDER = "provider";
    public static final String ORDER_CODE = "orderCode";

    private LogContext() {
    }

    /** orderCode có thể null khi chưa parse được payload (webhook lúc mới vào). */
    public static void set(String source, String provider, String orderCode) {
        put(SOURCE, source);
        put(PROVIDER, provider);
        put(ORDER_CODE, orderCode);
    }

    /** Bổ sung orderCode khi đã biết, giữ nguyên source/provider đã set trước đó. */
    public static void orderCode(String orderCode) {
        put(ORDER_CODE, orderCode);
    }

    public static void clear() {
        MDC.remove(SOURCE);
        MDC.remove(PROVIDER);
        MDC.remove(ORDER_CODE);
    }

    private static void put(String key, String value) {
        if (value == null) MDC.remove(key);
        else MDC.put(key, value);
    }
}
