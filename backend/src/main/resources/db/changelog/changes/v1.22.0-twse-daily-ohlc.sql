--liquibase formatted sql

--changeset steven:v1.22.0-twse-daily-ohlc
--comment 台股大盤日線補 OHLC 三欄（open/high/low），供觀察清單 0000 KD 計算與大盤 K 線顯示
-- TWSE FMTQIK 月報原本就有 OpeningIndex / HighestIndex / LowestIndex，先前只取 ClosingIndex
-- 舊資料 OHLC 維持 null；下次按「回補資料」會 upsert 補完

ALTER TABLE twse_index_daily_history
    ADD COLUMN open_point  NUMERIC(12,2),
    ADD COLUMN high_point  NUMERIC(12,2),
    ADD COLUMN low_point   NUMERIC(12,2);
