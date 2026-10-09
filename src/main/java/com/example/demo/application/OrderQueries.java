package com.example.demo.application;

import com.example.demo.application.dto.OrderResponse;
import com.example.demo.application.dto.PageResponse;
import com.example.demo.domain.order.Order;

import java.util.UUID;

public interface OrderQueries {

    OrderResponse get(UUID orderId);

    OrderResponse getOwned(UUID userId, UUID orderId);

    PageResponse<OrderResponse> myOrders(UUID userId, String status, int page, int size);

    OrderResponse toResponse(Order order);
}
