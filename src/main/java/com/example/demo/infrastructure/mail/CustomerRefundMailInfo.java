package com.example.demo.infrastructure.mail;

/**
 * Dữ liệu dựng mail gửi CHO KHÁCH khi ban tổ chức hủy yêu cầu hoàn vé. amount tính bằng đồng (VND, không phần thập phân).
 *
 * <p>Tách khỏi {@link RefundMailInfo} (mail cho ban tổ chức) chứ không dùng chung một record: hai mail gửi cho
 * HAI NGƯỜI khác nhau nên cần những field khác nhau. Dùng chung sẽ kéo theo cả {@code reasonCode},
 * {@code customerEmail}... vào mail của khách — toàn thông tin nội bộ mà khách không cần thấy và không nên thấy.
 *
 * <p>CỐ Ý KHÔNG có {@code note} của ban tổ chức: note là ghi chú NỘI BỘ, có thể ghi tay kiểu
 * "khach nay hay doi y, khong hoan nua" — đọc cho nhau chứ không phải câu để khách đọc. FE cũng đã quyết
 * không cho khách thấy nguyên văn note, nên ở đây giữ nhất quán: mail chỉ mời khách liên hệ BTC nếu muốn biết lý do.
 *
 * @param customerName tên khách (để mở đầu mail cho có người)
 * @param orderCode    mã đơn khách nhìn thấy trên trang đơn
 * @param amount       số tiền của yêu cầu ĐÃ BỊ HỦY (không phải tổng đơn)
 * @param ticketCount  số vé của yêu cầu đã bị hủy
 * @param orderUrl     link mở lại trang đơn để khách tự xem vé còn dùng được
 */
public record CustomerRefundMailInfo(
        String customerName,
        long orderCode,
        long amount,
        int ticketCount,
        String orderUrl
) {}
