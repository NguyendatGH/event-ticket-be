package com.example.demo.application;

import com.example.demo.application.dto.OrderResponse;
import com.example.demo.application.dto.PageResponse;
import com.example.demo.domain.order.Order;

import java.util.UUID;

public interface OrderQueries {

    /** Không kiểm chủ đơn: chỉ dùng nội bộ (admin audit, job, giả lập provider trong test). */
    OrderResponse get(UUID orderId);

    /** Dùng cho endpoint của người mua: không phải chủ đơn -> 404. */
    OrderResponse getOwned(UUID userId, UUID orderId);

    PageResponse<OrderResponse> myOrders(UUID userId, String status, int page, int size);

    OrderResponse toResponse(Order order);
}
