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

    /** Hàng chờ ví: theo thứ tự vào hàng (FIFO, không chen). */
    List<Refund> findAllByStatusOrderByQueuedSince(RefundStatus status);

    /** Recovery: REQUESTED đã gửi (có submittedAt) mà quá lâu không có kết quả. */
    List<Refund> findAllByStatusAndSubmittedAtBefore(RefundStatus status, Instant before);

    /** Recovery: REQUESTED chưa từng gửi (kill switch tắt, hoặc crash giữa TX1 và submit). */
    List<Refund> findAllByStatusAndSubmittedAtIsNullAndCreatedAtBefore(RefundStatus status, Instant before);

    Optional<Refund> findByProviderAndProviderRefundId(PaymentProvider provider, String providerRefundId);

    Optional<Refund> findByIdempotencyKey(String idempotencyKey);

    List<Refund> findAllByStatusInOrderByCreatedAtDesc(Collection<RefundStatus> statuses);

    long countByStatus(RefundStatus status);

    /** Tiền đang cam kết chi (đang gửi hoặc provider đang xử lý). */
    @Query("select coalesce(sum(r.amount), 0) from Refund r where r.status in :statuses")
    long sumAmountByStatusIn(@Param("statuses") Collection<RefundStatus> statuses);

    /** Tổng tiền các refund chưa kết thúc của MỘT đơn — dùng cho assert không hoàn quá số đã thu. */
    @Query("select coalesce(sum(r.amount), 0) from Refund r where r.orderId = :orderId and r.status in :statuses")
    long sumAmountByOrderIdAndStatusIn(@Param("orderId") UUID orderId, @Param("statuses") Collection<RefundStatus> statuses);
}
