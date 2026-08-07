--liquibase formatted sql

--changeset steven:v1.89.0-stock-price-close-source
--comment Requirement 7（Task 290）：保存收盤價的可稽核來源。既有列維持 NULL，禁止以 migration 猜測來源。
ALTER TABLE stock_price_history
    ADD COLUMN IF NOT EXISTS close_source VARCHAR(64);
