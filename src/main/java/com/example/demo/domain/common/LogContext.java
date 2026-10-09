package com.example.demo.domain.common;

import org.slf4j.MDC;

import java.util.Locale;
import java.util.UUID;

public final class LogContext {

    public static final String BACKEND = "BACKEND";

    public static final String SOURCE = "source";
    public static final String MER_NO = "merNo";
    public static final String TER_NO = "terNo";
    public static final String TRADE_NO = "tradeNo";
    public static final String ORDER_NO = "orderNo";
    public static final String REFUND_ID = "refundId";

    private static final String[] KEYS = {SOURCE, MER_NO, TER_NO, TRADE_NO, ORDER_NO, REFUND_ID};

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

    public static Scope refund(String provider, UUID refundId, String providerRefundId) {
        Scope scope = refund(provider, refundId);
        trade(null, null, providerRefundId);
        return scope;
    }

    public static Scope withTrade(String merNo, String terNo, String tradeNo) {
        Scope restore = snapshot();
        trade(merNo, terNo, tradeNo);
        return restore;
    }

    public static void orderCode(long orderCode) {
        put(ORDER_NO, String.valueOf(orderCode));
    }

    public static void refundId(UUID refundId) {
        put(REFUND_ID, refundId == null ? null : refundId.toString());
    }

    public static void trade(String merNo, String terNo, String tradeNo) {
        if (merNo != null) put(MER_NO, merNo);
        if (terNo != null) put(TER_NO, terNo);
        if (tradeNo != null) put(TRADE_NO, tradeNo);
    }

    public static void clear() {
        for (String key : KEYS) MDC.remove(key);
    }

    private static Scope open(String provider, String orderNo, String refundId) {
        Scope restore = snapshot();
        put(SOURCE, provider == null ? null : provider.toUpperCase(Locale.ROOT));
        put(MER_NO, null);
        put(TER_NO, null);
        put(TRADE_NO, null);
        put(ORDER_NO, orderNo);
        put(REFUND_ID, refundId);
        return restore;
    }

    private static Scope snapshot() {
        String[] previous = new String[KEYS.length];
        for (int i = 0; i < KEYS.length; i++) previous[i] = MDC.get(KEYS[i]);
        return () -> {
            for (int i = 0; i < KEYS.length; i++) put(KEYS[i], previous[i]);
        };
    }

    private static void put(String key, String value) {
        if (value == null || value.isBlank()) MDC.remove(key);
        else MDC.put(key, value);
    }
}
