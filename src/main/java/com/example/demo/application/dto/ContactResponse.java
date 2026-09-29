package com.example.demo.application.dto;

import java.time.Instant;
import java.util.UUID;

public record ContactResponse(UUID id, Instant createdAt) {}
