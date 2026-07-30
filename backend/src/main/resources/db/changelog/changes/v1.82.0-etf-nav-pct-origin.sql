--liquibase formatted sql

--changeset steven:v1.82.0-etf-nav-pct-origin
-- Requirement 34 / Task 259：折溢價來源標記，區分「來源直接公告」與「本系統反推」。
-- 冪等（IF NOT EXISTS）：全機共用一套運行中 DB、多 worktree 並行，本 changeset 可能已被別的分支套用；
-- 非冪等即 already exists → business-services / external-materials-service crash loop 整站掛（Task 207 教訓）。
-- 不回填既有 186 列：這些列抓取當時未記錄「怎麼算出來的」，無法回溯判斷，維持 NULL 是誠實狀態。
ALTER TABLE etf_nav_history ADD COLUMN IF NOT EXISTS pct_origin VARCHAR(20);
