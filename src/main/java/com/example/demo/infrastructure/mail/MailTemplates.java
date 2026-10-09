package com.example.demo.infrastructure.mail;

import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class MailTemplates {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{(\\w+)}}");

    private static final Map<String, String> CACHE = new ConcurrentHashMap<>();

    private MailTemplates() {
    }

    static String render(String name, Map<String, String> values) {
        String template = CACHE.computeIfAbsent(name, MailTemplates::load);

        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String value = values.getOrDefault(m.group(1), "");
            m.appendReplacement(out, Matcher.quoteReplacement(escapeHtml(value)));
        }
        m.appendTail(out);
        return out.toString();
    }

    static String escapeHtml(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        return raw.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    static String formatVnd(long amount) {
        DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.ROOT);
        symbols.setGroupingSeparator('.');
        return new DecimalFormat("#,##0", symbols).format(amount) + " ₫";
    }

    private static String load(String name) {
        ClassPathResource file = new ClassPathResource("mail/" + name + ".html");
        try (InputStream in = file.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Không đọc được template mail: mail/" + name + ".html", e);
        }
    }
}
