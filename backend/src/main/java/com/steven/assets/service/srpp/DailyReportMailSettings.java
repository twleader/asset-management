package com.steven.assets.service.srpp;

/** SMTP 身分與固定收件人設定的外部 port；核心服務不認識 EmailService 或環境變數。 */
public interface DailyReportMailSettings {
    Settings requireEnabled();
    record Settings(String from, String to) {}
}
