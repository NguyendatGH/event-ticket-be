package com.example.demo.application;

import com.example.demo.domain.event.Event;
import com.example.demo.domain.order.Order;
import com.example.demo.domain.organizer.Organizer;
import com.example.demo.domain.refund.Refund;
import com.example.demo.domain.user.User;
import com.example.demo.infrastructure.mail.CustomerRefundMailInfo;
import com.example.demo.infrastructure.mail.Mailer;
import com.example.demo.infrastructure.mail.RefundMailInfo;
import com.example.demo.infrastructure.persistence.EventRepository;
import com.example.demo.infrastructure.persistence.OrderRepository;
import com.example.demo.infrastructure.persistence.OrganizerRepository;
import com.example.demo.infrastructure.persistence.RefundRepository;
import com.example.demo.infrastructure.persistence.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Gửi mail về một lệnh hoàn tiền: cho BAN TỔ CHỨC khi lệnh bị kẹt và chỉ người thật mới gỡ được
 * (nạp thêm tiền vào ví chi, hoặc tự chuyển khoản tay), và cho KHÁCH khi ban tổ chức hủy yêu cầu hoàn vé.
 *
 * <p>TẠI SAO tách khỏi {@link RefundService}: RefundService đã rất dài và nhiệm vụ của nó là "tiền đi đúng
 * một lần". Việc tìm email BTC phải lần qua 5 bảng (refund → order → event → organizer → user) và chẳng liên quan
 * gì tới tiền, nên nhét vào đó chỉ làm file phình thêm và khó đọc. Ở đây nó là một class nhỏ, một việc duy nhất.
 *
 * <p>QUAN TRỌNG: mọi method ở đây tự bọc try/catch và KHÔNG BAO GIỜ ném exception ra ngoài. Gửi mail chỉ là
 * việc thông báo — nó không được phép làm sập luồng hoàn tiền (khách đang chờ response, vé đang bị giữ ở
 * REFUND_PENDING). Nơi gọi trong RefundService vẫn bọc thêm một lớp try/catch nữa cho chắc.
 */
@Service
public class RefundNotifier {

    private static final Logger log = LoggerFactory.getLogger(RefundNotifier.class);

    private final RefundRepository refunds;
    private final OrderRepository orders;
    private final EventRepository events;
    private final OrganizerRepository organizers;
    private final UserRepository users;
    private final Mailer mailer;
    private final String refundsUrl;
    /** Mẫu link trang đơn của FE, ví dụ {@code http://localhost:3000/orders/{orderId}}; xem {@link #orderUrl}. */
    private final String orderUrlTemplate;

    public RefundNotifier(RefundRepository refunds, OrderRepository orders, EventRepository events,
                          OrganizerRepository organizers, UserRepository users, Mailer mailer,
                          @Value("${app.mail.organizer-refunds-url:}") String refundsUrl,
                          @Value("${app.mail.order-url-template:}") String orderUrlTemplate) {
        this.refunds = refunds;
        this.orders = orders;
        this.events = events;
        this.organizers = organizers;
        this.users = users;
        this.mailer = mailer;
        this.refundsUrl = refundsUrl;
        this.orderUrlTemplate = orderUrlTemplate;
    }

    /**
     * Ví chi không đủ tiền: lệnh đã vào hàng chờ AWAITING_FUNDS, BTC nạp ví là nó tự chạy tiếp.
     *
     * @param available số dư ví lúc phát hiện; {@code < 0} nghĩa là chưa đọc được số dư (provider từ chối
     *                  vì thiếu tiền nên ta không gọi lại ví chỉ để lấy con số cho log).
     *                  Số này CHỈ dùng để log — {@link RefundMailInfo} không có field cho nó.
     */
    public void awaitingFunds(UUID refundId, long available) {
        send(refundId, "AWAITING_FUNDS", available, mailer::sendRefundAwaitingFunds);
    }

    /** Lệnh không chi tự động được (kill switch tắt, ví/PayOS không trả lời, hoặc chờ ví quá lâu): cần người xem. */
    public void needsReview(UUID refundId) {
        send(refundId, "NEEDS_REVIEW", -1, mailer::sendRefundNeedsReview);
    }

    /**
     * Ban tổ chức vừa hủy yêu cầu hoàn vé: báo cho KHÁCH, vì khách không được thông báo gì thì chỉ biết
     * khi tự mở lại trang đơn. Gửi tới email khách tự nhập lúc tạo yêu cầu ({@code refunds.contact_email}),
     * KHÔNG phải {@code orders.customer_email} — khách nhập email riêng là để nhận thông báo ở đó.
     *
     * <p>Viết riêng chứ không dùng chung {@link #send}: mail này gửi cho người khác (khách, không phải BTC),
     * cần dữ liệu khác ({@link CustomerRefundMailInfo}), nên nhồi vào {@code send} sẽ thành một hàm đầy if.
     * Vẫn giữ đúng hai nguyên tắc của class này: KHÔNG {@code @Transactional}, và tự bọc try/catch để
     * không bao giờ ném ra ngoài.
     *
     * <p>{@code contactEmail} null (refund tạo trước migration V7) thì chỉ {@code log.warn} rồi return:
     * không có email thì có ném exception cũng chẳng gửi được mail, mà ném ra là làm hỏng việc hủy refund —
     * việc đã commit xong và quan trọng hơn cái mail nhiều.
     */
    public void cancelledByOrganizer(UUID refundId) {
        try {
            Refund refund = refunds.findById(refundId).orElse(null);
            if (refund == null) {
                log.warn("Không gửi được mail hủy hoàn vé cho refund {}: không còn tìm thấy refund", refundId);
                return;
            }
            String to = refund.getContactEmail();
            if (to == null || to.isBlank()) {
                log.warn("Refund {} bị BTC hủy nhưng không có contact_email (refund tạo trước migration V7?), "
                        + "khách sẽ không được thông báo", refundId);
                return;
            }
            Order order = orders.findById(refund.getOrderId()).orElse(null);
            if (order == null) {
                log.warn("Không gửi được mail hủy hoàn vé cho refund {}: không tìm thấy đơn {}",
                        refundId, refund.getOrderId());
                return;
            }
            CustomerRefundMailInfo info = new CustomerRefundMailInfo(order.getCustomerName(), order.getOrderCode(),
                    refund.getAmount(), refund.getItems().size(), orderUrl(order.getId()));
            mailer.sendRefundCancelledToCustomer(to, info);
            log.info("Đã gửi mail hủy hoàn vé cho khách {} về refund {} ({} VND, {} vé)",
                    to, refundId, refund.getAmount(), refund.getItems().size());
        } catch (RuntimeException ex) {
            log.warn("Gửi mail hủy hoàn vé cho refund {} thất bại: {}", refundId, ex.toString());
        }
    }

