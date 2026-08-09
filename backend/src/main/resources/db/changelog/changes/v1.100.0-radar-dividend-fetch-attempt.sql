--liquibase formatted sql

--changeset steven:v1.100.0-radar-dividend-fetch-attempt
--comment Task 307.6：invalid/null scope 與無 snapshot 的失敗抓取仍需可查稽核，不建立 authoritative observation。
CREATE TABLE IF NOT EXISTS stock_dividend_fetch_attempt (
    id BIGSERIAL PRIMARY KEY,
    stock_code VARCHAR(32),
    market VARCHAR(16),
    provider VARCHAR(128),
    observed_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(32) NOT NULL,
    scope_from DATE,
    scope_to DATE,
    source_urls TEXT,
    error_reason TEXT,
    snapshot_id BIGINT REFERENCES stock_dividend_snapshot(id)
);

CREATE INDEX IF NOT EXISTS idx_stock_dividend_fetch_attempt_lookup
    ON stock_dividend_fetch_attempt (stock_code, market, observed_at DESC);
