--liquibase formatted sql

--changeset steven:v1.55.0-trading-calendar-export-schedule
--comment Requirement 37（Task 185）：交易日曆每日排程自動匯出，per-user 設定表。每個 owner 一列（owner_user_id UNIQUE、@Filter(ownerFilter) 隔離），存啟用開關、每日執行時分、格式(json|excel)、輸出相對子路徑、上次執行時間/結果。背景 cron 讀全部列，逐列以該列 format/subpath 匯出「當前年度」交易日曆（全域資料，無需 owner 資料過濾）。
CREATE TABLE trading_calendar_export_schedule (
    id              BIGSERIAL PRIMARY KEY,
    owner_user_id   BIGINT       NOT NULL,
    enabled         BOOLEAN      NOT NULL DEFAULT FALSE,
    run_hour        INT          NOT NULL DEFAULT 8,
    run_minute      INT          NOT NULL DEFAULT 0,
    format          VARCHAR(10)  NOT NULL DEFAULT 'json',
    output_subpath  VARCHAR(255) NOT NULL DEFAULT 'input',
    last_run_date   DATE,
    last_run_at     TIMESTAMP,
    last_run_status VARCHAR(500),
    updated_at      TIMESTAMP,
    CONSTRAINT uq_tc_export_schedule_owner UNIQUE (owner_user_id),
    CONSTRAINT ck_tc_export_schedule_hour CHECK (run_hour BETWEEN 0 AND 23),
    CONSTRAINT ck_tc_export_schedule_minute CHECK (run_minute BETWEEN 0 AND 59),
    CONSTRAINT ck_tc_export_schedule_format CHECK (format IN ('json','excel'))
);
