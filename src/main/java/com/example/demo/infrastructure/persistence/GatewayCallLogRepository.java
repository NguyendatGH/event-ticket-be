package com.example.demo.infrastructure.persistence;

import com.example.demo.domain.payment.GatewayCallLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface GatewayCallLogRepository extends JpaRepository<GatewayCallLog, UUID> {

    List<GatewayCallLog> findAllByRefIdOrderByCreatedAt(UUID refId);
}
