package com.example.demo.infrastructure.persistence;

import com.example.demo.domain.payment.PaymentProvider;
import com.example.demo.domain.refund.Refund;
import com.example.demo.domain.refund.RefundStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RefundRepository extends JpaRepository<Refund, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Refund> findWithLockById(UUID id);

    List<Refund> findAllByOrderIdOrderByCreatedAt(UUID orderId);

    List<Refund> findAllByStatusOrderByCreatedAt(RefundStatus status);

    List<Refund> findAllByStatusOrderByQueuedSince(RefundStatus status);

    List<Refund> findAllByStatusAndSubmittedAtBefore(RefundStatus status, Instant before);

    List<Refund> findAllByStatusAndSubmittedAtIsNullAndCreatedAtBefore(RefundStatus status, Instant before);

    Optional<Refund> findByProviderAndProviderRefundId(PaymentProvider provider, String providerRefundId);

    Optional<Refund> findByIdempotencyKey(String idempotencyKey);

    @Query(value = """
            select r.* from refunds r
            join orders o on o.id = r.order_id
            join events e on e.id = o.event_id
            where e.organizer_id = :organizerId and r.status in (:statuses)
            order by r.created_at desc
            """, nativeQuery = true)
    List<Refund> findAllByOrganizer(@Param("organizerId") UUID organizerId, @Param("statuses") Collection<String> statuses);

    @Query(value = """
            select count(*) from refunds r
            join orders o on o.id = r.order_id
            join events e on e.id = o.event_id
            where e.organizer_id = :organizerId and r.id = :refundId
            """, nativeQuery = true)
    long countByOrganizerAndId(@Param("organizerId") UUID organizerId, @Param("refundId") UUID refundId);

    long countByStatus(RefundStatus status);

    @Query("select coalesce(sum(r.amount), 0) from Refund r where r.status in :statuses")
    long sumAmountByStatusIn(@Param("statuses") Collection<RefundStatus> statuses);

    @Query("select coalesce(sum(r.amount), 0) from Refund r where r.provider = :provider and r.status in :statuses")
    long sumAmountByProviderAndStatusIn(@Param("provider") PaymentProvider provider,
                                        @Param("statuses") Collection<RefundStatus> statuses);

    @Query("select coalesce(sum(r.amount), 0) from Refund r where r.orderId = :orderId and r.status in :statuses")
    long sumAmountByOrderIdAndStatusIn(@Param("orderId") UUID orderId, @Param("statuses") Collection<RefundStatus> statuses);
}
