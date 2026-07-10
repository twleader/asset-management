--liquibase formatted sql

--changeset steven:v1.46.0-tw-market-closure
--comment 台股颱風假 / 臨時休市（Requirement 7 / Task 160）：證交所颱風天是否休市，法規上取決於臺北市政府是否宣布停止上班；此類臨時休市不在 TWSE 年度 holidaySchedule（年初即公告的固定假期）中。external-materials-service 於台股開盤前每 15 分鐘爬 DGPA「天然災害停止上班及上課情形」判臺北市停班，命中即 upsert 本表；MarketDataFetchService.getTwHolidays 將本表 union 進台股假日唯一入口，令 market-status / 抓價 / 收盤 / 警示 / 備份 / 07:30 分析 / 交易日曆一體休市。全域參考資料、無 owner（比照 news_headline / twse_index_daily_history）。ext 以 JdbcTemplate 直寫、backend 經 /internal/tw-holidays 讀。

CREATE TABLE tw_market_closure (
    closure_date  DATE          PRIMARY KEY,
    reason        VARCHAR(200)  NOT NULL,
    source        VARCHAR(32)   NOT NULL,
    raw_status    VARCHAR(500),
    detected_at   TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);
