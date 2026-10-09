package com.example.demo.application.dto;

import java.util.List;

public record EventFacets(List<CategoryCount> categories, List<CityCount> cities, PriceRange price) {

    public record CategoryCount(String slug, long count) {}

    public record CityCount(String name, long count) {}

    public record PriceRange(long min, long max) {}
}
