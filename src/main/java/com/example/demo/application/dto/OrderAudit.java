package com.example.demo.application.dto;

import com.example.demo.domain.payment.GatewayCallLog;
import com.example.demo.domain.payment.WebhookEvent;

import java.util.List;

/** GET /admin/orders/{id}/audit: đơn + webhook đã nhận + mọi lần gọi provider, để tra soát khi có tranh chấp. */
public record OrderAudit(OrderResponse order, List<WebhookEvent> webhookEvents, List<GatewayCallLog> gatewayCallLogs) {}
