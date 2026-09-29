package com.example.demo.domain.common;

import java.text.Normalizer;
import java.util.Locale;
import java.util.function.Predicate;

public final class Slugs {
    private static final int MAX_BASE_LENGTH = 180;

    private Slugs() {

    }

    public static String slugify(String name, String fallback) {
        String s = Normalizer.normalize(name.replace('đ', 'd').replace('Đ', 'd'), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (s.length() > MAX_BASE_LENGTH) s = s.substring(0, MAX_BASE_LENGTH).replaceAll("-+$", "");
        return s.isEmpty() ? fallback : s;
    }

    public static String unique(String base, Predicate<String> taken) {
        String slug = base;
        for (int i = 2; taken.test(slug); i++) slug = base + "-" + i;
        return slug;
    }
}
