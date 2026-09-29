package com.example.demo.infrastructure.persistence;

import com.example.demo.domain.event.TicketTier;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface TicketTierRepository extends JpaRepository<TicketTier, UUID> {

    List<TicketTier> findAllByEventIdOrderByPriceAsc(UUID eventId);

    List<TicketTier> findAllByEventIdIn(Collection<UUID> eventIds);
}
