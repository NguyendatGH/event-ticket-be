package com.example.demo.application.dto;

import java.time.Instant;
import java.util.UUID;

public record DashboardTopEvent(UUID id, String slug, String name, Instant startsAt, String status,
                                long ticketsSold, long revenue) {}
