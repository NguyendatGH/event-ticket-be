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

    List<Ticket> findAllByOrderIdIn(Collection<UUID> orderIds);

    List<Ticket> findAllByOrderIdAndIdIn(UUID orderId, Collection<UUID> ids);

    long countByOrderIdAndStatusNot(UUID orderId, TicketStatus status);

    @Query("select coalesce(sum(t.price), 0) from Ticket t where t.status = :status")
    long sumPriceByStatus(@Param("status") TicketStatus status);
}
