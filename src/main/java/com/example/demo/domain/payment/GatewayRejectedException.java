package com.example.demo.domain.payment;

public class GatewayRejectedException extends RuntimeException {

    private final String code;

    public GatewayRejectedException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() { return code; }
}
