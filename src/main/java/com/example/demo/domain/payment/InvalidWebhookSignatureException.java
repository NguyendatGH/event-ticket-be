package com.example.demo.domain.payment;

import com.example.demo.domain.common.DomainException;
import org.springframework.http.HttpStatus;

public class InvalidWebhookSignatureException extends DomainException {
    public InvalidWebhookSignatureException(String message) {
        super(HttpStatus.UNAUTHORIZED, "INVALID_SIGNATURE", message);
    }
}
