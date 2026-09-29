package com.example.demo.application.impl;

import com.example.demo.application.GatewayAudit;
import com.example.demo.domain.payment.GatewayCallLog;
import com.example.demo.infrastructure.persistence.GatewayCallLogRepository;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.UUID;


@Component
public class GatewayAuditImpl implements GatewayAudit {

    private final GatewayCallLogRepository logs;
    private final ObjectMapper json;

    public GatewayAuditImpl(GatewayCallLogRepository logs, ObjectMapper json) {
        this.logs = logs;
        this.json = json;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(UUID orderId, String direction, String endpoint, Object request, Object response,
                       Integer httpStatus, long durationMs) {
        logs.save(new GatewayCallLog(orderId, direction, endpoint, toJson(request), toJson(response),
                httpStatus, (int) durationMs, MDC.get("trace_id")));
    }

    private String toJson(Object o) {
        if (o == null) return null;
        return o instanceof String s ? jsonOrWrapped(s) : json.writeValueAsString(o);
    }

    /** Cột jsonb không nhận text thường: body lạ (webhook sai chữ ký) được bọc thành {"text": ...}. */
    @Override
    public String jsonOrWrapped(String raw) {
        try {
            json.readTree(raw);
            return raw;
        } catch (RuntimeException notJson) {
            return json.writeValueAsString(Map.of("text", raw));
        }
    }
}
