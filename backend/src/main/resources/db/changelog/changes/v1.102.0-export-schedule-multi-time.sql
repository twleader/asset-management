--liquibase formatted sql

--changeset steven:v1.102.0-export-schedule-multi-time
--comment Requirement 69：expand/contract 將每日匯出時間正規化；parent legacy columns 保留為 rollback shadow。
CREATE TABLE IF NOT EXISTS export_schedule_time (
    id BIGSERIAL PRIMARY KEY,
    schedule_id BIGINT NOT NULL REFERENCES export_schedule_setting(id) ON DELETE CASCADE,
    run_hour INT NOT NULL,
    run_minute INT NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    last_run_date DATE,
    last_run_at TIMESTAMP,
    last_run_status VARCHAR(500),
    updated_at TIMESTAMP,
    CONSTRAINT uq_export_schedule_time UNIQUE (schedule_id, run_hour, run_minute),
    CONSTRAINT ck_export_schedule_time_hour CHECK (run_hour BETWEEN 0 AND 23),
    CONSTRAINT ck_export_schedule_time_minute CHECK (run_minute BETWEEN 0 AND 59)
);
CREATE INDEX IF NOT EXISTS idx_export_schedule_time_schedule ON export_schedule_time(schedule_id);

-- 既有 parent 轉成一筆 child；ON CONFLICT 讓重跑／部分部署不重複建立。
INSERT INTO export_schedule_time (schedule_id, run_hour, run_minute, enabled, last_run_date, last_run_at, last_run_status, updated_at)
SELECT id, run_hour, run_minute, TRUE, last_run_date, last_run_at, last_run_status, updated_at
FROM export_schedule_setting
ON CONFLICT (schedule_id, run_hour, run_minute) DO NOTHING;

-- parent 的 run_hour/run_minute/last_run_date 不 drop、不放寬：舊 image rollback 可啟動。
