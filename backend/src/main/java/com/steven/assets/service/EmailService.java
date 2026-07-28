package com.steven.assets.service;

import jakarta.activation.DataHandler;
import jakarta.annotation.PostConstruct;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.util.ByteArrayDataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 警示觸發 Email 寄送（Requirement 23）。
 * 環境變數 MAIL_USERNAME 未設定時 isEnabled() = false，
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
        sendHtml(recipients, subject, html, inlineImages, null);
    }

    /**
     * 同 {@link #sendHtml(List, String, String, Map)}，另夾帶一份 iCalendar 邀請（Task 248）。
     * {@code icsContent} 為 null / 空白時行為與四參數版完全相同。
     */
    public void sendHtml(List<String> recipients, String subject, String html,
                         Map<String, byte[]> inlineImages, String icsContent) {
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
            attachCalendarInvite(helper, icsContent);
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

    /**
     * 把 iCalendar 邀請以 {@code text/calendar} body part 掛到 root multipart（Task 248）。
     *
     * <p>{@code MimeMessageHelper(msg, true, "UTF-8")} 為 MULTIPART_MODE_MIXED_RELATED：root 是
     * multipart/mixed、其內含 multipart/related（HTML + inline CID 圖），故 ics 掛在 root 不影響既有內容。
     *
     * <p>內容以 {@link ByteArrayDataSource} 寫入**明確的 UTF-8 位元組**，不可改用
     * {@code setContent(String, type)}：JavaMail 的 mailcap 未註冊 {@code text/calendar}
     * （只有 text/plain、text/html、text/xml、multipart/*、message/rfc822），
     * 會落到 ObjectDataContentHandler 的 String 分支以 {@code Charset.defaultCharset()} 寫出、
     * 忽略宣告的 charset，中文摘要在非 UTF-8 預設編碼的 JVM 下會變亂碼。
     *
     * <p>失敗策略：只 log.warn，讓外層照常寄出不含 ics 的信——日曆是加值，不能因此讓警示信寄不出去。
     */
    private void attachCalendarInvite(MimeMessageHelper helper, String icsContent) {
        if (icsContent == null || icsContent.isBlank()) return;
        try {
            MimeBodyPart calPart = new MimeBodyPart();
            calPart.setDataHandler(new DataHandler(new ByteArrayDataSource(
                    icsContent.getBytes(StandardCharsets.UTF_8),
                    "text/calendar; charset=UTF-8; method=REQUEST")));
            // 顯式設過的 Content-Transfer-Encoding 不會被 MimeBodyPart.updateHeaders 覆寫
            calPart.setHeader("Content-Transfer-Encoding", "8bit");
            helper.getRootMimeMultipart().addBodyPart(calPart);
        } catch (Exception e) {
            log.warn("夾帶日曆邀請失敗，改寄不含 ics 的信：{}", e.getMessage());
        }
    }

    /** 實際 SMTP 寄件人。public 供 dispatcher 取 ics 的 ORGANIZER —— 兩者必須逐字一致，Google 才會自動接受 REQUEST。 */
    public String resolveFrom() {
        return (configuredFrom != null && !configuredFrom.isBlank()) ? configuredFrom : mailUsername;
    }
}
