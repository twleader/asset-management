--liquibase formatted sql

--changeset codex:v1.125.0-api-error-log-status-and-dedupe splitStatements:false
ALTER TABLE api_error_log ADD COLUMN IF NOT EXISTS http_status SMALLINT
    CHECK (http_status IS NULL OR (http_status BETWEEN 100 AND 599));
ALTER TABLE api_error_log ADD COLUMN IF NOT EXISTS dedupe_key VARCHAR(64);
CREATE UNIQUE INDEX IF NOT EXISTS uq_api_error_log_dedupe_key
    ON api_error_log (dedupe_key) WHERE dedupe_key IS NOT NULL;
