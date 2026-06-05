package com.steven.assets.service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 警示觸發 Email 寄送（Requirement 23）。
 * 環境變數 MAIL_USERNAME / MAIL_PASSWORD 未設定時 isEnabled() = false，
 * 上層應先檢查再呼叫。SMTP 失敗一律 log.warn 不拋例外，避免阻斷警示判斷。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EmailService {

    private final JavaMailSender mailSender;

    @Value("${spring.mail.username:}")
    private String mailUsername;

    @Value("${notification.email.from:}")
    private String configuredFrom;

    @PostConstruct
    void logStartup() {
        if (isEnabled()) {
            log.info("EmailService 啟用，寄件人={}", resolveFrom());
        } else {
            log.warn("EmailService 未設定 MAIL_USERNAME / MAIL_PASSWORD，警示通知將 skip 寄信");
        }
    }

    public boolean isEnabled() {
        return mailUsername != null && !mailUsername.isBlank();
    }

    public void send(List<String> recipients, String subject, String body) {
        if (!isEnabled()) {
            log.warn("EmailService disabled，skip 寄信：{}", subject);
            return;
        }
        if (recipients == null || recipients.isEmpty()) {
            log.warn("收件人為空，skip 寄信：{}", subject);
            return;
        }
        try {
            SimpleMailMessage msg = new SimpleMailMessage();
            msg.setFrom(resolveFrom());
            msg.setTo(recipients.toArray(new String[0]));
            msg.setSubject(subject);
            msg.setText(body);
            mailSender.send(msg);
            log.info("寄出警示通知 email：{} 收件人 {} 位", subject, recipients.size());
        } catch (Exception e) {
            log.warn("寄送警示 email 失敗（subject={}, recipients={}）：{}",
                    subject, recipients.size(), e.getMessage());
        }
    }

    private String resolveFrom() {
        return (configuredFrom != null && !configuredFrom.isBlank()) ? configuredFrom : mailUsername;
    }
}
