package com.example.demo.infrastructure.persistence;

import com.example.demo.domain.payment.PaymentProvider;
import com.example.demo.domain.payment.WebhookEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WebhookEventRepository extends JpaRepository<WebhookEvent, UUID> {

    List<WebhookEvent> findAllByProviderAndEventIdStartingWithOrderByReceivedAt(PaymentProvider provider, String prefix);

    Optional<WebhookEvent> findByProviderAndEventId(PaymentProvider provider, String eventId);
}
