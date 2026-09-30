package com.example.demo.infrastructure.mail;

import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Gửi mail HTML qua SMTP (cấu hình ở {@code spring.mail.*}).
 * <p>
 * NGUYÊN TẮC QUAN TRỌNG: mọi hàm ở đây KHÔNG BAO GIỜ ném exception ra ngoài, lỗi chỉ {@code log.warn} rồi thôi.
 * Lý do: Mailer được gọi từ giữa luồng hoàn tiền (refund). Lúc đó tiền/trạng thái đơn đã xử lý xong, mail chỉ là
 * thông báo cho ban tổ chức. Nếu SMTP chết hoặc Gmail chặn mà ta để exception bay ra, nó sẽ làm rollback/đứt luồng
 * hoàn tiền của khách — mất thứ quan trọng để cứu thứ không quan trọng. Thà không có mail còn hơn kẹt tiền.
 *
 * @see MailTemplates nơi đọc file HTML trong {@code resources/mail/} và escape dữ liệu người dùng
 */
@Slf4j
@Component
public class Mailer {

    private final JavaMailSender sender;
    private final boolean enabled;
    private final String from;
    private final String defaultRefundsUrl;

    public Mailer(JavaMailSender sender,
                  @Value("${app.mail.enabled:true}") boolean enabled,
                  @Value("${app.mail.from:}") String from,
                  @Value("${app.mail.organizer-refunds-url:}") String defaultRefundsUrl) {
        this.sender = sender;
        this.enabled = enabled;
        this.from = from == null ? "" : from.trim();
        this.defaultRefundsUrl = defaultRefundsUrl;
    }

    /**
     * Gửi link đặt lại mật khẩu (link đã kèm token, hết hạn theo {@code app.auth.reset-token-ttl}).
     * Gọi từ PasswordResetServiceImpl.
     */
    public void sendPasswordReset(String email, String resetUrl) {
        String subject = "Đặt lại mật khẩu";
        try {
            if (skipSending(email, subject + " -> " + resetUrl)) return;
            String html = MailTemplates.render("password-reset", Map.of("resetUrl", nvl(resetUrl, "")));
            send(email, subject, html);
        } catch (Exception e) {
            // Người dùng vẫn nhận response "đã gửi" (không tiết lộ email nào có thật), ta chỉ ghi log để dev biết
            log.warn("Không gửi được mail đặt lại mật khẩu cho {}: {}", email, e.toString());
        }
    }

    /**
     * Ví payout không đủ tiền: yêu cầu hoàn vé đã vào hàng chờ, nhắc ban tổ chức nạp ví.
     * Gửi tới mail của ban tổ chức.
     */
    public void sendRefundAwaitingFunds(String toEmail, RefundMailInfo info) {
        String subject = "[Đơn " + info.orderCode() + "] Ví chi không đủ tiền — một yêu cầu hoàn vé đang chờ";
        try {
            if (skipSending(toEmail, subject)) return;
            Map<String, String> values = Map.of(
                    "organizerName", nvl(info.organizerName(), "ban tổ chức"),
                    "orderCode", String.valueOf(info.orderCode()),
                    "amount", MailTemplates.formatVnd(info.amount()),
                    "ticketCount", String.valueOf(info.ticketCount()),
                    "customerName", nvl(info.customerName(), "(không rõ)"),
                    "customerEmail", nvl(info.customerEmail(), "(không có)"),
                    "refundsUrl", refundsUrl(info));
            send(toEmail, subject, MailTemplates.render("refund-awaiting-funds", values));
        } catch (Exception e) {
            log.warn("Không gửi được mail AWAITING_FUNDS cho đơn {}: {}", info.orderCode(), e.toString());
        }
    }

    /**
     * Yêu cầu hoàn vé hệ thống không tự xử lý được (MANUAL_REVIEW / chuyển khoản tay):
     * nêu rõ mã lý do kèm giải thích tiếng Việt để ban tổ chức biết phải làm gì.
     */
    public void sendRefundNeedsReview(String toEmail, RefundMailInfo info) {
        String subject = "[Đơn " + info.orderCode() + "] Một yêu cầu hoàn vé cần bạn xử lý tay";
        try {
            if (skipSending(toEmail, subject)) return;
            Map<String, String> values = Map.of(
                    "organizerName", nvl(info.organizerName(), "ban tổ chức"),
                    "orderCode", String.valueOf(info.orderCode()),
                    "amount", MailTemplates.formatVnd(info.amount()),
                    "ticketCount", String.valueOf(info.ticketCount()),
                    "reasonCode", nvl(info.reasonCode(), "UNKNOWN"),
                    "reasonText", reasonText(info.reasonCode()),
                    "customerName", nvl(info.customerName(), "(không rõ)"),
                    "customerEmail", nvl(info.customerEmail(), "(không có)"),
                    "refundsUrl", refundsUrl(info));
            send(toEmail, subject, MailTemplates.render("refund-needs-review", values));
        } catch (Exception e) {
            log.warn("Không gửi được mail cần xử lý tay cho đơn {}: {}", info.orderCode(), e.toString());
        }
    }

