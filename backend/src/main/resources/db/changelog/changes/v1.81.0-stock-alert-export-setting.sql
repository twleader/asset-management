--liquibase formatted sql

-- Requirement 54 / Task 254：警示觸發即時匯出 JSON 到指定目錄（本機必寫、Google Drive 為可選附加副本）。
-- 這是第十個「輸出資料夾」設定表，但它是事件驅動而非排程，故刻意沒有 run_hour / run_minute / last_run_date
-- 三欄——沒有執行時刻可設、也不是一天跑一次。seed 不啟用任何一列，既有部署升級後行為與現況完全一致。
-- 版號避讓：原定 v1.80.0，但另一個 worktree 的 v1.80.0-asset-transaction-export-schedules-multi
-- 已先一步套用到運行中的 DB（databasechangelog 有紀錄）。本 changeset 當時尚未執行過，故改名安全。
--changeset steven:v1.81.0-stock-alert-export-setting
CREATE TABLE IF NOT EXISTS stock_alert_export_setting (
    id                 BIGSERIAL PRIMARY KEY,
    owner_user_id      BIGINT       NOT NULL,
    enabled            BOOLEAN      NOT NULL DEFAULT FALSE,
    output_subpath     VARCHAR(512) NOT NULL DEFAULT 'input',
    last_run_at        TIMESTAMP,
    last_run_status    VARCHAR(512),
    gdrive_enabled     BOOLEAN      NOT NULL DEFAULT FALSE,
    gdrive_subpath     VARCHAR(512),
    gdrive_last_run_at TIMESTAMP,
    gdrive_last_status VARCHAR(512),
    created_at         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_stock_alert_export_owner UNIQUE (owner_user_id)
);
