--liquibase formatted sql

--changeset steven:v1.72.0-asset-transaction
CREATE TABLE IF NOT EXISTS asset_transaction (
    id               BIGSERIAL PRIMARY KEY,
    owner_user_id    BIGINT       NOT NULL,
    transaction_type VARCHAR(10)  NOT NULL,
    asset_type       VARCHAR(10)  NOT NULL,
    asset_name       VARCHAR(50)  NOT NULL,
    asset_code       VARCHAR(20),
    market           VARCHAR(20),
    currency         VARCHAR(10),
    channel          VARCHAR(30),
    trade_date       DATE         NOT NULL,
    shares           NUMERIC(15,5),
    price            NUMERIC(15,4),
    amount           NUMERIC(20,2) NOT NULL,
    exchange_rate    NUMERIC(10,4),
    notes            VARCHAR(500)
);
CREATE INDEX IF NOT EXISTS idx_asset_transaction_owner_date
    ON asset_transaction (owner_user_id, trade_date DESC);
