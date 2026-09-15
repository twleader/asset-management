--liquibase formatted sql
--changeset steven:v1.132.0-broker-filled-trade-cost-projection
CREATE TABLE IF NOT EXISTS broker_filled_trade_cost_projection (
  id BIGSERIAL PRIMARY KEY,
  owner_user_id BIGINT NOT NULL REFERENCES app_user(id),
  broker_id BIGINT NOT NULL REFERENCES broker(id),
  broker_filled_no VARCHAR(50) NOT NULL,
  asset_transaction_id BIGINT NOT NULL REFERENCES asset_transaction(id),
  stock_code VARCHAR(20) NOT NULL,
  market VARCHAR(20) NOT NULL,
  currency VARCHAR(10) NOT NULL,
  transaction_type VARCHAR(10) NOT NULL,
  trade_date DATE NOT NULL,
  shares NUMERIC(15,5) NOT NULL,
  buy_cost NUMERIC(20,2) NOT NULL,
  status VARCHAR(40) NOT NULL CHECK (status IN ('PENDING','APPLIED','SKIPPED_NO_PRETRADE_BASIS')),
  CONSTRAINT uq_broker_fill_cost_projection UNIQUE (owner_user_id, broker_id, broker_filled_no),
  CONSTRAINT uq_broker_fill_cost_projection_transaction UNIQUE (asset_transaction_id)
);
CREATE INDEX IF NOT EXISTS idx_broker_fill_cost_projection_pending
  ON broker_filled_trade_cost_projection(owner_user_id, broker_id, stock_code, trade_date, broker_filled_no)
  WHERE status = 'PENDING';
