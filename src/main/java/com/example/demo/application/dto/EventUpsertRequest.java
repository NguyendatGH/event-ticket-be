package com.example.demo.application.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Tạo/sửa sự kiện của BTC (ui-api-contract §4.4). Lưu nháp chỉ bắt buộc name; field có mặt thì phải hợp lệ.
 * Đủ/thiếu để publish kiểm tra ở Event.publishProblems. {@code tiers} là tập đầy đủ (tier cũ vắng mặt = xóa);
 * null = giữ nguyên tiers.
 */
public record EventUpsertRequest(
        @NotBlank(message = "Tên sự kiện không được trống") @Size(max = 200) String name,
        @Pattern(regexp = "music|theatre|sport|conference|exhibition|workshop", message = "Danh mục không hợp lệ") String category,
        @Size(max = 300) String tagline,
        @Size(max = 50) List<@Size(max = 5000) String> description,
        @Size(max = 1000) String coverImageUrl,
        @Size(max = 300) String coverImageAlt,
        Instant startsAt,
        Instant endsAt,
        @Valid VenueInput venue,
        @Size(max = 50) List<@Valid ScheduleInput> schedule,
        @Size(max = 20) List<@Valid TierInput> tiers
) {
    public record VenueInput(@Size(max = 200) String name, @Size(max = 100) String city, @Size(max = 500) String address) {}

    public record ScheduleInput(
            @NotBlank @Pattern(regexp = "([01]\\d|2[0-3]):[0-5]\\d", message = "Giờ phải dạng HH:mm") String time,
            @NotBlank @Size(max = 200) String title) {}

    /** id null = tier mới. price/totalQuantity trống coi là 0, maxPerOrder trống là 6 (mặc định DB). */
    public record TierInput(
            UUID id,
            @NotBlank(message = "Tên hạng vé không được trống") @Size(max = 200) String name,
            @Size(max = 500) String description,
            @Min(0) Long price,
            @Min(0) @Max(1_000_000) Integer totalQuantity,
            @Min(1) @Max(20) Integer maxPerOrder) {}
}
