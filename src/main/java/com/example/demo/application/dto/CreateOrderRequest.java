package com.example.demo.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

public record CreateOrderRequest(
        @NotNull UUID eventId,
        @Schema(description = "Ít nhất một dòng; cùng tierId được gộp lại") List<Item> items,
        @NotNull @Valid CustomerInfo customer
) {
    public record Item(@NotNull UUID tierId, int quantity) {}
}
