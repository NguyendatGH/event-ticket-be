package com.example.demo.infrastructure.web;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void unexpectedErrorIs500ProblemWithCode() {
        var pd = handler.unexpected(new IllegalStateException("boom"));
        assertEquals(500, pd.getStatus());
        assertEquals("INTERNAL_ERROR", pd.getProperties().get("code"));
    }

    @Test
    void uniqueViolationIs409OtherIntegrityErrorsAre500() {
        var dup = new DataIntegrityViolationException("dup", new SQLException("duplicate key", "23505"));
        assertEquals(409, handler.dataIntegrity(dup).getStatus());
        assertEquals("CONFLICT", handler.dataIntegrity(dup).getProperties().get("code"));

        var notNull = new DataIntegrityViolationException("nn", new SQLException("null value", "23502"));
        assertEquals(500, handler.dataIntegrity(notNull).getStatus());
    }

    @Test
    void securityExceptionsAreLeftToSpringSecurity() {
        assertThrows(AccessDeniedException.class, () -> handler.rethrowSecurity(new AccessDeniedException("no")));
    }
}
