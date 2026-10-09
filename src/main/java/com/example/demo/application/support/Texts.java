package com.example.demo.application.support;

import java.util.Optional;
import java.util.UUID;

public final class Texts {

    private Texts() {}

    public static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    public static Optional<UUID> parseUuid(String s) {
        try {
            return Optional.of(UUID.fromString(s));
        } catch (IllegalArgumentException notUuid) {
            return Optional.empty();
        }
    }

    public static String likePattern(String q) {
        return "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }
}
