--liquibase formatted sql

--changeset steven:v1.4.0-stock-display-order
ALTER TABLE stock_holding ADD COLUMN IF NOT EXISTS display_order INTEGER;
