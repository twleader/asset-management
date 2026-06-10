--liquibase formatted sql

--changeset steven:v1.27.0-us-index-daily
--comment 美股四大指數（道瓊 DJI / 標普500 SPX / 那斯達克綜合 IXIC / 費城半導體 SOX）每日 OHLC，供 GDP+大盤頁面切換顯示近 10 年日線 + MA20/60/240
-- 資料來源：Yahoo Finance v8 chart API（^DJI / ^GSPC / ^IXIC / ^SOX，range=10y&interval=1d）
-- 與 twse_index_daily_history 分表：台股大盤為單一指數（無 code 欄）且已與觀察清單 0000 耦合，分表不動既有流程
-- 不 seed 歷史值；由使用者於頁面切到該指數後按「回補日線（10 年）」觸發抓取

CREATE TABLE us_index_daily_history (
    index_code    VARCHAR(16)   NOT NULL,
    trading_date  DATE          NOT NULL,
    open_point    NUMERIC(14,4),
    high_point    NUMERIC(14,4),
    low_point     NUMERIC(14,4),
    close_point   NUMERIC(14,4) NOT NULL,
    PRIMARY KEY (index_code, trading_date)
);
