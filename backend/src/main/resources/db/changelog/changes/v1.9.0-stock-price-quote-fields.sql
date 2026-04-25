--liquibase formatted sql

--changeset steven:v1.9.0-stock-price-quote-fields
ALTER TABLE stock_price ADD COLUMN IF NOT EXISTS buy_price       DECIMAL(15,4);
ALTER TABLE stock_price ADD COLUMN IF NOT EXISTS sell_price      DECIMAL(15,4);
ALTER TABLE stock_price ADD COLUMN IF NOT EXISTS open_price      DECIMAL(15,4);
ALTER TABLE stock_price ADD COLUMN IF NOT EXISTS previous_close  DECIMAL(15,4);
ALTER TABLE stock_price ADD COLUMN IF NOT EXISTS high_price      DECIMAL(15,4);
ALTER TABLE stock_price ADD COLUMN IF NOT EXISTS low_price       DECIMAL(15,4);
ALTER TABLE stock_price ADD COLUMN IF NOT EXISTS volume          BIGINT;
