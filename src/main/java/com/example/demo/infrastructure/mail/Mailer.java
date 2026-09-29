package com.example.demo.infrastructure.mail;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Gửi email cho user.
 * GIỚI HẠN: bản log-only, chỉ in link ra log (chưa có SMTP). Cần gửi thật thì thêm spring-boot-starter-mail
 * và đổi thân hàm sang JavaMailSender, nơi gọi giữ nguyên.
 */
@Slf4j
@Component
public class Mailer {

    public void sendPasswordReset(String email, String resetUrl) {
        log.info("[mail] Đặt lại mật khẩu cho {}: {}", email, resetUrl);
    }
}
