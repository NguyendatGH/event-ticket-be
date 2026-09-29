package com.example.demo.application;

import com.example.demo.domain.idempotency.IdempotencyScope;

import java.util.function.Supplier;

public interface IdempotencyService {

    <T> T execute(IdempotencyScope scope, String key, Object request, Class<T> responseType, Supplier<T> action);
}
