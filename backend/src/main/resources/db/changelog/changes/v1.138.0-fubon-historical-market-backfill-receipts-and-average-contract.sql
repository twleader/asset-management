--liquibase formatted sql

--changeset steven:v1.138.0-fubon-historical-market-backfill-receipts-and-average-contract
-- Task466: immutable backfill manifest / attempts and session-cumulative minute average contract.
ALTER TABLE fubon_intraday_candle DROP CONSTRAINT IF EXISTS ck_fubon_intraday_candle_prices;
ALTER TABLE fubon_intraday_candle DROP CONSTRAINT IF EXISTS ck_fubon_intraday_candle_ohlc;
ALTER TABLE fubon_intraday_candle DROP CONSTRAINT IF EXISTS ck_fubon_intraday_candle_average;
ALTER TABLE fubon_intraday_candle ADD CONSTRAINT ck_fubon_intraday_candle_ohlc
    CHECK (open > 0 AND high > 0 AND low > 0 AND close > 0
       AND high >= open AND high >= close AND open >= low AND close >= low);
ALTER TABLE fubon_intraday_candle ADD CONSTRAINT ck_fubon_intraday_candle_average CHECK (average > 0);

CREATE TABLE IF NOT EXISTS fubon_historical_backfill_campaign (
    campaign_id uuid NOT NULL,
    from_date date NOT NULL,
    to_date date NOT NULL,
    latest_completed_date date NOT NULL,
    symbols jsonb NOT NULL,
    scope_sha256 character(64) NOT NULL,
    unsupported_coverage jsonb NOT NULL,
    created_at timestamptz NOT NULL,
    status character varying(16) NOT NULL DEFAULT 'RUNNING',
    finished_at timestamptz,
    summary jsonb,
    CONSTRAINT pk_fubon_historical_backfill_campaign PRIMARY KEY (campaign_id),
    CONSTRAINT ck_fubon_historical_backfill_campaign_dates CHECK (from_date <= to_date AND to_date = latest_completed_date),
    CONSTRAINT ck_fubon_historical_backfill_campaign_symbols CHECK (jsonb_typeof(symbols) = 'array' AND jsonb_array_length(symbols) BETWEEN 1 AND 30),
    CONSTRAINT ck_fubon_historical_backfill_campaign_hash CHECK (scope_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_fubon_historical_backfill_campaign_coverage CHECK (jsonb_typeof(unsupported_coverage) = 'object'),
    CONSTRAINT ck_fubon_historical_backfill_campaign_status CHECK (status IN ('RUNNING', 'SUCCESS', 'PARTIAL')),
    CONSTRAINT ck_fubon_historical_backfill_campaign_finished CHECK ((status = 'RUNNING' AND finished_at IS NULL) OR (status <> 'RUNNING' AND finished_at IS NOT NULL))
);

CREATE TABLE IF NOT EXISTS fubon_historical_backfill_window_attempt (
    attempt_id bigserial NOT NULL,
    campaign_id uuid NOT NULL,
    dataset character varying(32) NOT NULL,
    symbol character varying(20) NOT NULL,
    window_from date NOT NULL,
    window_to date NOT NULL,
    attempt_number integer NOT NULL,
    status character varying(20) NOT NULL,
    started_at timestamptz NOT NULL,
    completed_at timestamptz,
    observed_at timestamptz,
    provider_row_count integer NOT NULL DEFAULT 0,
    inserted_count integer NOT NULL DEFAULT 0,
    unchanged_count integer NOT NULL DEFAULT 0,
    conflict_count integer NOT NULL DEFAULT 0,
    error_code character varying(64),
    CONSTRAINT pk_fubon_historical_backfill_window_attempt PRIMARY KEY (attempt_id),
    CONSTRAINT fk_fubon_historical_backfill_window_attempt_campaign FOREIGN KEY (campaign_id)
        REFERENCES fubon_historical_backfill_campaign (campaign_id),
    CONSTRAINT uq_fubon_historical_backfill_window_attempt UNIQUE (campaign_id, dataset, symbol, window_from, attempt_number),
    CONSTRAINT ck_fubon_historical_backfill_window_attempt_dataset CHECK (dataset IN ('DAILY_CANDLE', 'INTRADAY_CANDLE_1M')),
    CONSTRAINT ck_fubon_historical_backfill_window_attempt_dates CHECK (window_from <= window_to),
    CONSTRAINT ck_fubon_historical_backfill_window_attempt_number CHECK (attempt_number > 0),
    CONSTRAINT ck_fubon_historical_backfill_window_attempt_status CHECK (status IN ('STARTED', 'COMPLETE', 'NO_DATA', 'FAILED', 'CONFLICT', 'SCOPE_CHANGED')),
    CONSTRAINT ck_fubon_historical_backfill_window_attempt_completion CHECK ((status = 'STARTED' AND completed_at IS NULL) OR (status <> 'STARTED' AND completed_at IS NOT NULL)),
    CONSTRAINT ck_fubon_historical_backfill_window_attempt_counts CHECK (provider_row_count >= 0 AND inserted_count >= 0 AND unchanged_count >= 0 AND conflict_count >= 0)
);
CREATE INDEX IF NOT EXISTS idx_fubon_historical_backfill_attempt_latest
    ON fubon_historical_backfill_window_attempt (campaign_id, dataset, symbol, window_from, attempt_number DESC);
CREATE INDEX IF NOT EXISTS idx_fubon_historical_backfill_campaign_status
    ON fubon_historical_backfill_campaign (status, created_at DESC);

--changeset steven:v1.138.0-fubon-historical-backfill-campaign-trigger-function splitStatements:false
CREATE OR REPLACE FUNCTION guard_fubon_historical_backfill_campaign_manifest() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'fubon historical backfill campaign is immutable'; END IF;
    IF NEW.campaign_id IS DISTINCT FROM OLD.campaign_id OR NEW.from_date IS DISTINCT FROM OLD.from_date
       OR NEW.to_date IS DISTINCT FROM OLD.to_date OR NEW.latest_completed_date IS DISTINCT FROM OLD.latest_completed_date
       OR NEW.symbols IS DISTINCT FROM OLD.symbols OR NEW.scope_sha256 IS DISTINCT FROM OLD.scope_sha256
       OR NEW.unsupported_coverage IS DISTINCT FROM OLD.unsupported_coverage OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'fubon historical backfill campaign manifest is immutable';
    END IF;
    RETURN NEW;
END $$;

--changeset steven:v1.138.0-fubon-historical-backfill-campaign-trigger
DROP TRIGGER IF EXISTS trg_fubon_historical_backfill_campaign_manifest ON fubon_historical_backfill_campaign;
CREATE TRIGGER trg_fubon_historical_backfill_campaign_manifest
BEFORE UPDATE OR DELETE ON fubon_historical_backfill_campaign
FOR EACH ROW EXECUTE FUNCTION guard_fubon_historical_backfill_campaign_manifest();

--changeset steven:v1.138.0-fubon-historical-backfill-attempt-trigger-function splitStatements:false
CREATE OR REPLACE FUNCTION guard_fubon_historical_backfill_attempt_immutable() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'fubon historical backfill attempt is immutable'; END IF;
    IF OLD.status <> 'STARTED' OR NEW.status = 'STARTED'
       OR NEW.attempt_id IS DISTINCT FROM OLD.attempt_id OR NEW.campaign_id IS DISTINCT FROM OLD.campaign_id
       OR NEW.dataset IS DISTINCT FROM OLD.dataset OR NEW.symbol IS DISTINCT FROM OLD.symbol
       OR NEW.window_from IS DISTINCT FROM OLD.window_from OR NEW.window_to IS DISTINCT FROM OLD.window_to
       OR NEW.attempt_number IS DISTINCT FROM OLD.attempt_number OR NEW.started_at IS DISTINCT FROM OLD.started_at THEN
        RAISE EXCEPTION 'fubon historical backfill attempt may transition once from STARTED';
    END IF;
    RETURN NEW;
END $$;

--changeset steven:v1.138.0-fubon-historical-backfill-attempt-trigger
DROP TRIGGER IF EXISTS trg_fubon_historical_backfill_attempt_immutable ON fubon_historical_backfill_window_attempt;
CREATE TRIGGER trg_fubon_historical_backfill_attempt_immutable
BEFORE UPDATE OR DELETE ON fubon_historical_backfill_window_attempt
FOR EACH ROW EXECUTE FUNCTION guard_fubon_historical_backfill_attempt_immutable();
