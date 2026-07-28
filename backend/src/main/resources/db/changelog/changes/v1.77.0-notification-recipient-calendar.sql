--liquibase formatted sql

--changeset steven:v1.77.0-notification-recipient-calendar
-- Requirement 23 / Task 248：警示 digest 是否對該收件人夾帶 Google 日曆邀請（ics）。
-- 預設 FALSE（不同於 receive_market_analysis 的 TRUE）：日曆事件會實際寫進別人的日曆，
-- 屬明示同意才開的行為，不對既有收件人自動開啟。
-- 冪等（IF NOT EXISTS）：全機共用一套運行中 DB、多 worktree 並行，本 changeset 可能已被別的分支套用。
ALTER TABLE notification_recipient
    ADD COLUMN IF NOT EXISTS add_to_calendar BOOLEAN NOT NULL DEFAULT FALSE;
