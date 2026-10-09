package com.example.demo.application.dto;

import com.example.demo.domain.payment.GatewayCallLog;
import com.example.demo.domain.payment.WebhookEvent;

import java.util.List;

public record OrderAudit(OrderResponse order, List<WebhookEvent> webhookEvents, List<GatewayCallLog> gatewayCallLogs) {}
