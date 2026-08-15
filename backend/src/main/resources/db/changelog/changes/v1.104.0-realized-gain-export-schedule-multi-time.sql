--liquibase formatted sql

--changeset steven:v1.104.0-realized-gain-export-schedule-multi-time
--comment Requirement 73：expand/contract 將已實現損益每日匯出時間正規化；parent legacy columns 保留為 rollback shadow。
CREATE TABLE IF NOT EXISTS realized_gain_export_schedule_time (
    id BIGSERIAL PRIMARY KEY,
    schedule_id BIGINT NOT NULL REFERENCES realized_gain_export_schedule(id) ON DELETE CASCADE,
    run_hour INT NOT NULL,
    run_minute INT NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    last_run_date DATE,
    last_run_at TIMESTAMP,
    last_run_status VARCHAR(500),
    updated_at TIMESTAMP,
    CONSTRAINT uq_rg_export_schedule_time UNIQUE (schedule_id, run_hour, run_minute),
    CONSTRAINT ck_rg_export_schedule_time_hour CHECK (run_hour BETWEEN 0 AND 23),
    CONSTRAINT ck_rg_export_schedule_time_minute CHECK (run_minute BETWEEN 0 AND 59)
);
CREATE INDEX IF NOT EXISTS idx_rg_export_schedule_time_schedule ON realized_gain_export_schedule_time(schedule_id);

-- 既有 parent 轉成一筆 child；ON CONFLICT 讓重跑／部分部署不重複建立。
-- 一併複製 last_run_* 三欄：部署當天若原排程已跑過，新 child 仍被 guard 住、不會重跑。
INSERT INTO realized_gain_export_schedule_time (schedule_id, run_hour, run_minute, enabled, last_run_date, last_run_at, last_run_status, updated_at)
SELECT id, run_hour, run_minute, TRUE, last_run_date, last_run_at, last_run_status, updated_at
FROM realized_gain_export_schedule
ON CONFLICT (schedule_id, run_hour, run_minute) DO NOTHING;

-- parent 的 run_hour/run_minute/last_run_date 不 drop、不放寬：舊 image rollback 可啟動。
