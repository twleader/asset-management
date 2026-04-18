--liquibase formatted sql

--changeset steven:v1.3.0-stock-transaction-fields
ALTER TABLE stock_holding
    ADD COLUMN IF NOT EXISTS transaction_type        VARCHAR(10),
    ADD COLUMN IF NOT EXISTS transaction_date        DATE,
    ADD COLUMN IF NOT EXISTS transaction_exchange_rate NUMERIC(10, 4);
