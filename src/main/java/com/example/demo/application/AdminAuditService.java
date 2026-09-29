package com.example.demo.application;

import com.example.demo.application.dto.OrderAudit;

import java.util.UUID;

public interface AdminAuditService {

    OrderAudit audit(UUID orderId);
}
