--liquibase formatted sql

--changeset steven:v1.58.0-realized-gain-export-schedule
--comment Requirement 38（Task 192）：已實現損益每日排程自動匯出，per-user 設定表。每個 owner 一列（owner_user_id UNIQUE、@Filter(ownerFilter) 隔離），存啟用開關、每日執行時分、輸出相對子路徑、上次執行日期(當日 guard)/時間/結果。背景 cron 讀全部列，逐列以 exportRealizedGainsForOwner(ownerId) 手動 enableFilter 後產檔（已實現損益為 per-user 資料，不過濾會外洩他人資料）。
CREATE TABLE realized_gain_export_schedule (
    id              BIGSERIAL PRIMARY KEY,
    owner_user_id   BIGINT       NOT NULL,
    enabled         BOOLEAN      NOT NULL DEFAULT FALSE,
    run_hour        INT          NOT NULL DEFAULT 8,
    run_minute      INT          NOT NULL DEFAULT 0,
    output_subpath  VARCHAR(255) NOT NULL DEFAULT 'input',
    last_run_date   DATE,
    last_run_at     TIMESTAMP,
    last_run_status VARCHAR(500),
    updated_at      TIMESTAMP,
    CONSTRAINT uq_rg_export_schedule_owner UNIQUE (owner_user_id),
    CONSTRAINT ck_rg_export_schedule_hour CHECK (run_hour BETWEEN 0 AND 23),
    CONSTRAINT ck_rg_export_schedule_minute CHECK (run_minute BETWEEN 0 AND 59)
);
