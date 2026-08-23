--liquibase formatted sql

--changeset steven:v1.112.0-trading-radar-blog-publish
CREATE TABLE IF NOT EXISTS blog_publish_credential (
    id                        BIGINT PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    blog_id                   VARCHAR(64),
    blog_url                  VARCHAR(512) NOT NULL DEFAULT 'https://twleader.blogspot.com/',
    account_label             VARCHAR(255),
    access_token              VARCHAR(2048),
    access_token_expires_at   TIMESTAMP,
    refresh_token             VARCHAR(2048),
    needs_reconnect           BOOLEAN NOT NULL DEFAULT FALSE,
    connected_at              TIMESTAMP,
    updated_at                TIMESTAMP
);

ALTER TABLE trading_radar_export_setting ADD COLUMN IF NOT EXISTS blog_enabled BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE trading_radar_export_setting ADD COLUMN IF NOT EXISTS blog_last_post_id VARCHAR(64);
ALTER TABLE trading_radar_export_setting ADD COLUMN IF NOT EXISTS blog_last_post_url VARCHAR(512);
ALTER TABLE trading_radar_export_setting ADD COLUMN IF NOT EXISTS blog_last_run_at TIMESTAMP;
ALTER TABLE trading_radar_export_setting ADD COLUMN IF NOT EXISTS blog_last_status VARCHAR(512);
