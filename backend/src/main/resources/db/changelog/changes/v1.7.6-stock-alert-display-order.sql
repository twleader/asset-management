--liquibase formatted sql

--changeset steven:v1.7.6-stock-alert-display-order
ALTER TABLE stock_alert ADD COLUMN display_order INTEGER NOT NULL DEFAULT 0;
-- 以建立時間初始化排序值
UPDATE stock_alert SET display_order = id;
