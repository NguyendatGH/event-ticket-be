package com.example.demo.infrastructure.web;

import com.example.demo.application.MyTicketService;
import com.example.demo.application.OrderQueries;
import com.example.demo.application.dto.MyTicketResponse;
import com.example.demo.application.dto.OrderResponse;
import com.example.demo.application.dto.PageResponse;
import com.example.demo.infrastructure.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/me")
@Tag(name = "My tickets", description = "Vé của tôi, đơn của tôi")
public class MyTicketsController {

    private final MyTicketService myTickets;
    private final OrderQueries orderQueries;

    public MyTicketsController(MyTicketService myTickets, OrderQueries orderQueries) {
        this.myTickets = myTickets;
        this.orderQueries = orderQueries;
    }

    @GetMapping("/tickets")
    @Operation(summary = "Vé của tôi", description = "scope = upcoming (mặc định) | past | all. Chỉ vé có owner là mình (mua khi đã đăng nhập)")
    public PageResponse<MyTicketResponse> tickets(@RequestParam(defaultValue = "upcoming") String scope,
                                                  @RequestParam(defaultValue = "0") int page,
                                                  @RequestParam(defaultValue = "12") int size) {
        return myTickets.page(CurrentUser.require(), scope, page, size);
    }

    @GetMapping("/tickets/{id}")
    @Operation(summary = "Chi tiết vé + lịch sử", description = "Vé không phải của mình cũng 404 TICKET_NOT_FOUND")
    public MyTicketResponse ticket(@PathVariable UUID id) {
        return myTickets.detail(CurrentUser.require(), id);
    }

    @GetMapping("/orders")
    @Operation(summary = "Đơn của tôi, mới nhất trước",
            description = "status tùy chọn: một hoặc nhiều OrderStatus cách nhau dấu phẩy, vd PAID hoặc CANCELLED,EXPIRED; bỏ trống = tất cả")
    public PageResponse<OrderResponse> orders(@RequestParam(required = false) String status,
                                              @RequestParam(defaultValue = "0") int page,
                                              @RequestParam(defaultValue = "12") int size) {
        return orderQueries.myOrders(CurrentUser.require(), status, page, size);
    }
}
