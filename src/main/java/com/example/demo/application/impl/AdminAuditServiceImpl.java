package com.example.demo.application.impl;

import com.example.demo.application.AdminAuditService;
import com.example.demo.application.OrderQueries;
import com.example.demo.application.dto.OrderAudit;
import com.example.demo.application.dto.OrderResponse;
import com.example.demo.domain.payment.WebhookEvent;
import com.example.demo.infrastructure.persistence.GatewayCallLogRepository;
import com.example.demo.infrastructure.persistence.PaymentRepository;
import com.example.demo.infrastructure.persistence.WebhookEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class AdminAuditServiceImpl implements AdminAuditService {

    private final OrderQueries orderQueries;
    private final PaymentRepository payments;
    private final WebhookEventRepository webhookEvents;
    private final GatewayCallLogRepository callLogs;

    public AdminAuditServiceImpl(OrderQueries orderQueries, PaymentRepository payments,
                             WebhookEventRepository webhookEvents, GatewayCallLogRepository callLogs) {
        this.orderQueries = orderQueries;
        this.payments = payments;
        this.webhookEvents = webhookEvents;
        this.callLogs = callLogs;
    }

    @Override
    @Transactional(readOnly = true)
    public OrderAudit audit(UUID orderId) {
        OrderResponse order = orderQueries.get(orderId);
        List<WebhookEvent> events = payments.findFirstByOrderIdOrderByCreatedAtDesc(orderId)
                .map(p -> webhookEvents.findAllByProviderAndEventIdStartingWithOrderByReceivedAt(p.getProvider(), p.getProviderPaymentId()))
                .orElse(List.of());
        return new OrderAudit(order, events, callLogs.findAllByRefIdOrderByCreatedAt(orderId));
    }
}
