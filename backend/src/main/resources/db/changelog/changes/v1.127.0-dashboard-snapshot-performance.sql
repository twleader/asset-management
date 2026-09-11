--liquibase formatted sql
--changeset steven:v1.127.0-dashboard-snapshot-performance runInTransaction:false
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_stock_holding_snapshot_id
    ON stock_holding (snapshot_id);

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_fund_holding_snapshot_id
    ON fund_holding (snapshot_id);

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_bank_deposit_snapshot_id
    ON bank_deposit (snapshot_id);
