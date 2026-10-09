package com.example.demo.domain.common;

import org.slf4j.MDC;

import java.util.UUID;

public final class LogContext {

    public static final String SOURCE = "source";
    public static final String PROVIDER = "provider";
    public static final String ORDER_CODE = "orderCode";
    public static final String REFUND_ID = "refundId";

    private static final String[] KEYS = {SOURCE, PROVIDER, ORDER_CODE, REFUND_ID};

    private LogContext() {
    }

    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }

    public static Scope of(String provider) {
        return open(provider, null, null);
    }

    public static Scope order(String provider, long orderCode) {
        return open(provider, String.valueOf(orderCode), null);
    }

    public static Scope refund(String provider, UUID refundId) {
        return open(provider, null, refundId.toString());
    }

    public static void orderCode(long orderCode) {
        put(ORDER_CODE, String.valueOf(orderCode));
    }

    public static void refundId(UUID refundId) {
        put(REFUND_ID, refundId == null ? null : refundId.toString());
    }

    private static Scope open(String provider, String orderCode, String refundId) {
        String[] previous = new String[KEYS.length];
        for (int i = 0; i < KEYS.length; i++) previous[i] = MDC.get(KEYS[i]);
        put(SOURCE, "BACKEND");
        put(PROVIDER, provider);
        put(ORDER_CODE, orderCode);
        put(REFUND_ID, refundId);
        return () -> {
            for (int i = 0; i < KEYS.length; i++) put(KEYS[i], previous[i]);
        };
    }

    private static void put(String key, String value) {
        if (value == null) MDC.remove(key);
        else MDC.put(key, value);
    }
}
