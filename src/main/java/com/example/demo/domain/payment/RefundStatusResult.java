package com.example.demo.domain.payment;

import java.time.Instant;

/** Trạng thái một lệnh hoàn ở provider, đã chuẩn hóa. Domain không thấy enum của PayOS. */
public record RefundStatusResult(Status status, String providerRefundId, String failureCode, String failureReason,
                                 Instant completedAt) {

    /** Trùng tên 1-1 với PayoutTransactionState của SDK PayOS, nên map bằng valueOf được. */
    public enum Status {
        RECEIVED, PROCESSING, SUCCEEDED, FAILED, CANCELLED, ON_HOLD, REVERSED;

        /** Đã chốt: không đổi nữa. */
        public boolean isFinal() { return this == SUCCEEDED || this == FAILED || this == CANCELLED; }

        /** Provider không tự quyết được, cần người xem. */
        public boolean needsHuman() { return this == ON_HOLD || this == REVERSED; }

        /**
         * Provider CÒN ĐANG xử lý: chưa có gì để chốt, và tuyệt đối không được coi là thất bại —
         * đánh FAILED ở đây là mời admin gửi lại lệnh trong khi lệnh cũ còn có thể thành công.
         */
        public boolean inFlight() { return this == RECEIVED || this == PROCESSING; }
    }

    public boolean isFinal() { return status.isFinal(); }

    public boolean needsHuman() { return status.needsHuman(); }

    public boolean inFlight() { return status.inFlight(); }
}
