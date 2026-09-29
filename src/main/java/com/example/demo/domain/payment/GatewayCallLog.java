package com.example.demo.domain.payment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/** Audit mọi lần gọi provider (OUTBOUND) và mọi webhook đã verify (INBOUND). Không FK để ghi được kể cả khi nghiệp vụ rollback. */
@Entity
@Table(name = "gateway_call_logs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GatewayCallLog {

    @Id
    private UUID id;

    // Hiện luôn ref ORDER để admin audit tra theo order id; ref PAYMENT/REFUND khi cần
    @Column(name = "ref_type", length = 20)
    private String refType;

    @Column(name = "ref_id")
    private UUID refId;

    @Column(nullable = false, length = 10)
    private String direction;

    @Column(nullable = false)
    private String endpoint;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "request_masked")
    private String requestMasked;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response_raw")
    private String responseRaw;

    @Column(name = "http_status")
    private Integer httpStatus;

    @Column(name = "duration_ms")
    private Integer durationMs;

    @Column(name = "trace_id", length = 64)
    private String traceId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public GatewayCallLog(UUID orderId, String direction, String endpoint, String requestJson, String responseJson,
                          Integer httpStatus, Integer durationMs, String traceId) {
        this.id = UUID.randomUUID();
        this.refType = "ORDER";
        this.refId = orderId;
        this.direction = direction;
        this.endpoint = endpoint;
        this.requestMasked = requestJson;
        this.responseRaw = responseJson;
        this.httpStatus = httpStatus;
        this.durationMs = durationMs;
        this.traceId = traceId;
        this.createdAt = Instant.now();
    }
}
