package com.example.demo.domain.payment;

/**
 * KHÔNG biết provider đã nhận lệnh chưa (timeout, mất mạng, 5xx). PHẢI thử lại CÙNG key, không được sinh key mới,
 * nếu không là chi tiền hai lần. RefundRecoveryJob tra provider theo key rồi mới quyết định.
 */
public class GatewayTimeoutException extends RuntimeException {

    public GatewayTimeoutException(String message) { super(message); }

    public GatewayTimeoutException(String message, Throwable cause) { super(message, cause); }
}
