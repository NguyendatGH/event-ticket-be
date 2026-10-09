package com.example.demo.infrastructure.persistence;

import com.example.demo.domain.wallet.SellerWalletTransaction;
import com.example.demo.domain.wallet.SellerWalletTransactionType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SellerWalletTransactionRepository extends JpaRepository<SellerWalletTransaction, UUID> {

    List<SellerWalletTransaction> findTop30ByOrganizerIdOrderByCreatedAtDesc(UUID organizerId);

    boolean existsByTypeAndRefId(SellerWalletTransactionType type, UUID refId);

    java.util.Optional<SellerWalletTransaction> findByOrganizerIdAndTypeAndIdempotencyKey(UUID organizerId, SellerWalletTransactionType type, String idempotencyKey);
}
