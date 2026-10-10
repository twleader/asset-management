--liquibase formatted sql

--changeset steven:v1.148.0-fubon-intraday-candle-capture
CREATE TABLE IF NOT EXISTS fubon_intraday_candle_capture (
    stock_code varchar(20) NOT NULL,
    market varchar(20) NOT NULL,
    provider varchar(32) NOT NULL,
    source_date date NOT NULL,
    request_started_at timestamptz NOT NULL,
    captured_at timestamptz NOT NULL,
    latest_completed_at timestamptz,
    status varchar(20) NOT NULL,
    reason varchar(80),
    CONSTRAINT pk_fubon_intraday_candle_capture PRIMARY KEY (stock_code, market, provider),
    CONSTRAINT ck_fubon_intraday_capture_identity CHECK (market='台股' AND provider='FUBON_SDK'),
    CONSTRAINT ck_fubon_intraday_capture_status CHECK (status IN ('AVAILABLE','UNAVAILABLE','CONFLICT')),
    CONSTRAINT ck_fubon_intraday_capture_observation CHECK (captured_at >= request_started_at),
    CONSTRAINT ck_fubon_intraday_capture_available CHECK (status <> 'AVAILABLE' OR
        (latest_completed_at IS NOT NULL AND reason IS NULL
         AND latest_completed_at <= request_started_at - INTERVAL '60 seconds')),
    CONSTRAINT ck_fubon_intraday_capture_source_day CHECK
        ((request_started_at AT TIME ZONE 'Asia/Taipei')::date=source_date)
);
CREATE INDEX IF NOT EXISTS idx_fubon_intraday_candle_retention_date
    ON fubon_intraday_candle (source_date);
CREATE INDEX IF NOT EXISTS idx_fubon_intraday_capture_retention_date
    ON fubon_intraday_candle_capture (source_date);
