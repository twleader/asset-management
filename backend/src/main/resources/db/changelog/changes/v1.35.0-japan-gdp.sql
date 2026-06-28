--liquibase formatted sql

--changeset steven:v1.35.0-japan-gdp
--comment 日本人均 GDP（USD）+ 實質 GDP 成長率年度歷史；資料由「股市分析」頁之「回補 GDP（IMF）」按鈕從 IMF DataMapper API（NGDPDPC/JPN、NGDP_RPCH/JPN）回補。DGBAS 無日本資料，故純 IMF（比照韓國）。

CREATE TABLE japan_gdp_per_capita_history (
    year                 INTEGER PRIMARY KEY,
    gdp_usd              NUMERIC(12,2) NOT NULL,
    real_gdp_growth_rate NUMERIC(8,4)
);
