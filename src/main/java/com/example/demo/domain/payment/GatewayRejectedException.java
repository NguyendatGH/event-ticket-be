package com.example.demo.domain.payment;

/**
 * Provider từ chối DỨT KHOÁT (4xx): tiền chắc chắn CHƯA đi, được phép thử lại bằng key mới.
 * code chuẩn hóa: INSUFFICIENT_PAYOUT_BALANCE, NO_PAYOUT_CREDIT, INVALID_DESTINATION, LIMIT_EXCEEDED, REJECTED, NOT_FOUND.
 */
public class GatewayRejectedException extends RuntimeException {

    private final String code;

    public GatewayRejectedException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() { return code; }
}
