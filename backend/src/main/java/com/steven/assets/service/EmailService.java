package com.steven.assets.service;

import jakarta.annotation.PostConstruct;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

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

    /**
     * 寄送 HTML email，並把 inlineImages（cid → PNG bytes）以 inline 附件嵌入（供 &lt;img src="cid:..."&gt; 使用）。
     * 失敗策略同 {@link #send}：一律 log.warn 不拋例外。
     */
    public void sendHtml(List<String> recipients, String subject, String html, Map<String, byte[]> inlineImages) {
        if (!isEnabled()) {
            log.warn("EmailService disabled，skip 寄信：{}", subject);
            return;
        }
        if (recipients == null || recipients.isEmpty()) {
            log.warn("收件人為空，skip 寄信：{}", subject);
            return;
        }
        try {
            MimeMessage msg = mailSender.createMimeMessage();
            // multipart=true：才能同時帶 HTML 本文與 inline 圖片
            MimeMessageHelper helper = new MimeMessageHelper(msg, true, "UTF-8");
            helper.setFrom(resolveFrom());
            helper.setTo(recipients.toArray(new String[0]));
            helper.setSubject(subject);
            helper.setText(html, true);   // 必須先 setText，再 addInline
            if (inlineImages != null) {
                for (Map.Entry<String, byte[]> e : inlineImages.entrySet()) {
                    helper.addInline(e.getKey(), new ByteArrayResource(e.getValue()), "image/png");
                }
            }
            mailSender.send(msg);
            long imgBytes = inlineImages == null ? 0
                    : inlineImages.values().stream().filter(b -> b != null).mapToLong(b -> b.length).sum();
            log.info("寄出警示通知 email（HTML）：{} 收件人 {} 位，附圖 {} 張 / {} KB",
                    subject, recipients.size(),
                    inlineImages == null ? 0 : inlineImages.size(), imgBytes / 1024);
        } catch (Exception e) {
            log.warn("寄送 HTML 警示 email 失敗（subject={}, recipients={}）：{}",
                    subject, recipients.size(), e.getMessage());
        }
    }

    private String resolveFrom() {
        return (configuredFrom != null && !configuredFrom.isBlank()) ? configuredFrom : mailUsername;
    }
}
