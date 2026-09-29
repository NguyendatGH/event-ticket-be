package com.example.demo.application.dto;

import java.util.List;

/** Bộ lọc gợi ý trên tập sự kiện đang liệt kê. price tính trên giá thấp nhất của mỗi sự kiện (cùng cột lọc priceMin/priceMax). */
public record EventFacets(List<CategoryCount> categories, List<CityCount> cities, PriceRange price) {

    public record CategoryCount(String slug, long count) {}

    public record CityCount(String name, long count) {}

    public record PriceRange(long min, long max) {}
}
