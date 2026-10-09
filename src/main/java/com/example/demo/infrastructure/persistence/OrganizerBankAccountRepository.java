package com.example.demo.infrastructure.persistence;

import com.example.demo.domain.payment.OrganizerBankAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrganizerBankAccountRepository extends JpaRepository<OrganizerBankAccount, UUID> {
    Optional<OrganizerBankAccount> findFirstByOrganizerIdAndIsDefaultTrue(UUID organizerId);

    List<OrganizerBankAccount> findByIsDefaultTrueAndGatewaySyncedAtIsNull();

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update OrganizerBankAccount a set a.gatewaySyncedAt = :at
            where a.organizerId = :organizerId and a.isDefault = true
              and a.bankBin = :bankBin and a.accountNumber = :accountNumber""")
    int markSynced(UUID organizerId, String bankBin, String accountNumber, Instant at);
}
