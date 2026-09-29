package com.example.demo.infrastructure.web;

import com.example.demo.domain.common.DomainException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.sql.SQLException;
import java.util.Map;

/**
 * Mọi lỗi trả về application/problem+json (RFC 9457) kèm hai trường mở rộng mà FE đọc
 * (fe/src/api/errors.js): {@code code} mã máy đọc và {@code traceId} để tra log.
 * Lỗi validation (và DomainException có withErrors) thêm {@code errors: [{field, message}]}.
 * Kế thừa ResponseEntityExceptionHandler để Boot tắt ProblemDetailsExceptionHandler mặc định
 * và các lỗi MVC chuẩn (400, 404, 405, 413, 415...) cũng đi qua {@link #createResponseEntity}.
 * 401/403 từ filter của Spring Security không tới đây, xem SecurityConfig. AccessDeniedException từ
 * {@code @PreAuthorize} cố ý KHÔNG bắt ở đây: để nó ra tới ExceptionTranslationFilter, chưa đăng nhập → 401,
 * đã đăng nhập → 403 FORBIDDEN, cả hai cùng body problem+json của SecurityConfig.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    static ProblemDetail problem(HttpStatusCode status, String code, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setProperty("code", code);
        pd.setProperty("traceId", MDC.get(TraceIdFilter.MDC_KEY));
        return pd;
    }

    @ExceptionHandler(DomainException.class)
    ProblemDetail domain(DomainException e) {
        ProblemDetail pd = problem(e.getStatus(), e.getCode(), e.getMessage());
        if (!e.getErrors().isEmpty()) pd.setProperty("errors", e.getErrors());
        return pd;
    }

    @ExceptionHandler(BadCredentialsException.class)
    ProblemDetail badCredentials() {
        // Không nói rõ sai email hay sai password để tránh lộ email nào đã đăng ký
        return problem(HttpStatus.UNAUTHORIZED, "BAD_CREDENTIALS", "Email hoặc mật khẩu không đúng");
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ProblemDetail missingHeader(MissingRequestHeaderException e) {
        return problem(HttpStatus.BAD_REQUEST, "MISSING_HEADER", "Thiếu header " + e.getHeaderName());
    }

    /** Để Spring Security tự xử lý (401/403 problem+json ở SecurityConfig), không rơi vào catch-all bên dưới. */
    @ExceptionHandler({AccessDeniedException.class, AuthenticationException.class})
    void rethrowSecurity(RuntimeException e) {
        throw e;
    }

    /**
     * Vi phạm unique do hai request chạy đua (đăng ký trùng email, bấm Thanh toán hai lần cùng Idempotency-Key):
     * kiểm tra trước trong service đã qua nhưng DB chặn. Trả 409 để FE báo thử lại. Vi phạm khác (NOT NULL, FK) là bug → 500.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail dataIntegrity(DataIntegrityViolationException e) {
        if (e.getMostSpecificCause() instanceof SQLException sql && "23505".equals(sql.getSQLState())) {
            log.warn("Trùng unique do request đồng thời: {}", sql.getMessage());
            return problem(HttpStatus.CONFLICT, "CONFLICT", "Yêu cầu trùng với một yêu cầu khác vừa xử lý, vui lòng tải lại rồi thử lại");
        }
        return unexpected(e);
    }

    /** Lưới cuối: mọi lỗi chưa lường trước là 500 problem+json có traceId, không lộ stack trace ra client. */
    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception e) {
        log.error("Lỗi không mong đợi", e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Hệ thống gặp lỗi, vui lòng thử lại sau");
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex, HttpHeaders headers,
                                                                  HttpStatusCode status, WebRequest request) {
        ProblemDetail pd = problem(status, "VALIDATION", "Dữ liệu gửi lên không hợp lệ");
        pd.setProperty("errors", ex.getBindingResult().getFieldErrors().stream()
                .map(f -> Map.of("field", f.getField(), "message", String.valueOf(f.getDefaultMessage())))
                .toList());
        return handleExceptionInternal(ex, pd, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleMaxUploadSizeExceededException(MaxUploadSizeExceededException ex, HttpHeaders headers,
                                                                          HttpStatusCode status, WebRequest request) {
        ProblemDetail pd = problem(HttpStatus.CONTENT_TOO_LARGE, "FILE_TOO_LARGE", "File vượt quá dung lượng cho phép (tối đa 5MB)");
        return handleExceptionInternal(ex, pd, headers, HttpStatus.CONTENT_TOO_LARGE, request);
    }

    /** Điểm ra chung của lớp cha: bảo đảm mọi ProblemDetail đều có code và traceId. */
    @Override
    protected ResponseEntity<Object> createResponseEntity(Object body, HttpHeaders headers, HttpStatusCode status,
                                                          WebRequest request) {
        if (body instanceof ProblemDetail pd) {
            if (pd.getProperties() == null || !pd.getProperties().containsKey("code")) {
                pd.setProperty("code", status instanceof HttpStatus hs ? hs.name() : "HTTP_" + status.value());
            }
            pd.setProperty("traceId", MDC.get(TraceIdFilter.MDC_KEY));
        }
        return super.createResponseEntity(body, headers, status, request);
    }
}
