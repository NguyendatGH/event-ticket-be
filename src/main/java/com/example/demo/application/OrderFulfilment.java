package com.example.demo.application;

import com.example.demo.domain.order.Order;

public interface OrderFulfilment {

    int fulfil(Order order);

    void release(Order order);
}
