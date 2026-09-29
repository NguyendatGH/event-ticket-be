package com.example.demo.infrastructure.scheduling;

import com.example.demo.application.RefundService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Ba việc nền của refund (refund-implementation-plan bước 5). Không ShedLock, một instance như OrderExpiryJob.
 * Lỗi của một job không được làm chết job khác, nên mỗi lần chạy bọc try/catch riêng.
 */
@Component
public class RefundJobs {

    private static final Logger log = LoggerFactory.getLogger(RefundJobs.class);

    private final RefundService refunds;

    public RefundJobs(RefundService refunds) {
        this.refunds = refunds;
    }

    /** Hỏi provider kết quả các lệnh đang bay. Với PayOS đây là đường DUY NHẤT (không có webhook lệnh chi). */
    @Scheduled(fixedDelayString = "${app.refund.poll-interval:PT10S}", initialDelayString = "PT10S")
    public void poll() {
        run("RefundPollJob", refunds::pollProcessing);
    }

    /** Ví đủ tiền thì gửi tiếp hàng chờ AWAITING_FUNDS. */
    @Scheduled(fixedDelayString = "${app.refund.queue-interval:PT60S}", initialDelayString = "PT30S")
    public void queue() {
        run("RefundQueueJob", refunds::drainQueue);
    }

    /** Lệnh REQUESTED kẹt (timeout lúc gửi, hoặc app chết giữa hai transaction). */
    @Scheduled(fixedDelayString = "${app.refund.recovery-interval:PT60S}", initialDelayString = "PT60S")
    public void recover() {
        run("RefundRecoveryJob", refunds::recoverStuck);
    }

    private static void run(String name, Runnable body) {
        MDC.put("trace_id", UUID.randomUUID().toString());
        try {
            body.run();
        } catch (RuntimeException ex) {
            log.error("{} lỗi: {}", name, ex.toString(), ex);
        } finally {
            MDC.remove("trace_id");
        }
    }
}
