package com.example.demo.infrastructure.persistence;

import com.example.demo.domain.order.Ticket;
import com.example.demo.domain.order.TicketStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface TicketRepository extends JpaRepository<Ticket, UUID> {

    /** Vé của nhiều đơn trong một query ("đơn của tôi"). */
    List<Ticket> findAllByOrderIdIn(Collection<UUID> orderIds);

    /** Vé được chọn để hoàn, chặn luôn vé của đơn khác lọt vào. */
    List<Ticket> findAllByOrderIdAndIdIn(UUID orderId, Collection<UUID> ids);

    /** Còn bao nhiêu vé của đơn chưa REFUNDED: 0 thì đơn sang REFUNDED, còn thì PARTIALLY_REFUNDED. */
    long countByOrderIdAndStatusNot(UUID orderId, TicketStatus status);

    /** liability cho ví chi: tổng giá vé còn có thể bị đòi hoàn. */
    @Query("select coalesce(sum(t.price), 0) from Ticket t where t.status = :status")
    long sumPriceByStatus(@Param("status") TicketStatus status);
}
