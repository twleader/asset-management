--liquibase formatted sql
--changeset steven:v1.114.0-stock-intraday-order-book
CREATE TABLE IF NOT EXISTS stock_intraday_order_book (
  stock_code VARCHAR(20) NOT NULL,
  market VARCHAR(20) NOT NULL,
  trading_date DATE NOT NULL,
  source_updated_at TIMESTAMPTZ NOT NULL,
  fetched_at TIMESTAMPTZ NOT NULL,
  source VARCHAR(32) NOT NULL,
  market_status VARCHAR(16) NOT NULL,
  actual_price NUMERIC(20,10) NOT NULL,
  previous_close NUMERIC(20,10) NOT NULL,
  open_price NUMERIC(20,10),
  high_price NUMERIC(20,10),
  low_price NUMERIC(20,10),
  average_price NUMERIC(20,10),
  turnover_yi NUMERIC(20,10),
  volume_lots BIGINT,
  previous_volume_lots BIGINT,
  inner_volume_lots BIGINT,
  outer_volume_lots BIGINT,
  PRIMARY KEY (stock_code, market),
  CONSTRAINT ck_stock_intraday_order_book_market CHECK (market = '台股'),
  CONSTRAINT ck_stock_intraday_order_book_source CHECK (source = 'FUBON_BOOKS'),
  CONSTRAINT ck_stock_intraday_order_book_status CHECK (market_status IN ('OPEN', 'CLOSED', 'UNKNOWN')),
  CONSTRAINT ck_stock_intraday_order_book_date CHECK (
    (source_updated_at AT TIME ZONE 'Asia/Taipei')::date = trading_date),
  CONSTRAINT ck_stock_intraday_order_book_required_price CHECK (
    actual_price > 0 AND previous_close > 0),
  CONSTRAINT ck_stock_intraday_order_book_optional_price CHECK (
    (open_price IS NULL OR open_price > 0)
    AND (high_price IS NULL OR high_price > 0)
    AND (low_price IS NULL OR low_price > 0)
    AND (average_price IS NULL OR average_price >= 0)
    AND (turnover_yi IS NULL OR turnover_yi >= 0)),
  CONSTRAINT ck_stock_intraday_order_book_lots CHECK (
    (volume_lots IS NULL OR volume_lots >= 0)
    AND (previous_volume_lots IS NULL OR previous_volume_lots >= 0)
    AND (inner_volume_lots IS NULL OR inner_volume_lots >= 0)
    AND (outer_volume_lots IS NULL OR outer_volume_lots >= 0))
);

CREATE TABLE IF NOT EXISTS stock_intraday_order_book_level (
  stock_code VARCHAR(20) NOT NULL,
  market VARCHAR(20) NOT NULL,
  level SMALLINT NOT NULL,
  bid_price NUMERIC(20,10),
  bid_volume_lots BIGINT,
  ask_price NUMERIC(20,10),
  ask_volume_lots BIGINT,
  PRIMARY KEY (stock_code, market, level),
  CONSTRAINT fk_stock_intraday_order_book_level_header FOREIGN KEY (stock_code, market)
    REFERENCES stock_intraday_order_book (stock_code, market) ON DELETE CASCADE,
  CONSTRAINT ck_stock_intraday_order_book_level_number CHECK (level BETWEEN 1 AND 5),
  CONSTRAINT ck_stock_intraday_order_book_level_bid_pair CHECK (
    (bid_price IS NULL) = (bid_volume_lots IS NULL)),
  CONSTRAINT ck_stock_intraday_order_book_level_ask_pair CHECK (
    (ask_price IS NULL) = (ask_volume_lots IS NULL)),
  CONSTRAINT ck_stock_intraday_order_book_level_price CHECK (
    (bid_price IS NULL OR bid_price > 0)
    AND (ask_price IS NULL OR ask_price > 0)),
  CONSTRAINT ck_stock_intraday_order_book_level_lots CHECK (
    (bid_volume_lots IS NULL OR bid_volume_lots >= 0)
    AND (ask_volume_lots IS NULL OR ask_volume_lots >= 0))
);
