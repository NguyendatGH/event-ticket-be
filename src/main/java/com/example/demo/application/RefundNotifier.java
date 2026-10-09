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

    public void awaitingFunds(UUID refundId, long available) {
        send(refundId, "AWAITING_FUNDS", available, mailer::sendRefundAwaitingFunds);
    }

    public void needsReview(UUID refundId) {
        send(refundId, "NEEDS_REVIEW", -1, mailer::sendRefundNeedsReview);
    }

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

    private String orderUrl(UUID orderId) {
        if (orderUrlTemplate == null || orderUrlTemplate.isBlank()) return "";
        return orderUrlTemplate.trim().replace("{orderId}", orderId.toString());
    }

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
            log.warn("Gửi mail {} cho refund {} thất bại: {}", kind, refundId, ex.toString());
        }
    }

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

    @FunctionalInterface
    private interface MailSender {
        void send(String toEmail, RefundMailInfo info);
    }
}
