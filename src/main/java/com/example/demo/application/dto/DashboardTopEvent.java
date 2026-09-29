package com.example.demo.application.dto;

import java.time.Instant;
import java.util.UUID;

/** Một dòng của GET /organizer/dashboard/top-events; {@code status} là trạng thái hiển thị (có ENDED, SOLD_OUT). */
public record DashboardTopEvent(UUID id, String slug, String name, Instant startsAt, String status,
                                long ticketsSold, long revenue) {}
