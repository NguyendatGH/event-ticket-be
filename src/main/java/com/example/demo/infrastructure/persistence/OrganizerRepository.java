package com.example.demo.infrastructure.persistence;

import com.example.demo.domain.organizer.Organizer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface OrganizerRepository extends JpaRepository<Organizer, UUID> {

    Optional<Organizer> findByUserId(UUID userId);

    Optional<Organizer> findBySlug(String slug);

    boolean existsBySlug(String slug);
}
