package com.example.demo.infrastructure.logging;

import ch.qos.logback.classic.pattern.MessageConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

import java.util.regex.Pattern;

/**
 * Che dữ liệu nhạy cảm trong message log. Đăng ký làm conversion word
 * {@code %maskedMsg} trong logback-spring.xml thay cho {@code %m}. Chỉ che message, không che stack trace.
 */
public class MaskingMessageConverter extends MessageConverter {

    // "Authorization: Bearer eyJ..." -> "Bearer ***"
    private static final Pattern BEARER = Pattern.compile("(?i)(Bearer\\s+)[A-Za-z0-9._~+/=-]+");
    // api_key=..., "apiKey": "...", checksumKey: ..., client_id=..., password=..., secret=... -> ***
    private static final Pattern SECRET = Pattern.compile(
            "(?i)((?:api_?key|checksum_?key|client_?id|password|secret)\"?\\s*[:=]\\s*\"?)([^\\s\",;}]+)");
    // a@example.com -> a***@example.com (giữ tối đa 2 ký tự đầu)
    private static final Pattern EMAIL = Pattern.compile("([A-Za-z0-9._%+-]{1,2})[A-Za-z0-9._%+-]*(@[A-Za-z0-9.-]+\\.[A-Za-z]{2,})");
    // GIỚI HẠN: mọi dãy 10-19 chữ số coi là số tài khoản, giữ 4 số cuối. Trần: orderCode/epoch millis
    // 13 chữ số cũng bị che; nếu vướng debug thì đổi sang regex theo key (account_number=...).
    private static final Pattern ACCOUNT = Pattern.compile("(?<!\\d)(\\d{6,15})(\\d{4})(?!\\d)");

    @Override
    public String convert(ILoggingEvent event) {
        return mask(super.convert(event));
    }

    public static String mask(String s) {
        if (s == null || s.isEmpty()) return s;
        s = BEARER.matcher(s).replaceAll("$1***");
        s = SECRET.matcher(s).replaceAll("$1***");
        s = EMAIL.matcher(s).replaceAll("$1***$2");
        return ACCOUNT.matcher(s).replaceAll(m -> "*".repeat(m.group(1).length()) + m.group(2));
    }
}
