package com.example.demo.infrastructure.persistence;

import com.example.demo.domain.gateway.OrganizerGatewayBinding;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.util.Optional;
import java.util.UUID;

public interface OrganizerGatewayBindingRepository extends JpaRepository<OrganizerGatewayBinding, UUID> {
    Optional<OrganizerGatewayBinding> findByOrganizerIdAndProvider(UUID organizerId, String provider);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<OrganizerGatewayBinding> findWithLockByOrganizerIdAndProvider(UUID organizerId, String provider);
    Optional<OrganizerGatewayBinding> findByGatewayMerchantNoAndProvider(String merchantNo, String provider);

    Optional<OrganizerGatewayBinding> findFirstByProviderAndStatusOrderByCreatedAtAsc(
            String provider, OrganizerGatewayBinding.Status status);
}
