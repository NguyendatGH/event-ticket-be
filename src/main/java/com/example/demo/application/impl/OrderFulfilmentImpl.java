package com.example.demo.application.impl;

import com.example.demo.application.OrderFulfilment;
import com.example.demo.domain.order.Order;
import com.example.demo.domain.order.OrderItem;
import com.example.demo.domain.order.Ticket;
import com.example.demo.infrastructure.persistence.InventoryRepository;
import com.example.demo.infrastructure.persistence.TicketRepository;
import org.springframework.stereotype.Component;

import java.util.Comparator;

@Component
public class OrderFulfilmentImpl implements OrderFulfilment {

    private final TicketRepository tickets;
    private final InventoryRepository inventory;

    public OrderFulfilmentImpl(TicketRepository tickets, InventoryRepository inventory) {
        this.tickets = tickets;
        this.inventory = inventory;
    }

    @Override
    public int fulfil(Order order) {
        int issued = 0;
        for (OrderItem item : order.getItems()) {
            for (int i = 0; i < item.getQuantity(); i++) {
                tickets.save(Ticket.issue(order.getId(), item.getTicketTierId(), item.getUnitPrice(), order.getUserId()));
                issued++;
            }
        }
        return issued;
    }

    @Override
    public void release(Order order) {
        order.getItems().stream()
                .sorted(Comparator.comparing(OrderItem::getTicketTierId))
                .forEach(i -> inventory.findWithLockByTicketTierId(i.getTicketTierId())
                        .ifPresent(inv -> inv.release(i.getQuantity())));
    }
}
