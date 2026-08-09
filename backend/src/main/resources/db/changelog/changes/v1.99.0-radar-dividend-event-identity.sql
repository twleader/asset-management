--liquibase formatted sql

--changeset steven:v1.99.0-radar-dividend-event-identity
--comment Task 307.6：current-state 權息列保存 append-only snapshot event key；同日不同付款日／來源修訂不可互相覆蓋。
ALTER TABLE stock_dividend_history
    ADD COLUMN IF NOT EXISTS event_key VARCHAR(64);

DROP INDEX IF EXISTS uk_dividend_event;

CREATE UNIQUE INDEX IF NOT EXISTS uk_dividend_event
    ON stock_dividend_history (
        stock_code,
        market,
        year,
        COALESCE(ex_dividend_date, DATE '1970-01-01'),
        COALESCE(cash_dividend, 0),
        COALESCE(stock_dividend, 0),
        COALESCE(cash_payment_date, DATE '1970-01-01'),
        COALESCE(stock_payment_date, DATE '1970-01-01'),
        COALESCE(event_key, '')
    );

CREATE INDEX IF NOT EXISTS idx_dividend_event_key
    ON stock_dividend_history (stock_code, market, event_key)
    WHERE event_key IS NOT NULL;
