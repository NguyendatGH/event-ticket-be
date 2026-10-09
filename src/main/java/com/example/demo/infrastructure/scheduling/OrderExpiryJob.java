package com.example.demo.infrastructure.scheduling;

import com.example.demo.application.PaymentService;
import com.example.demo.domain.order.Order;
import com.example.demo.domain.order.OrderStatus;
import com.example.demo.infrastructure.persistence.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
public class OrderExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(OrderExpiryJob.class);

    private final OrderRepository orders;
    private final PaymentService payments;

    public OrderExpiryJob(OrderRepository orders, PaymentService payments) {
        this.orders = orders;
        this.payments = payments;
    }

    @Scheduled(fixedDelayString = "PT60S")
    public void run() {
        MDC.put("trace_id", UUID.randomUUID().toString());
        try {
            List<Order> due = orders.findAllByStatusAndExpiresAtBefore(OrderStatus.PENDING_PAYMENT, Instant.now());
            int expired = 0, manualReview = 0, paid = 0;
            for (Order o : due) {
                switch (payments.settleExpired(o.getId())) {
                    case "PAID" -> paid++;
                    case "EXPIRED" -> expired++;
                    case "MANUAL_REVIEW" -> manualReview++;
                    default -> { }
                }
            }
            if (!due.isEmpty()) log.info("OrderExpiryJob: {} đơn quá hạn, hết hạn {}, manual review {}, phát hiện đã trả {}", due.size(), expired, manualReview, paid);
        } finally {
            MDC.remove("trace_id");
        }
    }
}
