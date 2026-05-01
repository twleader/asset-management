--liquibase formatted sql

--changeset steven:v1.17.0-korea-gdp
--comment 韓國人均 GDP（USD）年度歷史；資料由「GDP + 台股大盤」頁面之回補按鈕從 IMF DataMapper API 回補。

CREATE TABLE korea_gdp_per_capita_history (
    year     INTEGER PRIMARY KEY,
    gdp_usd  NUMERIC(12,2) NOT NULL
);