    /**
     * Ban tổ chức đã hủy yêu cầu hoàn vé của khách: báo cho KHÁCH biết, và nhấn mạnh vé vẫn còn dùng được.
     * Gửi tới {@code refunds.contact_email} — email khách tự nhập lúc tạo yêu cầu, có thể khác email lúc mua.
     * <p>
     * Không có tham số nào là note của ban tổ chức: đó là ghi chú nội bộ, xem {@link CustomerRefundMailInfo}.
     */
    public void sendRefundCancelledToCustomer(String toEmail, CustomerRefundMailInfo info) {
        String subject = "[Đơn " + info.orderCode() + "] Yêu cầu hoàn vé đã bị hủy — vé của bạn vẫn dùng được";
        try {
            if (skipSending(toEmail, subject)) return;
            Map<String, String> values = Map.of(
                    "customerName", nvl(info.customerName(), "bạn"),
                    "orderCode", String.valueOf(info.orderCode()),
                    "amount", MailTemplates.formatVnd(info.amount()),
                    "ticketCount", String.valueOf(info.ticketCount()),
                    "orderUrl", nvl(info.orderUrl(), ""));
            send(toEmail, subject, MailTemplates.render("refund-cancelled", values));
        } catch (Exception e) {
            // Y như ba method trên: chỉ log.warn. Lúc mail này được gửi thì vé đã về ACTIVE và refund đã FAILED
            // (transaction đã commit) — ném ra chỉ làm ban tổ chức nhận 500 cho một việc đã xong xuôi.
            log.warn("Không gửi được mail hủy yêu cầu hoàn vé cho đơn {}: {}", info.orderCode(), e.toString());
        }
    }

    /**
     * Dịch mã lý do (do RefundService sinh ra) thành câu tiếng Việt cho ban tổ chức đọc.
     * Để ở Java chứ không ở template: một template dùng chung cho mọi mã, khỏi phải viết 7 file HTML gần giống nhau.
     */
    private static String reasonText(String code) {
        return switch (code == null ? "" : code) {
            case "INSUFFICIENT_PAYOUT_BALANCE" ->
                    "Ví chi hộ (payout) không đủ tiền để thực hiện lệnh hoàn. Hãy nạp thêm vào ví payout.";
            case "AWAITING_FUNDS_TIMEOUT" ->
                    "Yêu cầu đã nằm chờ ví đủ tiền quá lâu (theo app.refund.awaiting-funds-timeout) nên hệ thống dừng tự động. "
                            + "Bạn cần chuyển khoản tay cho khách rồi đánh dấu đã hoàn.";
            case "PAYOUT_DISABLED" ->
                    "Kênh chi tự động đang được tắt trong cấu hình (app.refund.payout-enabled=false), nên mọi yêu cầu đều phải chuyển khoản tay.";
            case "PAYOUT_UNAVAILABLE" ->
                    "Không gọi được cổng chi trả (chưa cấu hình khóa payout, hoặc PayOS không phản hồi). Tiền CHƯA bị trừ.";
            case "PROCESSING_TIMEOUT" ->
                    "Cổng thanh toán chưa trả kết quả sau thời gian chờ. Lệnh có thể VẪN ĐANG chạy: hãy kiểm tra dashboard PayOS "
                            + "trước khi làm gì tiếp, đừng gửi lại ngay kẻo chi hai lần.";
            case "LIMIT_EXCEEDED" ->
                    "Vượt hạn mức chi của cổng thanh toán (hạn mức mỗi lần hoặc mỗi ngày). Chờ qua hạn mức hoặc chuyển khoản tay.";
            case "INVALID_DESTINATION" ->
                    "Số tài khoản / ngân hàng nhận tiền không hợp lệ. Cần liên hệ khách để lấy lại thông tin rồi tạo yêu cầu mới.";
            default ->
                    "Hệ thống không tự xử lý được yêu cầu này nên cần người kiểm tra. Xem chi tiết trong trang quản lý hoàn tiền.";
        };
    }

    /** Link mở trang hoàn tiền: ưu tiên link nơi gọi truyền vào, thiếu thì lấy {@code app.mail.organizer-refunds-url}. */
    private String refundsUrl(RefundMailInfo info) {
        return nvl(info.refundsUrl(), nvl(defaultRefundsUrl, ""));
    }

    /**
     * Có nên bỏ qua việc gửi thật không? Trả về true thì hàm gọi return luôn.
     * Ba trường hợp bỏ qua, và KHÔNG trường hợp nào được làm app crash:
     * <ul>
     *   <li>{@code app.mail.enabled=false}: chỉ log nội dung như bản stub cũ, để chạy test/dev không cần mạng;</li>
     *   <li>{@code app.mail.from} rỗng (chưa điền MY_EMAIL trong .env): log.warn, app vẫn chạy bình thường;</li>
     *   <li>không có địa chỉ người nhận: có gửi cũng lỗi, cảnh báo cho dev biết dữ liệu thiếu.</li>
     * </ul>
     */
    private boolean skipSending(String to, String preview) {
        if (!enabled) {
            log.info("[mail] (app.mail.enabled=false) gửi tới {}: {}", to, preview);
            return true;
        }
        if (from.isBlank()) {
            log.warn("[mail] chưa cấu hình mail (app.mail.from rỗng, kiểm tra MY_EMAIL trong .env), bỏ qua: {}", preview);
            return true;
        }
        if (to == null || to.isBlank()) {
            log.warn("[mail] không có địa chỉ người nhận, bỏ qua: {}", preview);
            return true;
        }
        return false;
    }

    /** Dựng MimeMessage HTML và gửi. Ném exception nếu SMTP lỗi — các hàm public bên trên đã bọc try/catch. */
    private void send(String to, String subject, String html) throws Exception {
        MimeMessage message = sender.createMimeMessage();
        // true = multipart (cho phép đính kèm/ảnh sau này), "UTF-8" = tiếng Việt trong nội dung và tiêu đề không lỗi font
        MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
        helper.setFrom(from);
        helper.setTo(to);
        helper.setSubject(subject);
        helper.setText(html, true);        // true = phần thân là HTML, không phải text thường
        sender.send(message);
        log.info("[mail] đã gửi \"{}\" tới {}", subject, to);
    }

    /** Giá trị rỗng/null thì lấy giá trị thay thế — để template không hiện chỗ trống trơ trọi. */
    private static String nvl(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
