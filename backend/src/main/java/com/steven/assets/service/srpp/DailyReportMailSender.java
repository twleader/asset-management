package com.steven.assets.service.srpp;

/** SMTP 是 side-effect adapter；領域 service 不依賴 JavaMail。 */
public interface DailyReportMailSender { String send(String from, String to, String subject, String plain, String html); }
