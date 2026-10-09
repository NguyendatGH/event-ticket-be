package com.example.demo.infrastructure.mail;

import jakarta.mail.Multipart;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.javamail.JavaMailSender;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MailerRefundMailTest {

    private final JavaMailSender sender = mock(JavaMailSender.class);
    private final Mailer mailer = new Mailer(sender, true, "encore@example.com", "http://localhost:3000/organizer/refunds");

    private static final CustomerRefundMailInfo CUSTOMER = new CustomerRefundMailInfo(
            "Nguyễn <b>An</b>", 1791475495398507L, 2_500_000, 1, "http://localhost:3000/orders/abc");

    @BeforeEach
    void realMessages() {
        when(sender.createMimeMessage()).thenAnswer(i -> new MimeMessage((Session) null));
    }

    @Test
    void succeededMailShowsWhereMoneyWentAndEscapesCustomerName() throws Exception {
        mailer.sendRefundSucceededToCustomer("khach@example.com", CUSTOMER, "Vietcombank ••••9012");

        String html = sentHtml();
        assertNoLeftoverPlaceholder(html);
        assertTrue(html.contains("Vietcombank ••••9012"), html);
        assertTrue(html.contains("1791475495398507"));
        assertTrue(html.contains("http://localhost:3000/orders/abc"));
        assertTrue(html.contains("Nguyễn &lt;b&gt;An&lt;/b&gt;"), "tên khách là dữ liệu người dùng, phải escape");
    }

    @Test
    void failedMailExplainsReasonInCustomerWords() throws Exception {
        mailer.sendRefundFailedToCustomer("khach@example.com", CUSTOMER, "INVALID_DESTINATION");

        String html = sentHtml();
        assertNoLeftoverPlaceholder(html);
        assertTrue(html.contains("Không chuyển được tiền tới ngân hàng"), html);
        assertTrue(html.contains("vẫn còn dùng được"));
        assertFalse(html.contains("INVALID_DESTINATION"), "khách không cần thấy mã nội bộ");
    }

    @Test
    void needsReviewMailExplainsDestinationReviewToOrganizer() throws Exception {
        mailer.sendRefundNeedsReview("btc@example.com", new RefundMailInfo("BTC A", 1791475495398507L, 2_500_000, 1,
                "DESTINATION_REVIEW", "Nguyen An", "khach@example.com", null));

        String html = sentHtml();
        assertNoLeftoverPlaceholder(html);
        assertTrue(html.contains("KHÁC tài khoản đã thanh toán"), html);
        assertTrue(html.contains("http://localhost:3000/organizer/refunds"), "thiếu link thì lấy link mặc định");
    }

    private String sentHtml() throws Exception {
        ArgumentCaptor<MimeMessage> sent = ArgumentCaptor.forClass(MimeMessage.class);
        verify(sender).send(sent.capture());
        String html = html(sent.getValue().getContent());
        assertNotNull(html, "mail phải có phần HTML");
        return html;
    }

    private static String html(Object content) throws Exception {
        if (content instanceof String s) return s;
        if (content instanceof Multipart parts) {
            for (int i = 0; i < parts.getCount(); i++) {
                String found = html(parts.getBodyPart(i).getContent());
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void assertNoLeftoverPlaceholder(String html) {
        assertFalse(html.matches("(?s).*\\{\\{\\w+}}.*"), "còn placeholder chưa thay: " + html);
    }
}
