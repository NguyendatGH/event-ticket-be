package com.example.demo.application.support;

import java.util.Optional;
import java.util.UUID;

/** Tiện ích chuỗi nhỏ dùng chung cho các service (đọc input người dùng). */
public final class Texts {

    private Texts() {}

    /** Field tùy chọn: null, "" hoặc toàn khoảng trắng coi như bỏ trống (null); còn lại thì trim. */
    public static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /** URL nhận cả UUID lẫn slug: chuỗi không phải UUID thì trả empty thay vì ném lỗi. */
    public static Optional<UUID> parseUuid(String s) {
        try {
            return Optional.of(UUID.fromString(s));
        } catch (IllegalArgumentException notUuid) {
            return Optional.empty();
        }
    }

    /**
     * Mẫu "chứa" cho LIKE/ILIKE: "abc" → "%abc%". Ký tự đặc biệt của LIKE ('%', '_', '\') người dùng gõ
     * được escape để hiểu theo nghĩa đen (gõ "50%" tìm đúng chuỗi "50%", không phải "50 + bất kỳ").
     */
    public static String likePattern(String q) {
        return "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }
}
