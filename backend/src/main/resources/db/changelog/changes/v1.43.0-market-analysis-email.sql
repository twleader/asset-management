--liquibase formatted sql

--changeset steven:v1.43.0-recipient-market-analysis
--comment 今日股市分析結果每日 Email 寄送（Requirement 31 / Task 151）：notification_recipient 加「是否接收股市分析」訂閱旗標，沿用既有通知收件人。DEFAULT TRUE——既有收件人預設訂閱、可自行取消；與 active（是否接收警示）各自獨立。

ALTER TABLE notification_recipient
    ADD COLUMN receive_market_analysis BOOLEAN NOT NULL DEFAULT TRUE;

--changeset steven:v1.43.0-daily-analysis-email-sent-at
--comment 今日股市分析每日 Email 寄送冪等記號（Requirement 31 / Task 151）：daily_market_analysis 加 email_sent_at。批次收尾（finalizeIfReady）首次落 OK 時寄一次並戳記，之後（含手動重跑同一交易日）不重寄——滿足「僅每日自動寄、不重複打擾」。對齊 generated_at 為 timestamptz。

ALTER TABLE daily_market_analysis
    ADD COLUMN email_sent_at TIMESTAMP WITH TIME ZONE;
