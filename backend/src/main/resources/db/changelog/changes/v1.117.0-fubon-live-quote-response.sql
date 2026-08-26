--liquibase formatted sql
--changeset steven:v1.117.0-fubon-live-quote-response
-- The table holds only the bounded normalized adapter contract, never SDK/raw account data.
CREATE TABLE IF NOT EXISTS fubon_tw_live_quote_response (
    stock_code   VARCHAR(20) NOT NULL,
    market       VARCHAR(20) NOT NULL,
    received_at  TIMESTAMPTZ NOT NULL,
    batch_id     VARCHAR(64) NOT NULL,
    counters     JSONB NOT NULL,
    response_row JSONB NOT NULL,
    CONSTRAINT pk_fubon_tw_live_quote_response PRIMARY KEY (stock_code, market),
    CONSTRAINT ck_fubon_tw_live_quote_response_market CHECK (market = '台股'),
    CONSTRAINT ck_fubon_tw_live_quote_response_counters_object CHECK (jsonb_typeof(counters) = 'object'),
    CONSTRAINT ck_fubon_tw_live_quote_response_row_object CHECK (jsonb_typeof(response_row) = 'object'),
    CONSTRAINT ck_fubon_tw_live_quote_response_batch_id CHECK (btrim(batch_id) <> '')
);
