--liquibase formatted sql

--changeset steven:v1.91.0-trading-radar-notification-cooldown
-- Requirement 44（Task 301）：通知冷卻。同一 setting 同一 state_code 於冷卻窗內不重複寄送；
-- 只擋 email，不影響 last_action 基準。TIMESTAMPTZ + Hibernate Instant（時區中立寫入）。
ALTER TABLE trading_radar_notification_state
    ADD COLUMN IF NOT EXISTS last_notified_at TIMESTAMPTZ NULL;
