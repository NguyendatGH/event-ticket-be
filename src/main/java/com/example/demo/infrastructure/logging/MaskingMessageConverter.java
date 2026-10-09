package com.example.demo.infrastructure.logging;

import ch.qos.logback.classic.pattern.MessageConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

import java.util.regex.Pattern;

public class MaskingMessageConverter extends MessageConverter {

    private static final Pattern BEARER = Pattern.compile("(?i)(Bearer\\s+)[A-Za-z0-9._~+/=-]+");
    private static final Pattern SECRET = Pattern.compile(
            "(?i)((?:api_?key|checksum_?key|client_?id|password|secret)\"?\\s*[:=]\\s*\"?)([^\\s\",;}]+)");
    private static final Pattern EMAIL = Pattern.compile("([A-Za-z0-9._%+-]{1,2})[A-Za-z0-9._%+-]*(@[A-Za-z0-9.-]+\\.[A-Za-z]{2,})");
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
