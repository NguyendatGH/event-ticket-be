package com.example.demo.infrastructure.persistence;

import com.example.demo.domain.payment.PaymentChannel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentChannelRepository extends JpaRepository<PaymentChannel, UUID> {

    List<PaymentChannel> findByOrganizerIdAndStatusOrderByOpenedAtAsc(UUID organizerId, PaymentChannel.Status status);

    Optional<PaymentChannel> findByOrganizerIdAndBankCode(UUID organizerId, String bankCode);

    Optional<PaymentChannel> findByIdAndOrganizerId(UUID id, UUID organizerId);

    boolean existsByOrganizerId(UUID organizerId);

    boolean existsByGatewayTerminalId(String gatewayTerminalId);
}
