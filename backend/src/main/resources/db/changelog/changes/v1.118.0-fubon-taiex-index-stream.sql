--liquibase formatted sql
--changeset steven:v1.118.0-fubon-taiex-index-stream
-- One current normalized Fubon TAIEX point only.  This is not daily close authority or event history.
CREATE TABLE IF NOT EXISTS fubon_taiex_index_latest (
    index_code          VARCHAR(20) PRIMARY KEY,
    provider_symbol     VARCHAR(64) NOT NULL,
    exchange            VARCHAR(20) NOT NULL,
    trading_date        DATE NOT NULL,
    provider_updated_at TIMESTAMPTZ NOT NULL,
    index_point         NUMERIC(30,10) NOT NULL,
    source              VARCHAR(32) NOT NULL,
    CONSTRAINT ck_fubon_taiex_index_code CHECK (index_code = '0000'),
    CONSTRAINT ck_fubon_taiex_index_exchange CHECK (exchange = 'TWSE'),
    CONSTRAINT ck_fubon_taiex_index_point CHECK (index_point > 0),
    CONSTRAINT ck_fubon_taiex_index_source CHECK (source = 'FUBON_INDICES'),
    CONSTRAINT ck_fubon_taiex_index_provider_date
        CHECK ((provider_updated_at AT TIME ZONE 'Asia/Taipei')::date = trading_date)
);
