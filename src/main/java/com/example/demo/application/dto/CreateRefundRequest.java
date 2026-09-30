package com.example.demo.application.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * Body của POST /api/v1/orders/{orderId}/refunds. Hoàn theo vé; destination chỉ gửi khi muốn nhận ở tài khoản khác.
 *
 * @param ticketIds    vé muốn hoàn, phải thuộc đúng đơn này
 * @param contactEmail email khách muốn nhận thông báo về yêu cầu NÀY. BẮT BUỘC, và cố ý hỏi lại chứ không
 *                     mặc định lấy {@code orders.customer_email}: khách có thể mua bằng mail công ty nhưng
 *                     muốn nhận thông báo vào mail cá nhân. Hiện dùng để báo khi ban tổ chức hủy yêu cầu hoàn vé
 *                     — không có email thì khách bị hủy mà chẳng biết gì cho tới khi tự mở lại trang đơn.
 *                     {@code @NotBlank} phải đi kèm {@code @Email} vì {@code @Email} coi chuỗi rỗng là hợp lệ.
 * @param reason       lý do khách ghi (không bắt buộc)
 * @param destination  tài khoản nhận khác tài khoản đã trả; để trống = hoàn về đúng chỗ đã trả
 */
public record CreateRefundRequest(
        @NotEmpty List<UUID> ticketIds,
        @NotBlank @Email @Size(max = 200) String contactEmail,
        @Size(max = 500) String reason,
        @Valid Destination destination
) {
    /**
     * Constructor rút gọn của record: chạy NGAY khi Jackson dựng object từ JSON, tức TRƯỚC khi {@code @Valid} kiểm.
     *
     * <p>TẠI SAO phải trim ở đây: {@code @Email} từ chối chuỗi có khoảng trắng đầu/cuối, nên
     * {@code "  an@example.com "} (rất hay gặp khi khách copy email từ chỗ khác dán vào) sẽ bị 400 một cách
     * vô lý. Trim trước rồi mới kiểm thì địa chỉ đó hợp lệ như bình thường. Vẫn KHÔNG nới lỏng gì:
     * {@code "   "} sau khi trim thành chuỗi rỗng và {@code @NotBlank} chặn đúng như cũ.
     *
     * <p>Chỉ trim, KHÔNG hạ chữ thường ở đây: hạ chữ thường là việc của tầng application
     * ({@code RefundService.open} gọi {@code AuthService.normalizeEmail}) — nơi duy nhất quyết định
     * dạng chuẩn để LƯU, dùng chung một hàm với đăng ký/đăng nhập cho cả app hiểu email theo một kiểu.
     */
    public CreateRefundRequest {
        contactEmail = contactEmail == null ? null : contactEmail.trim();
    }

    /** Tài khoản nhận khác tài khoản đã trả -> refund vào MANUAL_REVIEW cho admin duyệt (chống chuyển tiền cho người lạ). */
    public record Destination(@NotBlank String bin, @NotBlank String accountNumber) {}
}
