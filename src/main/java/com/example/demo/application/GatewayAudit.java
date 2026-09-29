package com.example.demo.application;

import java.util.UUID;

public interface GatewayAudit {

    void record(UUID orderId, String direction, String endpoint, Object request, Object response,
                Integer httpStatus, long durationMs);

    String jsonOrWrapped(String raw);
}
