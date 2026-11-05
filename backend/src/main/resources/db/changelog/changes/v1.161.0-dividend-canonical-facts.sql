--liquibase formatted sql
--changeset steven:v1.161.0-dividend-canonical-facts splitStatements:false
--comment: Task552 exact nominal source facts and canonical projection provenance; no financial data rewrite.
ALTER TABLE stock_dividend_snapshot_event
  ALTER COLUMN cash_dividend TYPE NUMERIC(30,18),
  ALTER COLUMN stock_dividend TYPE NUMERIC(30,18);
ALTER TABLE stock_dividend_history
  ALTER COLUMN cash_dividend TYPE NUMERIC(30,18),
  ALTER COLUMN stock_dividend TYPE NUMERIC(30,18);

ALTER TABLE stock_dividend_snapshot
  ADD COLUMN IF NOT EXISTS normalizer_version INTEGER NOT NULL DEFAULT 1,
  ADD COLUMN IF NOT EXISTS amount_basis VARCHAR(32) NOT NULL DEFAULT 'LEGACY_UNVERIFIED',
  ADD COLUMN IF NOT EXISTS date_groups_complete BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE stock_dividend_snapshot_event
  ADD COLUMN IF NOT EXISTS upstream_component_id VARCHAR(128);
ALTER TABLE stock_dividend_history
  ADD COLUMN IF NOT EXISTS canonical_date DATE,
  ADD COLUMN IF NOT EXISTS source_snapshot_id BIGINT,
  ADD COLUMN IF NOT EXISTS resolution_status VARCHAR(16) NOT NULL DEFAULT 'LEGACY',
  ADD COLUMN IF NOT EXISTS resolution_reason TEXT;

DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='fk_dividend_history_source_snapshot'
      AND conrelid='stock_dividend_history'::regclass) THEN
    ALTER TABLE stock_dividend_history ADD CONSTRAINT fk_dividend_history_source_snapshot
      FOREIGN KEY (source_snapshot_id) REFERENCES stock_dividend_snapshot(id);
  END IF;
END $$;

CREATE UNIQUE INDEX IF NOT EXISTS uq_dividend_canonical_active_date
  ON stock_dividend_history(market,stock_code,canonical_date)
  WHERE event_status='ACTIVE' AND canonical_date IS NOT NULL;
