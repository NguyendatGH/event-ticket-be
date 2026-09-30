package com.example.demo.application.dto;

import jakarta.validation.constraints.NotNull;

/**
 * BTC chốt một yêu cầu hoàn tiền đang chờ người quyết. Bốn hướng chốt:
 *
 * <ul>
 *   <li>{@code SUCCEEDED} — đã chuyển tiền (thường là chuyển tay theo QR ở {@code /instruction}):
 *       vé thành REFUNDED, kho được cộng lại.</li>
 *   <li>{@code FAILED} — không chi được / BTC từ chối: vé về ACTIVE, kho giữ nguyên.</li>
 *   <li>{@code RETRY} — gửi lại lệnh chi (kèm {@code destination} nếu đổi đích).
 *       Chỉ hợp lệ khi provider CHƯA từng nhận lệnh, nếu không là chi tiền hai lần.</li>
 *   <li>{@code CANCELLED} — HỦY yêu cầu hoàn tiền (khách đổi ý, BTC không đồng ý hoàn...):
 *       vé về ACTIVE nên khách giữ vé và vẫn xin hoàn lại được sau này. Bắt buộc có {@code note}
 *       (lý do hủy), và chỉ hủy được khi lệnh chi chưa đi đâu cả.</li>
 * </ul>
 *
 * <p>SUCCEEDED/FAILED/RETRY chỉ hợp lệ khi refund đang {@code MANUAL_REVIEW}.
 * CANCELLED hợp lệ khi refund đang {@code MANUAL_REVIEW} hoặc {@code AWAITING_FUNDS}.
 *
 * <p>{@code note} là tuỳ chọn với ba outcome đầu nhưng BẮT BUỘC với CANCELLED: sau khi hủy,
 * nó là dòng duy nhất còn lại trong DB để biết vì sao yêu cầu hoàn tiền bị bỏ.
 */
public record ResolveRefundRequest(@NotNull Outcome outcome, String note, CreateRefundRequest.Destination destination) {
    public enum Outcome { SUCCEEDED, FAILED, RETRY, CANCELLED }
}
