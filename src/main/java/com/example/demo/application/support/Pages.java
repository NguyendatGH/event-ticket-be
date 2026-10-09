package com.example.demo.application.support;

import com.example.demo.application.dto.PageResponse;
import org.springframework.data.domain.PageRequest;

import java.util.List;

public final class Pages {

    public static final int DEFAULT_SIZE = 12;
    public static final int MAX_SIZE = 50;

    private Pages() {}

    public static int page(int page) {
        return Math.max(page, 0);
    }

    public static int size(int size) {
        return size < 1 ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);
    }

    public static int clampSize(int size) {
        return Math.clamp(size, 1, MAX_SIZE);
    }

    public static long offset(int page, int size) {
        return (long) page * size;
    }

    public static PageRequest of(int page, int size) {
        return PageRequest.of(page(page), size(size));
    }

    public static <T> PageResponse<T> response(List<T> content, int page, int size, long total) {
        return new PageResponse<>(content, page, size, total, (int) ((total + size - 1) / size));
    }
}
