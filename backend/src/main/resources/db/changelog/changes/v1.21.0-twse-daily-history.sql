--liquibase formatted sql

--changeset steven:v1.21.0-twse-daily-history
--comment 台股大盤（TAIEX）每日收盤點位歷史，供 GDP+台股大盤頁面繪製近 10 年日線 + MA20/60/240
-- 資料來源：TWSE FMTQIK 月報 (https://www.twse.com.tw/exchangeReport/FMTQIK)
-- 不 seed 歷史值；由使用者按「回補資料」觸發 MacroHistoryService.refreshTwseDaily 抓取

CREATE TABLE twse_index_daily_history (
    trading_date  DATE PRIMARY KEY,
    close_point   NUMERIC(12,2) NOT NULL
);
