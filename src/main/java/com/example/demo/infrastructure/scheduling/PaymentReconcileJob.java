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

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
public class PaymentReconcileJob {

    private static final Logger log = LoggerFactory.getLogger(PaymentReconcileJob.class);
    private static final Duration WINDOW = Duration.ofMinutes(5);

    private final OrderRepository orders;
    private final PaymentService payments;

    public PaymentReconcileJob(OrderRepository orders, PaymentService payments) {
        this.orders = orders;
        this.payments = payments;
    }

    @Scheduled(fixedDelayString = "PT5M", initialDelayString = "PT5M")
    public void run() {
        MDC.put("trace_id", UUID.randomUUID().toString());
        try {
            Instant now = Instant.now();
            List<Order> soon = orders.findAllByStatusAndExpiresAtBetween(OrderStatus.PENDING_PAYMENT, now, now.plus(WINDOW));
            int paid = 0;
            for (Order o : soon) if (payments.reconcile(o.getId())) paid++;
            if (!soon.isEmpty()) log.info("PaymentReconcileJob: {} đơn sắp hết hạn, phát hiện đã trả {}", soon.size(), paid);
        } finally {
            MDC.remove("trace_id");
        }
    }
}
