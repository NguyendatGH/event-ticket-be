package com.example.demo.infrastructure.persistence;

import com.example.demo.domain.event.Event;
import com.example.demo.domain.event.EventStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface EventRepository extends JpaRepository<Event, UUID> {
    Optional<Event> findBySlug(String slug);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Event> findWithLockById(UUID id);

    boolean existsBySlug(String slug);

    long countByOrganizerIdAndStatusIn(UUID organizerId, Collection<EventStatus> statuses);
}
