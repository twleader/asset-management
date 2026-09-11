--liquibase formatted sql
--changeset steven:v1.128.0-fubon-volume-and-daily-candle
CREATE TABLE fubon_historical_daily_candle (
    stock_code varchar(20) NOT NULL,
    market varchar(20) NOT NULL CHECK (market = '台股'),
    provider varchar(32) NOT NULL CHECK (provider = 'FUBON_SDK'),
    trading_date date NOT NULL,
    exchange varchar(10) NOT NULL CHECK (exchange IN ('TWSE', 'TPEx', 'ESB')),
    source_market varchar(20),
    open numeric(30,10) NOT NULL CHECK (open > 0),
    high numeric(30,10) NOT NULL CHECK (high > 0),
    low numeric(30,10) NOT NULL CHECK (low > 0),
    close numeric(30,10) NOT NULL CHECK (close > 0),
    volume bigint NOT NULL CHECK (volume >= 0),
    turnover numeric(30,10) NOT NULL CHECK (turnover >= 0),
    price_change numeric(30,10),
    observed_at timestamptz NOT NULL,
    schema_version integer NOT NULL CHECK (schema_version = 1),
    canonical_payload jsonb NOT NULL,
    payload_hash char(64) NOT NULL CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fubon_historical_daily_candle_pkey PRIMARY KEY (stock_code, market, trading_date),
    CONSTRAINT ck_fubon_historical_daily_candle_ohlc CHECK (high >= open AND high >= close AND open >= low AND close >= low)
);

--changeset steven:v1.128.0-fubon-volume-and-daily-candle-immutable-trigger splitStatements:false
CREATE FUNCTION guard_fubon_historical_daily_candle_immutable() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'fubon_historical_daily_candle is immutable';
END;
$$;

CREATE TRIGGER trg_fubon_historical_daily_candle_immutable
BEFORE UPDATE OR DELETE ON fubon_historical_daily_candle
FOR EACH ROW EXECUTE FUNCTION guard_fubon_historical_daily_candle_immutable();

--changeset steven:v1.128.0-fubon-volume-and-daily-candle-error-log-catalog
-- Task425 broker failures are written by external-materials-service.  These immutable identities
-- must exist before its composite api_error_log foreign key can accept the diagnostic row.
INSERT INTO api_error_log_operation (source, operation_key, operation_label, display_order) VALUES
    ('FUBON_API', 'FUBON_INTRADAY_VOLUMES_READ', '個股當日分價量查詢', 160),
    ('FUBON_API', 'FUBON_HISTORICAL_DAILY_CANDLES_READ', '個股歷史日K線查詢', 170)
ON CONFLICT (source, operation_key) DO NOTHING;

--changeset steven:v1.128.0-fubon-volume-and-daily-candle-verify runOnChange:false splitStatements:false
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conrelid = 'stock_price_history'::regclass
          AND contype = 'u' AND pg_get_constraintdef(oid) LIKE '%(stock_code, market, trading_date)%'
    ) THEN
        RAISE EXCEPTION 'stock_price_history stock_code/market/trading_date unique constraint is required';
    END IF;
END;
$$;
