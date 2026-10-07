package com.steven.assets.srpp.mail;

import com.steven.assets.service.EmailService;
import com.steven.assets.service.srpp.DailyReportMailSender;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor
public class EmailServiceDailyReportMailSender implements DailyReportMailSender {
    private final EmailService emailService;
    @Override public String send(String from, String to, String subject, String plain, String html) {
        return emailService.sendAlternativeOrThrow(from, to, subject, plain, html);
    }
}
