--liquibase formatted sql

--changeset steven:v1.115.0-trading-radar-blog-destination-myrader
ALTER TABLE blog_publish_credential
    ALTER COLUMN blog_url SET DEFAULT 'https://myrader.blogspot.com/';

DELETE FROM blog_publish_credential;

UPDATE trading_radar_export_setting
SET blog_enabled = FALSE,
    blog_last_post_id = NULL,
    blog_last_post_url = NULL,
    blog_last_run_at = NULL,
    blog_last_status = NULL;
