--liquibase formatted sql

--changeset steven:v1.7.4-stock-alert-triggered-kd
ALTER TABLE stock_alert ADD COLUMN last_triggered_kd_value DECIMAL(10,4);
