--liquibase formatted sql

--changeset steven:v1.94.0-radar-event-observations
--comment Task 307.6：配息抓取的 append-only snapshot／event／observation 稽核鏈；normalized history 仍維持 current-state。
CREATE TABLE IF NOT EXISTS stock_dividend_snapshot (
    id BIGSERIAL PRIMARY KEY,
    stock_code VARCHAR(32) NOT NULL,
    market VARCHAR(16) NOT NULL,
    provider VARCHAR(32) NOT NULL,
    scope_from DATE NOT NULL,
    scope_to DATE NOT NULL,
    source_url TEXT,
    content_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (stock_code, market, provider, scope_from, scope_to, content_hash)
);

CREATE TABLE IF NOT EXISTS stock_dividend_snapshot_event (
    id BIGSERIAL PRIMARY KEY,
    snapshot_id BIGINT NOT NULL REFERENCES stock_dividend_snapshot(id),
    event_key VARCHAR(64) NOT NULL,
    year INTEGER,
    ex_dividend_date DATE,
    cash_dividend NUMERIC(18,6),
    stock_dividend NUMERIC(18,6),
    cash_payment_date DATE,
    stock_payment_date DATE,
    source_available_at TIMESTAMPTZ,
    UNIQUE (snapshot_id, event_key)
);

CREATE TABLE IF NOT EXISTS stock_dividend_fetch_observation (
    id BIGSERIAL PRIMARY KEY,
    snapshot_id BIGINT NOT NULL REFERENCES stock_dividend_snapshot(id),
    observed_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(16) NOT NULL,
    complete BOOLEAN NOT NULL,
    scope_from DATE NOT NULL,
    scope_to DATE NOT NULL,
    source_available_at TIMESTAMPTZ,
    error_reason TEXT,
    UNIQUE (snapshot_id, observed_at)
);

ALTER TABLE stock_dividend_history
    ADD COLUMN IF NOT EXISTS event_status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE';

CREATE INDEX IF NOT EXISTS idx_stock_dividend_fetch_asof
    ON stock_dividend_fetch_observation (snapshot_id, observed_at DESC);

CREATE INDEX IF NOT EXISTS idx_stock_dividend_snapshot_event_date
    ON stock_dividend_snapshot_event (snapshot_id, ex_dividend_date);
