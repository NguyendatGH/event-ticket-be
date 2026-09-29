package com.example.demo.application.dto;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.nio.charset.StandardCharsets;

/**
 * BCrypt giới hạn 72 BYTE (UTF-8), không phải 72 ký tự: chữ có dấu tốn 2-3 byte nên
 * {@code @Size(max = 72)} vẫn lọt và BCryptPasswordEncoder ném IllegalArgumentException.
 * Chặn sớm ở đây để trả 400 VALIDATION đúng field.
 */
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = PasswordBytes.Validator.class)
public @interface PasswordBytes {
    int MAX = 72;

    String message() default "quá dài: tối đa 72 byte (chữ có dấu tính 2-3 byte)";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<PasswordBytes, String> {
        @Override
        public boolean isValid(String value, ConstraintValidatorContext ctx) {
            return value == null || value.getBytes(StandardCharsets.UTF_8).length <= MAX;
        }
    }
}
