--liquibase formatted sql

--changeset steven:v1.80.0-asset-transaction-export-schedules-multi
-- Requirement 49 / Task 255：交易紀錄排程自動匯出改為「每人可多筆」。
-- 冪等（IF EXISTS / IF NOT EXISTS）：全機共用一套運行中 DB、多 worktree 並行，本 changeset
-- 可能已被別的分支套用；非冪等即 already exists → business-services crash loop 整站掛（Task 207 教訓）。
-- 不做任何資料遷移：既有每人 1 列原地成為該使用者的第 1 筆排程，name 為 NULL
-- → 檔名（交易紀錄_{ownerId}_{yyyyMMdd}.xlsx）與落點完全不變，下游程式的輸入檔不會斷。
ALTER TABLE asset_transaction_export_schedule DROP CONSTRAINT IF EXISTS uq_at_export_schedule_owner;
ALTER TABLE asset_transaction_export_schedule ADD COLUMN IF NOT EXISTS name VARCHAR(50);
-- UNIQUE 掉了以後 owner 查詢失去索引；列表端點與 by-id 驗歸屬都要走它。
CREATE INDEX IF NOT EXISTS idx_at_export_schedule_owner ON asset_transaction_export_schedule(owner_user_id);
