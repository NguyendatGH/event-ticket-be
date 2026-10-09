package com.example.demo.infrastructure.persistence;

import com.example.demo.domain.wallet.BuyerWallet;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.util.Optional;
import java.util.UUID;

public interface BuyerWalletRepository extends JpaRepository<BuyerWallet, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<BuyerWallet> findWithLockByUserId(UUID userId);
}
