package com.example.demo.domain.payment;

/**
 * Trạng thái một lần thanh toán (một link) của đơn:
 * <pre>
 * PENDING ──đủ tiền, đơn còn chờ──▶ PAID
 *    │──provider báo thất bại──▶ FAILED (khách vẫn trả lại được trên cùng link tới khi hết hạn)
 *    │──trả thiếu──▶ UNDERPAID (đơn đóng sau đó → MANUAL_REVIEW)
 *    │──tiền vào khi đơn đã hủy/hết hạn──▶ PAID_LATE (đơn → MANUAL_REVIEW)
 *    └──đơn hủy/hết hạn khi chưa có tiền──▶ EXPIRED
 * </pre>
 * CREATED có trong schema cho provider cần tạo payment trước khi có link; code hiện tạo thẳng PENDING.
 */
public enum PaymentStatus { CREATED, PENDING, PAID, UNDERPAID, PAID_LATE, FAILED, EXPIRED }
