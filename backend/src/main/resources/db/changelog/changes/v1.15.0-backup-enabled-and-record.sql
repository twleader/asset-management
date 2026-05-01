--liquibase formatted sql

--changeset steven:v1.15.0-backup-enabled
ALTER TABLE backup_setting ADD COLUMN backup_enabled BOOLEAN NOT NULL DEFAULT TRUE;

--changeset steven:v1.15.0-backup-record
CREATE TABLE backup_record (
    id                BIGSERIAL PRIMARY KEY,
    folder            VARCHAR(20)  NOT NULL,
    filename          VARCHAR(255) NOT NULL,
    size_bytes        BIGINT       NOT NULL DEFAULT 0,
    modified_at       TIMESTAMP    NOT NULL,
    auto_pre_restore  BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at        TIMESTAMP    NOT NULL DEFAULT NOW(),
    CONSTRAINT backup_record_unique UNIQUE (folder, filename)
);
CREATE INDEX idx_backup_record_modified ON backup_record (modified_at DESC);
