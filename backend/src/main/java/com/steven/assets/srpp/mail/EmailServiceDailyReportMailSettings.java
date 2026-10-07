package com.steven.assets.srpp.mail;

import com.steven.assets.service.EmailService;
import com.steven.assets.service.srpp.DailyReportMailSettings;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** 唯一可依賴 EmailService 與部署設定的 SMTP adapter。 */
@Component
@RequiredArgsConstructor
public class EmailServiceDailyReportMailSettings implements DailyReportMailSettings {
    private static final Pattern ADDRESS = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private final EmailService emailService;
    @Value("${spring.mail.username:}") private String mailUsername;
    @Value("${notification.email.from:}") private String configuredFrom;
    @Value("${srpp.daily-report.owner-email:tw.leader@gmail.com}") private String owner;
    @Value("${srpp.daily-report.recipients:shi.chihung@gmail.com}") private String recipients;
    @Override public Settings requireEnabled() {
        String from = emailService.resolveFrom();
        String[] parts = recipients == null ? new String[0] : recipients.split(",", -1);
        if (parts.length != 1 || !ADDRESS.matcher(parts.length == 0 ? "" : parts[0].trim()).matches()
                || blank(owner) || blank(mailUsername) || blank(configuredFrom)
                || !owner.equals(mailUsername) || !owner.equals(from)) {
            throw new com.steven.assets.service.srpp.DailyReportMailProblem(HttpStatus.SERVICE_UNAVAILABLE, "MAIL_DISABLED");
        }
        return new Settings(from, parts[0].trim());
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