    /**
     * Link trang đơn trên FE: thay {@code {orderId}} trong {@code app.mail.order-url-template} bằng id thật.
     * Chưa cấu hình (chuỗi rỗng) thì trả rỗng — Mailer vẫn gửi mail, chỉ là nút không có link.
     */
    private String orderUrl(UUID orderId) {
        if (orderUrlTemplate == null || orderUrlTemplate.isBlank()) return "";
        return orderUrlTemplate.trim().replace("{orderId}", orderId.toString());
    }

    /**
     * Phần chung của hai method BTC ở trên: dựng nội dung mail, tìm email BTC, gọi Mailer.
     *
     * <p>CỐ Ý KHÔNG có {@code @Transactional}: gửi mail là I/O mạng, mở transaction bọc quanh nó là giữ
     * connection (và khóa row) trong lúc chờ SMTP trả lời. Mấy lần findById ở đây tự chạy auto-commit là đủ —
     * {@code refund.items} đã là EAGER nên không có lazy-load nào cần transaction.
     *
     * <p>{@code sender} là "gửi bằng template nào": {@code mailer::sendRefundAwaitingFunds} hoặc
     * {@code mailer::sendRefundNeedsReview}. Hai method đó cùng chữ ký nên truyền được như một tham số
     * (method reference), khỏi phải viết hai lần cùng một đoạn tìm email.
     */
    private void send(UUID refundId, String kind, long available, MailSender sender) {
        try {
            Refund refund = refunds.findById(refundId).orElse(null);
            if (refund == null) {
                log.warn("Không gửi được mail {} cho refund {}: không còn tìm thấy refund", kind, refundId);
                return;
            }
            Order order = orders.findById(refund.getOrderId()).orElse(null);
            if (order == null) {
                log.warn("Không gửi được mail {} cho refund {}: không tìm thấy đơn {}", kind, refundId, refund.getOrderId());
                return;
            }
            Organizer organizer = events.findById(order.getEventId())
                    .map(Event::getOrganizerId)
                    .flatMap(organizers::findById)
                    .orElse(null);
            if (organizer == null) {
                log.warn("Không gửi được mail {} cho refund {}: không tìm thấy BTC của sự kiện {}",
                        kind, refundId, order.getEventId());
                return;
            }
            String to = organizerEmail(organizer);
            if (to == null) {
                // Không có email nào để gửi: vẫn phải để lại dấu trong log, nếu không refund kẹt mà không ai biết.
                log.warn("Refund {} cần BTC xử lý ({}) nhưng BTC {} chưa có email liên hệ lẫn email chủ tài khoản",
                        refundId, kind, organizer.getId());
                return;
            }
            RefundMailInfo info = new RefundMailInfo(organizer.getName(), order.getOrderCode(), refund.getAmount(),
                    refund.getItems().size(), refund.getFailureCode(), order.getCustomerName(),
                    order.getCustomerEmail(), refundsUrl);
            sender.send(to, info);
            log.info("Đã gửi mail {} cho BTC {} về refund {} ({} VND, ví còn {})",
                    kind, to, refundId, refund.getAmount(), available < 0 ? "?" : String.valueOf(available));
        } catch (RuntimeException ex) {
            // Lớp chắn cuối: dù đọc DB lỗi hay Mailer ném ra thì luồng hoàn tiền vẫn phải đi tiếp.
            log.warn("Gửi mail {} cho refund {} thất bại: {}", kind, refundId, ex.toString());
        }
    }

    /**
     * Email nhận: ưu tiên {@code organizer.contactEmail} (email công việc BTC tự khai), rỗng thì lấy email
     * user chủ tài khoản BTC. Trả {@code null} nếu cả hai đều rỗng.
     */
    private String organizerEmail(Organizer organizer) {
        String contact = organizer.getContactEmail();
        if (contact != null && !contact.isBlank()) return contact.trim();
        if (organizer.getUserId() == null) return null;
        return users.findById(organizer.getUserId())
                .map(User::getEmail)
                .filter(e -> e != null && !e.isBlank())
                .map(String::trim)
                .orElse(null);
    }

    /** Chỉ để truyền "gọi method nào của Mailer" vào {@link #send}; hai method của Mailer cùng chữ ký này. */
    @FunctionalInterface
    private interface MailSender {
        void send(String toEmail, RefundMailInfo info);
    }
}
