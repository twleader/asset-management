--liquibase formatted sql

--changeset steven:v1.6.0-notification-setting
CREATE TABLE notification_setting (
    id         INTEGER PRIMARY KEY DEFAULT 1,
    email      VARCHAR(255) NOT NULL DEFAULT '',
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT notification_setting_single_row CHECK (id = 1)
);
INSERT INTO notification_setting (id, email) VALUES (1, '');
