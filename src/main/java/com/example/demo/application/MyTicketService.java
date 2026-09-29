package com.example.demo.application;

import com.example.demo.application.dto.MyTicketResponse;
import com.example.demo.application.dto.PageResponse;

import java.util.UUID;

public interface MyTicketService {

    PageResponse<MyTicketResponse> page(UUID owner, String scope, int page, int size);

    MyTicketResponse detail(UUID owner, UUID ticketId);
}
