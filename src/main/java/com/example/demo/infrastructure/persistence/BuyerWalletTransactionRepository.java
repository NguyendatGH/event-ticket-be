package com.example.demo.infrastructure.persistence;

import com.example.demo.domain.wallet.BuyerWalletTransaction;
import com.example.demo.domain.wallet.BuyerWalletTransactionType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface BuyerWalletTransactionRepository extends JpaRepository<BuyerWalletTransaction, UUID> {

    List<BuyerWalletTransaction> findTop30ByUserIdOrderByCreatedAtDesc(UUID userId);

    boolean existsByTypeAndRefId(BuyerWalletTransactionType type, UUID refId);

    java.util.Optional<BuyerWalletTransaction> findByUserIdAndTypeAndIdempotencyKey(UUID userId, BuyerWalletTransactionType type, String idempotencyKey);
}
