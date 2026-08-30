--liquibase formatted sql
--changeset steven:v1.121.0-app-feature-role-access
--comment: Requirement 134／Task 407：角色功能管理，一般使用者角色可用功能由管理者設定
CREATE TABLE IF NOT EXISTS app_feature (
    id BIGSERIAL PRIMARY KEY,
    code VARCHAR(100) NOT NULL UNIQUE,
    display_name VARCHAR(50) NOT NULL,
    menu_group VARCHAR(50),
    sort_order INTEGER NOT NULL DEFAULT 0,
    enabled_for_user BOOLEAN NOT NULL DEFAULT TRUE
);
