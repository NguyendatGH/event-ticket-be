package com.example.demo.application.support;

import com.example.demo.application.dto.PageResponse;
import org.springframework.data.domain.PageRequest;

import java.util.List;

/**
 * Quy ước phân trang chung (ui-api-contract §1): page đếm từ 0, size tối đa 50.
 * Có HAI cách xử lý size lạ (&lt; 1), giữ nguyên vì API đang chạy như vậy (FE luôn gửi size hợp lệ nên không thấy khác):
 * <ul>
 *   <li>{@link #size}: size &lt; 1 → mặc định 12. Dùng cho vé của tôi, đơn của tôi.</li>
 *   <li>{@link #clampSize}: size &lt; 1 → 1. Dùng cho danh sách sự kiện (công khai và của BTC).</li>
 * </ul>
 */
public final class Pages {

    public static final int DEFAULT_SIZE = 12;
    public static final int MAX_SIZE = 50;

    private Pages() {}

    /** Trang âm coi như trang 0. */
    public static int page(int page) {
        return Math.max(page, 0);
    }

    public static int size(int size) {
        return size < 1 ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);
    }

    public static int clampSize(int size) {
        return Math.clamp(size, 1, MAX_SIZE);
    }

    /** Số dòng bỏ qua (OFFSET) cho trang {@code page}. */
    public static long offset(int page, int size) {
        return (long) page * size;
    }

    /** Cho repository Spring Data (vd OrderRepository.findAllByUserIdOrderByCreatedAtDesc). */
    public static PageRequest of(int page, int size) {
        return PageRequest.of(page(page), size(size));
    }

    /** Đóng gói một trang đọc bằng JdbcClient; totalPages = làm tròn lên total / size. */
    public static <T> PageResponse<T> response(List<T> content, int page, int size, long total) {
        return new PageResponse<>(content, page, size, total, (int) ((total + size - 1) / size));
    }
}
