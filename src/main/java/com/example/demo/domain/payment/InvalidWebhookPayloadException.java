package com.example.demo.domain.payment;

import com.example.demo.domain.common.DomainException;
import org.springframework.http.HttpStatus;

/** Webhook đúng chữ ký nhưng nội dung thiếu/sai kiểu field bắt buộc: từ chối 400 thay vì đoán giá trị mặc định. */
public class InvalidWebhookPayloadException extends DomainException {
    public InvalidWebhookPayloadException(String message) {
        super(HttpStatus.BAD_REQUEST, "INVALID_WEBHOOK_PAYLOAD", message);
    }
}
