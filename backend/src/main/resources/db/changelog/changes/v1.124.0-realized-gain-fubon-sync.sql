--liquibase formatted sql

--changeset codex:v1.124.0-realized-gain-fubon-sync splitStatements:false
-- Task 395: manual rows retain all-null provenance; synchronized rows are immutable source facts.
ALTER TABLE realized_gain
    ADD COLUMN IF NOT EXISTS sync_source VARCHAR(32),
    ADD COLUMN IF NOT EXISTS sync_fingerprint CHAR(64),
    ADD COLUMN IF NOT EXISTS sync_occurrence INTEGER;

ALTER TABLE realized_gain DROP CONSTRAINT IF EXISTS ck_realized_gain_sync_provenance;
ALTER TABLE realized_gain
    ADD CONSTRAINT ck_realized_gain_sync_provenance CHECK (
        (sync_source IS NULL AND sync_fingerprint IS NULL AND sync_occurrence IS NULL)
        OR (
            sync_source = 'FUBON_REALIZED_GAIN_SYNC'
            AND sync_fingerprint IS NOT NULL
            AND sync_fingerprint ~ '^[0-9a-f]{64}$'
            AND sync_occurrence IS NOT NULL
            AND sync_occurrence >= 1
        )
    );

CREATE UNIQUE INDEX IF NOT EXISTS uq_realized_gain_fubon_sync_occurrence
    ON realized_gain (owner_user_id, sync_source, sync_fingerprint, sync_occurrence)
    WHERE sync_source IS NOT NULL;
