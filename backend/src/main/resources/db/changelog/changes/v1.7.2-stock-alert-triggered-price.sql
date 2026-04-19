--liquibase formatted sql

--changeset steven:v1.7.2-stock-alert-triggered-price
ALTER TABLE stock_alert ADD COLUMN last_triggered_price DECIMAL(16,4);
