package com.example.demo.application.dto;

import java.util.UUID;

public record TierResponse(UUID id, String name, String description, long price, int available, int maxPerOrder) {}
