--liquibase formatted sql

--changeset steven:v1.88.0-index-daily-volume
--comment 指數日線加成交量欄位，供 Requirement 18 日線圖的每日成交量柱狀子圖（Task 288）
-- 台股：trade_volume=成交股數(股)、trade_value=成交金額(元)，來源 TWSE FMTQIK 月報（MI_5MINS_HIST 無量欄）
-- 海外：volume=成交量(股)，取自既有 Yahoo v8 chart 回應的 indicators.quote[0].volume，不新增外部來源
-- 皆 nullable：既有列全為 null，由回補補齊；SOX 恆為 0（純計算型指數無成交量）
-- ADD COLUMN IF NOT EXISTS 使本 changeset 冪等，重跑無害（非冪等即 already exists → crash loop，Task 207 教訓）

ALTER TABLE twse_index_daily_history
    ADD COLUMN IF NOT EXISTS trade_volume BIGINT,
    ADD COLUMN IF NOT EXISTS trade_value  NUMERIC(20,0);

ALTER TABLE us_index_daily_history
    ADD COLUMN IF NOT EXISTS volume BIGINT;
