--liquibase formatted sql

--changeset steven:v1.156.0-notification-recipient-name
-- Task 509：名稱僅供通知設定頁辨識；既有收件人維持 null，不從 email 回填。
ALTER TABLE notification_recipient ADD COLUMN IF NOT EXISTS name VARCHAR(100);
