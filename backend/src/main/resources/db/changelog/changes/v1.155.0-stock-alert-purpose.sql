--liquibase formatted sql

--changeset steven:v1.155.0-stock-alert-purpose
-- Requirement 194 / Task 502：用途只能由使用者明確指定；既有設定與觸發歷史保留未設定，
-- 不得依價格、均線或技術條件反推買進／賣出。
ALTER TABLE stock_alert ADD COLUMN IF NOT EXISTS purpose VARCHAR(16) NOT NULL DEFAULT 'UNSPECIFIED';
ALTER TABLE stock_alert_group ADD COLUMN IF NOT EXISTS purpose VARCHAR(16) NOT NULL DEFAULT 'UNSPECIFIED';
ALTER TABLE stock_alert_trigger ADD COLUMN IF NOT EXISTS purpose VARCHAR(16) NOT NULL DEFAULT 'UNSPECIFIED';

--changeset steven:v1.155.0-stock-alert-purpose-constraints splitStatements:false
-- ADD CONSTRAINT 沒有 IF NOT EXISTS；單一 DO 區塊和三個 pg_constraint guard 讓平行 worktree
-- 重複套用時保持冪等。splitStatements:false 不可省，避免 Liquibase 切斷 dollar-quoted 區塊。
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_stock_alert_purpose') THEN
        ALTER TABLE stock_alert ADD CONSTRAINT ck_stock_alert_purpose
            CHECK (purpose IN ('BUY', 'SELL', 'UNSPECIFIED'));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_stock_alert_group_purpose') THEN
        ALTER TABLE stock_alert_group ADD CONSTRAINT ck_stock_alert_group_purpose
            CHECK (purpose IN ('BUY', 'SELL', 'UNSPECIFIED'));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_stock_alert_trigger_purpose') THEN
        ALTER TABLE stock_alert_trigger ADD CONSTRAINT ck_stock_alert_trigger_purpose
            CHECK (purpose IN ('BUY', 'SELL', 'UNSPECIFIED'));
    END IF;
END $$;
