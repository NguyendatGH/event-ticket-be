package com.example.demo.application.impl;

import com.example.demo.application.IdempotencyService;
import com.example.demo.domain.common.DomainException;
import com.example.demo.domain.idempotency.IdempotencyRecord;
import com.example.demo.domain.idempotency.IdempotencyScope;
import com.example.demo.infrastructure.persistence.IdempotencyRecordRepository;
import com.example.demo.infrastructure.security.OpaqueTokens;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;
import java.util.function.Supplier;


@Service
public class IdempotencyServiceImpl implements IdempotencyService {

    private final IdempotencyRecordRepository records;
    private final ObjectMapper json;

    public IdempotencyServiceImpl(IdempotencyRecordRepository records, ObjectMapper json) {
        this.records = records;
        this.json = json;
    }

    @Override
    public <T> T execute(IdempotencyScope scope, String key, Object request, Class<T> responseType, Supplier<T> action) {
        if (key == null || key.isBlank()) {
            throw DomainException.badRequest("IDEMPOTENCY_KEY_REQUIRED", "Thiếu header Idempotency-Key");
        }
        String hash = OpaqueTokens.sha256Hex(json.writeValueAsString(request));
        Optional<IdempotencyRecord> existing = records.findByScopeAndIdemKey(scope, key);
        if (existing.isPresent()) {
            if (!existing.get().getRequestHash().equals(hash)) {
                throw new DomainException(HttpStatus.UNPROCESSABLE_ENTITY, "IDEMPOTENCY_KEY_REUSED",
                        "Idempotency-Key này đã dùng cho một request khác");
            }
            return json.readValue(existing.get().getResponseBody(), responseType);
        }
        T response = action.get();
        // GIỚI HẠN: hai request cùng key chạy đúng lúc cùng thấy "chưa có" -> unique orders.idempotency_key
        // làm request thứ hai fail (500) thay vì tạo đơn thứ hai; nâng cấp: insert bản ghi PENDING trước rồi cập nhật.
        records.save(new IdempotencyRecord(scope, key, hash, HttpStatus.CREATED.value(), json.writeValueAsString(response)));
        return response;
    }
}
