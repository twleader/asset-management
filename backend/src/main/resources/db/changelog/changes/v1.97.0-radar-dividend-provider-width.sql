--liquibase formatted sql

--changeset steven:v1.97.0-radar-dividend-provider-width
--comment Dividend providers may include a deterministic endpoint-set label (for example FinMind[TaiwanStockDividend+TaiwanStockDividendResult]); preserve it in audit/current-state writes instead of truncating or aborting the sync.
ALTER TABLE stock_dividend_snapshot
    ALTER COLUMN provider TYPE VARCHAR(128);

ALTER TABLE stock_dividend_history
    ALTER COLUMN source TYPE VARCHAR(128);
