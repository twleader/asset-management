--liquibase formatted sql
--changeset steven:v1.122.0-fubon-technical-indicator-and-basic-info
-- Requirement 135／Task 408: immutable FUBON_SDK history; Redis is intentionally not represented here.

-- Task408.9：last_action 除了 rule/action-policy 外，也必須知道採用哪一版技術來源。
-- 既有列保留 NULL（未知），第一次 FUBON_OVERLAY_V1 評估只重建 baseline，不把來源切換
-- 造成的 action 差異當成一般狀態轉入而寄通知。
ALTER TABLE trading_radar_notification_setting
    ADD COLUMN IF NOT EXISTS technical_source_version VARCHAR(40);

CREATE TABLE IF NOT EXISTS stock_technical_indicator (
    stock_code         VARCHAR(20) NOT NULL,
    market             VARCHAR(20) NOT NULL,
    provider           VARCHAR(32) NOT NULL,
    timeframe          CHAR(1) NOT NULL,
    profile_id         VARCHAR(32) NOT NULL,
    source_date        DATE NOT NULL,
    indicator_kind     VARCHAR(16) NOT NULL,
    parameters         JSONB NOT NULL,
    payload            JSONB NOT NULL,
    source_timestamp   TIMESTAMPTZ,
    capture_id         UUID NOT NULL,
    observed_at        TIMESTAMPTZ NOT NULL,
    first_observed_at  TIMESTAMPTZ NOT NULL,
    content_hash       CHAR(64) NOT NULL,
    CONSTRAINT pk_stock_technical_indicator PRIMARY KEY
        (stock_code, market, provider, timeframe, profile_id, source_date),
    CONSTRAINT uq_stock_technical_indicator_fact_hash UNIQUE
        (stock_code, market, provider, timeframe, profile_id, source_date, content_hash),
    CONSTRAINT ck_stock_technical_indicator_identity CHECK (market = '台股' AND provider = 'FUBON_SDK'),
    CONSTRAINT ck_stock_technical_indicator_timeframe CHECK (timeframe IN ('D', 'W')),
    CONSTRAINT ck_stock_technical_indicator_json CHECK
        (jsonb_typeof(parameters) = 'object' AND jsonb_typeof(payload) = 'object'),
    CONSTRAINT ck_stock_technical_indicator_hash CHECK (content_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_stock_technical_indicator_observed CHECK (first_observed_at <= observed_at),
    CONSTRAINT ck_stock_technical_indicator_profile CHECK (
        (profile_id IN ('sma_d_5','sma_d_10','sma_d_20','sma_d_60','sma_d_240')
            AND timeframe = 'D' AND indicator_kind = 'SMA') OR
        (profile_id IN ('rsi_d_5','rsi_d_10') AND timeframe = 'D' AND indicator_kind = 'RSI') OR
        (profile_id = 'kdj_d_9_3_3' AND timeframe = 'D' AND indicator_kind = 'KDJ') OR
        (profile_id = 'macd_d_12_26_9' AND timeframe = 'D' AND indicator_kind = 'MACD') OR
        (profile_id = 'bb_d_20' AND timeframe = 'D' AND indicator_kind = 'BBANDS') OR
        (profile_id IN ('sma_w_5','sma_w_10','sma_w_20')
            AND timeframe = 'W' AND indicator_kind = 'SMA') OR
        (profile_id IN ('rsi_w_5','rsi_w_10') AND timeframe = 'W' AND indicator_kind = 'RSI') OR
        (profile_id = 'kdj_w_9_3_3' AND timeframe = 'W' AND indicator_kind = 'KDJ') OR
        (profile_id = 'macd_w_12_26_9' AND timeframe = 'W' AND indicator_kind = 'MACD')
    )
);

CREATE TABLE IF NOT EXISTS fubon_technical_capture_member (
    capture_id              UUID NOT NULL,
    profile_id              VARCHAR(32) NOT NULL,
    stock_code              VARCHAR(20) NOT NULL,
    market                  VARCHAR(20) NOT NULL,
    provider                VARCHAR(32) NOT NULL,
    timeframe               CHAR(1) NOT NULL,
    source_date             DATE NOT NULL,
    content_hash            CHAR(64) NOT NULL,
    observed_at             TIMESTAMPTZ NOT NULL,
    previous_source_date    DATE,
    previous_content_hash   CHAR(64),
    CONSTRAINT pk_fubon_technical_capture_member PRIMARY KEY (capture_id, profile_id),
    CONSTRAINT fk_fubon_technical_capture_member_fact FOREIGN KEY
        (stock_code, market, provider, timeframe, profile_id, source_date, content_hash)
        REFERENCES stock_technical_indicator
        (stock_code, market, provider, timeframe, profile_id, source_date, content_hash)
        ON DELETE RESTRICT,
    CONSTRAINT fk_fubon_technical_capture_member_previous_fact FOREIGN KEY
        (stock_code, market, provider, timeframe, profile_id, previous_source_date, previous_content_hash)
        REFERENCES stock_technical_indicator
        (stock_code, market, provider, timeframe, profile_id, source_date, content_hash)
        ON DELETE RESTRICT,
    CONSTRAINT ck_fubon_technical_capture_member_identity CHECK (market = '台股' AND provider = 'FUBON_SDK'),
    CONSTRAINT ck_fubon_technical_capture_member_hash CHECK (content_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_fubon_technical_capture_member_previous_hash CHECK
        (previous_content_hash IS NULL OR previous_content_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_fubon_technical_capture_member_previous_pair CHECK
        ((previous_source_date IS NULL) = (previous_content_hash IS NULL)),
    CONSTRAINT ck_fubon_technical_capture_member_profile CHECK (
        (profile_id IN ('sma_d_5','sma_d_10','sma_d_20','sma_d_60','sma_d_240') AND timeframe = 'D') OR
        (profile_id IN ('rsi_d_5','rsi_d_10','kdj_d_9_3_3','macd_d_12_26_9','bb_d_20') AND timeframe = 'D') OR
        (profile_id IN ('sma_w_5','sma_w_10','sma_w_20','rsi_w_5','rsi_w_10','kdj_w_9_3_3','macd_w_12_26_9') AND timeframe = 'W')
    ),
    CONSTRAINT ck_fubon_technical_capture_member_previous_kdj CHECK (
        (previous_source_date IS NULL AND previous_content_hash IS NULL) OR
        (profile_id IN ('kdj_d_9_3_3', 'kdj_w_9_3_3') AND previous_source_date < source_date)
    )
);
CREATE INDEX IF NOT EXISTS idx_fubon_technical_capture_member_lookup
    ON fubon_technical_capture_member (stock_code, market, provider, observed_at DESC, capture_id);

CREATE TABLE IF NOT EXISTS fubon_stock_basic_info (
    stock_code          VARCHAR(20) NOT NULL,
    market              VARCHAR(20) NOT NULL,
    provider            VARCHAR(32) NOT NULL,
    source_date         DATE NOT NULL,
    exchange            VARCHAR(20) NOT NULL,
    instrument_type     VARCHAR(20) NOT NULL,
    source_name         VARCHAR(100) NOT NULL,
    industry            VARCHAR(100),
    security_type       VARCHAR(64),
    source_market       VARCHAR(64),
    price_limit_up      NUMERIC(20,10),
    price_limit_down    NUMERIC(20,10),
    trading_eligible    BOOLEAN,
    trading_status      VARCHAR(64),
    matching_interval   INTEGER,
    board_lot           INTEGER,
    currency            VARCHAR(10),
    observed_at         TIMESTAMPTZ NOT NULL,
    content_hash        CHAR(64) NOT NULL,
    CONSTRAINT pk_fubon_stock_basic_info PRIMARY KEY (stock_code, market, provider),
    CONSTRAINT ck_fubon_stock_basic_info_identity CHECK (market = '台股' AND provider = 'FUBON_SDK'),
    CONSTRAINT ck_fubon_stock_basic_info_hash CHECK (content_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_fubon_stock_basic_info_price CHECK
        ((price_limit_up IS NULL OR price_limit_up > 0) AND (price_limit_down IS NULL OR price_limit_down > 0)),
    CONSTRAINT ck_fubon_stock_basic_info_interval CHECK (matching_interval IS NULL OR matching_interval >= 0),
    CONSTRAINT ck_fubon_stock_basic_info_lot CHECK (board_lot IS NULL OR board_lot > 0),
    CONSTRAINT ck_fubon_stock_basic_info_exchange CHECK (exchange IN ('TWSE', 'TPEx') AND instrument_type = 'EQUITY')
);

CREATE TABLE IF NOT EXISTS fubon_intraday_candle (
    stock_code      VARCHAR(20) NOT NULL,
    market          VARCHAR(20) NOT NULL,
    provider        VARCHAR(32) NOT NULL,
    timeframe       SMALLINT NOT NULL,
    candle_at       TIMESTAMPTZ NOT NULL,
    source_date     DATE NOT NULL,
    exchange        VARCHAR(20) NOT NULL,
    open            NUMERIC(20,10) NOT NULL,
    high            NUMERIC(20,10) NOT NULL,
    low             NUMERIC(20,10) NOT NULL,
    close           NUMERIC(20,10) NOT NULL,
    average         NUMERIC(20,10) NOT NULL,
    volume          BIGINT NOT NULL,
    observed_at     TIMESTAMPTZ NOT NULL,
    content_hash    CHAR(64) NOT NULL,
    CONSTRAINT pk_fubon_intraday_candle PRIMARY KEY (stock_code, market, provider, timeframe, candle_at),
    CONSTRAINT ck_fubon_intraday_candle_identity CHECK (market = '台股' AND provider = 'FUBON_SDK' AND timeframe = 1),
    CONSTRAINT ck_fubon_intraday_candle_hash CHECK (content_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_fubon_intraday_candle_minute CHECK (date_trunc('minute', candle_at) = candle_at),
    CONSTRAINT ck_fubon_intraday_candle_source_day CHECK
        ((candle_at AT TIME ZONE 'Asia/Taipei')::date = source_date),
    CONSTRAINT ck_fubon_intraday_candle_prices CHECK
        (open > 0 AND high > 0 AND low > 0 AND close > 0 AND average > 0
         AND high >= open AND high >= close AND open >= low AND close >= low
         AND average >= low AND average <= high),
    CONSTRAINT ck_fubon_intraday_candle_volume CHECK (volume >= 0),
    CONSTRAINT ck_fubon_intraday_candle_exchange CHECK (exchange IN ('TWSE', 'TPEx'))
);
CREATE INDEX IF NOT EXISTS idx_fubon_intraday_candle_date
    ON fubon_intraday_candle (stock_code, market, provider, source_date, candle_at);

CREATE OR REPLACE FUNCTION reject_fubon_technical_history_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'fubon technical history is immutable';
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_stock_technical_indicator_immutable ON stock_technical_indicator;
CREATE TRIGGER trg_stock_technical_indicator_immutable
    BEFORE UPDATE OR DELETE ON stock_technical_indicator
    FOR EACH ROW EXECUTE FUNCTION reject_fubon_technical_history_mutation();

DROP TRIGGER IF EXISTS trg_fubon_technical_capture_member_immutable ON fubon_technical_capture_member;
CREATE TRIGGER trg_fubon_technical_capture_member_immutable
    BEFORE UPDATE OR DELETE ON fubon_technical_capture_member
    FOR EACH ROW EXECUTE FUNCTION reject_fubon_technical_history_mutation();
