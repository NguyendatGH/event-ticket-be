package com.example.demo.infrastructure.persistence;

import com.example.demo.domain.wallet.SellerWallet;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.util.Optional;
import java.util.UUID;

public interface SellerWalletRepository extends JpaRepository<SellerWallet, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<SellerWallet> findWithLockByOrganizerId(UUID organizerId);
}
