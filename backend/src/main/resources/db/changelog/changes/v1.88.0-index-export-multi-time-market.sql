--liquibase formatted sql

--changeset steven:v1.88.0-index-export-multi-time-market
-- Requirement 45 / Task 287：每個 owner 多時間點、每時間點多指數。
CREATE TABLE IF NOT EXISTS index_export_schedule_time (
    id BIGSERIAL PRIMARY KEY,
    schedule_id BIGINT NOT NULL REFERENCES index_export_schedule(id) ON DELETE CASCADE,
    run_hour INT NOT NULL DEFAULT 8,
    run_minute INT NOT NULL DEFAULT 0,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    last_run_date DATE,
    last_run_at TIMESTAMP,
    last_run_status VARCHAR(500),
    updated_at TIMESTAMP,
    CONSTRAINT uq_index_export_schedule_time UNIQUE (schedule_id, run_hour, run_minute),
    CONSTRAINT ck_index_export_schedule_time_hour CHECK (run_hour BETWEEN 0 AND 23),
    CONSTRAINT ck_index_export_schedule_time_minute CHECK (run_minute BETWEEN 0 AND 59)
);

CREATE TABLE IF NOT EXISTS index_export_schedule_time_market (
    schedule_time_id BIGINT NOT NULL REFERENCES index_export_schedule_time(id) ON DELETE CASCADE,
    market VARCHAR(16) NOT NULL,
    PRIMARY KEY (schedule_time_id, market)
);

--changeset steven:v1.88.0-index-export-multi-time-market-migrate splitStatements:false
-- 只有仍存在舊欄位時才搬移；DO block 讓已完成部分遷移的環境可安全重跑。
DO $$
DECLARE
    has_old BOOLEAN;
BEGIN
    IF to_regclass('index_export_schedule') IS NULL THEN
        RAISE EXCEPTION 'index_export_schedule (v1.66.0) is required before v1.88.0';
    END IF;
    SELECT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'index_export_schedule' AND column_name = 'run_hour'
    ) INTO has_old;
    IF has_old THEN
        INSERT INTO index_export_schedule_time
            (schedule_id, run_hour, run_minute, enabled, last_run_date, last_run_at, last_run_status, updated_at)
        SELECT id, COALESCE(run_hour, 8), COALESCE(run_minute, 0), TRUE,
               last_run_date, last_run_at, last_run_status, updated_at
        FROM index_export_schedule
        ON CONFLICT (schedule_id, run_hour, run_minute) DO NOTHING;

        INSERT INTO index_export_schedule_time_market (schedule_time_id, market)
        SELECT t.id, COALESCE(s.market, 'TWSE')
        FROM index_export_schedule s
        JOIN index_export_schedule_time t ON t.schedule_id = s.id
        ON CONFLICT DO NOTHING;

        ALTER TABLE index_export_schedule DROP CONSTRAINT IF EXISTS ck_index_export_schedule_hour;
        ALTER TABLE index_export_schedule DROP CONSTRAINT IF EXISTS ck_index_export_schedule_minute;
        ALTER TABLE index_export_schedule DROP COLUMN IF EXISTS run_hour;
        ALTER TABLE index_export_schedule DROP COLUMN IF EXISTS run_minute;
        ALTER TABLE index_export_schedule DROP COLUMN IF EXISTS market;
        ALTER TABLE index_export_schedule DROP COLUMN IF EXISTS last_run_date;
        ALTER TABLE index_export_schedule DROP COLUMN IF EXISTS last_run_at;
        ALTER TABLE index_export_schedule DROP COLUMN IF EXISTS last_run_status;
    END IF;
END $$;
