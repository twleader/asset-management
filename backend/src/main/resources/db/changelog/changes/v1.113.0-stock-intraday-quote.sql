--liquibase formatted sql
--changeset steven:v1.113.0-stock-intraday-quote
CREATE TABLE IF NOT EXISTS stock_intraday_quote (
  stock_code VARCHAR(20) NOT NULL, market VARCHAR(20) NOT NULL, trading_date DATE NOT NULL,
  provider_updated_at TIMESTAMPTZ NOT NULL, source VARCHAR(64) NOT NULL,
  actual_price NUMERIC(20,10) NOT NULL, previous_close NUMERIC(20,10), open_price NUMERIC(20,10),
  high_price NUMERIC(20,10), low_price NUMERIC(20,10), buy_price NUMERIC(20,10), sell_price NUMERIC(20,10),
  volume BIGINT, PRIMARY KEY (stock_code, market),
  CONSTRAINT ck_stock_intraday_quote_actual_positive CHECK (actual_price > 0),
  CONSTRAINT ck_stock_intraday_quote_optional_positive CHECK (
    (previous_close IS NULL OR previous_close > 0) AND (open_price IS NULL OR open_price > 0)
    AND (high_price IS NULL OR high_price > 0) AND (low_price IS NULL OR low_price > 0)
    AND (buy_price IS NULL OR buy_price > 0) AND (sell_price IS NULL OR sell_price > 0)),
  CONSTRAINT ck_stock_intraday_quote_volume CHECK (volume IS NULL OR volume >= 0),
  CONSTRAINT ck_stock_intraday_quote_date CHECK ((provider_updated_at AT TIME ZONE 'Asia/Taipei')::date = trading_date)
);
