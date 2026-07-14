--liquibase formatted sql

--changeset steven:v1.55.0-foreign-stock-daily
--comment 海外參考個股每日收盤（韓股：三星電子 005930 / SK 海力士 000660），供公開資訊爬蟲組韓股快照（category=kr-market）
-- 資料來源：Yahoo Finance v8 chart API（005930.KS / 000660.KS，時區 Asia/Seoul、幣別 KRW），由 external-materials 的 KrStockPoller 抓取後直寫（JdbcTemplate，無 JPA entity）
-- 與 us_index_daily_history 分表：那張語意為「指數」；與 stock_price_history 分表：那張為投組個股（market 分類驅動、涉持股/觀察）。本表僅存固定的海外參考個股收盤，語意獨立、不污染既有兩條線
-- 僅存 close_point（韓股快照只需最新＋前一交易日收盤算漲跌%）；不 seed 歷史值，由排程/開機 warmup 抓近日收盤

CREATE TABLE foreign_stock_daily_history (
    stock_code   VARCHAR(16)   NOT NULL,
    trading_date DATE          NOT NULL,
    close_point  NUMERIC(18,4) NOT NULL,
    PRIMARY KEY (stock_code, trading_date)
);
