--liquibase formatted sql

--changeset steven:v1.10.0-backup-setting
CREATE TABLE backup_setting (
    id                INTEGER PRIMARY KEY DEFAULT 1,
    manual_retention  INTEGER NOT NULL DEFAULT 5,
    daily_retention   INTEGER NOT NULL DEFAULT 50,
    weekly_retention  INTEGER NOT NULL DEFAULT 5,
    updated_at        TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT backup_setting_single_row CHECK (id = 1),
    CONSTRAINT backup_setting_manual_range CHECK (manual_retention BETWEEN 1 AND 999),
    CONSTRAINT backup_setting_daily_range  CHECK (daily_retention  BETWEEN 1 AND 999),
    CONSTRAINT backup_setting_weekly_range CHECK (weekly_retention BETWEEN 1 AND 999)
);
INSERT INTO backup_setting (id) VALUES (1);
