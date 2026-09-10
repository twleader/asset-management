--liquibase formatted sql

--changeset steven:v1.126.0-exchange-rate-export-schedule-multi-time
--comment Requirement 145 / Task 423：expand/contract 正規化台幣兌美元每日匯出時間；parent legacy columns 保留 rollback shadow。
CREATE TABLE IF NOT EXISTS exchange_rate_export_schedule_time (
    id BIGSERIAL PRIMARY KEY,
    schedule_id BIGINT NOT NULL REFERENCES exchange_rate_export_schedule(id) ON DELETE CASCADE,
    run_hour INT NOT NULL,
    run_minute INT NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    last_run_date DATE,
    last_run_at TIMESTAMP,
    last_run_status VARCHAR(500),
    updated_at TIMESTAMP,
    CONSTRAINT uq_exchange_rate_export_schedule_time UNIQUE (schedule_id, run_hour, run_minute),
    CONSTRAINT ck_exchange_rate_export_schedule_time_hour CHECK (run_hour BETWEEN 0 AND 23),
    CONSTRAINT ck_exchange_rate_export_schedule_time_minute CHECK (run_minute BETWEEN 0 AND 59)
);
CREATE INDEX IF NOT EXISTS idx_exchange_rate_export_schedule_time_schedule
    ON exchange_rate_export_schedule_time(schedule_id);

-- 既有 parent 搬成一筆 enabled child；ON CONFLICT 讓重跑或部分部署不重複建立。
INSERT INTO exchange_rate_export_schedule_time
        (schedule_id, run_hour, run_minute, enabled, last_run_date, last_run_at, last_run_status, updated_at)
SELECT id, run_hour, run_minute, TRUE, last_run_date, last_run_at, last_run_status, updated_at
FROM exchange_rate_export_schedule
ON CONFLICT (schedule_id, run_hour, run_minute) DO NOTHING;

-- parent run_hour/run_minute/last_run_date 保留，供舊 image rollback；新 scheduler 只讀 child guard。
