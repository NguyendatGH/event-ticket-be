package com.example.demo.infrastructure.persistence;

import com.example.demo.domain.order.Order;
import com.example.demo.domain.order.OrderStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrderRepository extends JpaRepository<Order, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Order> findWithLockById(UUID id);

    List<Order> findAllByStatusAndExpiresAtBefore(OrderStatus status, Instant before);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Order> findWithLockByOrderCode(long orderCode);

    List<Order> findAllByStatusAndExpiresAtBetween(OrderStatus status, Instant from, Instant to);

    Page<Order> findAllByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    Page<Order> findAllByUserIdAndStatusInOrderByCreatedAtDesc(UUID userId, Collection<OrderStatus> statuses, Pageable pageable);
}
