package com.example.demo.domain.common;

import org.springframework.http.HttpStatus;

import java.util.List;

public class DomainException extends RuntimeException {

    public record FieldError(String field, String message) {}

    private final HttpStatus status;
    private final String code;
    private List<FieldError> errors = List.of();

    public DomainException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public static DomainException notFound(String code, String message) {
        return new DomainException(HttpStatus.NOT_FOUND, code, message);
    }

    public static DomainException conflict(String code, String message) {
        return new DomainException(HttpStatus.CONFLICT, code, message);
    }

    public static DomainException badRequest(String code, String message) {
        return new DomainException(HttpStatus.BAD_REQUEST, code, message);
    }

    public static DomainException unauthorized(String code, String message) {
        return new DomainException(HttpStatus.UNAUTHORIZED, code, message);
    }

    public static DomainException forbidden(String code, String message) {
        return new DomainException(HttpStatus.FORBIDDEN, code, message);
    }

    public static DomainException gone(String code, String message) {
        return new DomainException(HttpStatus.GONE, code, message);
    }

    public static DomainException invalid(String field, String message) {
        return badRequest("VALIDATION", message).withError(field, message);
    }

    public DomainException withError(String field, String message) {
        return withErrors(List.of(new FieldError(field, message)));
    }

    public DomainException withErrors(List<FieldError> errors) {
        this.errors = List.copyOf(errors);
        return this;
    }

    public HttpStatus getStatus() { return status; }

    public String getCode() { return code; }

    public List<FieldError> getErrors() { return errors; }
}
