--liquibase formatted sql

--changeset steven:v1.73.0-asset-transaction-export-schedule
CREATE TABLE IF NOT EXISTS asset_transaction_export_schedule (
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
    CONSTRAINT uq_at_export_schedule_owner UNIQUE (owner_user_id),
    CONSTRAINT ck_at_export_schedule_hour CHECK (run_hour BETWEEN 0 AND 23),
    CONSTRAINT ck_at_export_schedule_minute CHECK (run_minute BETWEEN 0 AND 59)
);
