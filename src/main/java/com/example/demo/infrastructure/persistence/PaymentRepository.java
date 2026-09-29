package com.example.demo.infrastructure.persistence;

import com.example.demo.domain.payment.Payment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    Optional<Payment> findFirstByOrderIdOrderByCreatedAtDesc(UUID orderId);

    /** Payment của nhiều đơn trong một query; service tự chọn bản mới nhất mỗi đơn. */
    List<Payment> findAllByOrderIdIn(Collection<UUID> orderIds);
}
