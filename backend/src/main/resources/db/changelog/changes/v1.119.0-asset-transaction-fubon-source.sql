--liquibase formatted sql

--changeset steven:v1.119.0-asset-transaction-fubon-source
--comment: Requirement 120／Task 385：asset_transaction 新增 source／broker_filled_no 供富邦成交紀錄同步冪等寫入
ALTER TABLE asset_transaction
    ADD COLUMN IF NOT EXISTS source VARCHAR(20) NOT NULL DEFAULT 'MANUAL',
    ADD COLUMN IF NOT EXISTS broker_filled_no VARCHAR(50);

ALTER TABLE asset_transaction
    DROP CONSTRAINT IF EXISTS asset_transaction_source_check;

ALTER TABLE asset_transaction
    ADD CONSTRAINT asset_transaction_source_check CHECK (source IN ('MANUAL', 'FUBON_SYNC'));

CREATE UNIQUE INDEX IF NOT EXISTS ux_asset_transaction_owner_broker_filled_no
    ON asset_transaction (owner_user_id, broker_filled_no)
    WHERE broker_filled_no IS NOT NULL;
