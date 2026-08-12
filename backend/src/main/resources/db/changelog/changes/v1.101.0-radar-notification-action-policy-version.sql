--liquibase formatted sql

--changeset steven:v1.101.0-radar-notification-action-policy-version
--comment Requirement 65 / Task 316：V12 score/ruleVersion 維持不變，但 final action 首次接上 evidence gate。nullable action_policy_version 讓既有列在部署首輪只重建 gated baseline，不與舊 action 比較而誤發通知；禁止 backfill 或 default 猜填現行版本。
ALTER TABLE trading_radar_notification_setting
    ADD COLUMN IF NOT EXISTS action_policy_version VARCHAR(40);
