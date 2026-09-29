package com.example.demo.infrastructure.persistence;

import com.example.demo.domain.idempotency.IdempotencyRecord;
import com.example.demo.domain.idempotency.IdempotencyScope;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, UUID> {

    Optional<IdempotencyRecord> findByScopeAndIdemKey(IdempotencyScope scope, String idemKey);
}
