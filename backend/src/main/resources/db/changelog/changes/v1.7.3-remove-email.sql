--liquibase formatted sql

--changeset steven:v1.7.3-remove-email
-- 移除 stock_alert 的 email 欄位（通知功能已移除）
ALTER TABLE stock_alert DROP COLUMN IF EXISTS email;

-- 移除 notification_setting 表
DROP TABLE IF EXISTS notification_setting;
