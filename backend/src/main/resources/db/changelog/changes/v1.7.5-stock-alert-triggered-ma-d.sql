--liquibase formatted sql

--changeset steven:v1.7.5-stock-alert-triggered-ma-d
-- 觸發當下的均線值（季線或年線）
ALTER TABLE stock_alert ADD COLUMN last_triggered_ma_value DECIMAL(16,4);
-- 觸發當下的 D 值（last_triggered_kd_value 改用於存 K 值）
ALTER TABLE stock_alert ADD COLUMN last_triggered_d_value DECIMAL(10,4);
