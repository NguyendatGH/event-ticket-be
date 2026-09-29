package com.example.demo.domain.order;

import com.example.demo.domain.common.DomainException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class OrderTest {

    // private static Order pending() {
    //     return Order.create(UUID.randomUUID(),
    //             List.of(new OrderItem(UUID.randomUUID(), "VIP", 2, 2_500_000), new OrderItem(UUID.randomUUID(), "GA", 1, 800_000)),
    //             "A", "a@example.com", null, 12_000, Instant.now().plusSeconds(900), "key");
    // }

    // @Test
    // void createComputesTotalsAndStartsPending() {
    //     Order o = pending();
    //     assertEquals(5_800_000, o.getSubtotalAmount());
    //     assertEquals(5_812_000, o.getTotalAmount());
    //     assertEquals(OrderStatus.PENDING_PAYMENT, o.getStatus());
    //     assertTrue(o.getOrderCode() > 0 && o.getOrderCode() < (1L << 53), "orderCode phải vừa số nguyên PayOS");
    // }

    // @Test
    // void createRejectsEmptyItems() {
    //     DomainException e = assertThrows(DomainException.class, () ->
    //             Order.create(UUID.randomUUID(), List.of(), "A", "a@example.com", null, 0, Instant.now(), "k"));
    //     assertEquals("ORDER_EMPTY", e.getCode());
    // }

    // @Test
    // void expireAndCancelOnlyFromPending() {
    //     Order o = pending();
    //     o.expire();
    //     assertEquals(OrderStatus.EXPIRED, o.getStatus());
    //     assertThrows(IllegalStateException.class, o::expire);
    //     assertEquals("ORDER_NOT_CANCELLABLE", assertThrows(DomainException.class, o::cancel).getCode());

    //     Order p = pending();
    //     p.cancel();
    //     assertEquals(OrderStatus.CANCELLED, p.getStatus());
    // }
}
