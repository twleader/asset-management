--liquibase formatted sql

--changeset steven:v1.37.0-daily-market-analysis
--comment 今日股市分析（Requirement 31）：每交易日 07:30 由 Claude Opus 4.8 綜合台股/美股近一年日線走勢 + 近期財經新聞（模型 web_search）判斷當天台股走向。全域參考資料，無 owner 欄位（比照 twse_index_daily_history / us_index_daily_history）。每交易日一筆，analysis_date 主鍵；同日重跑覆蓋。

CREATE TABLE daily_market_analysis (
    analysis_date    DATE          PRIMARY KEY,
    bias             VARCHAR(16),
    confidence       INTEGER,
    summary          TEXT,
    key_factors      TEXT,
    news_highlights  TEXT,
    tw_context       TEXT,
    us_context       TEXT,
    model            VARCHAR(64),
    status           VARCHAR(16)   NOT NULL,
    error_message    TEXT,
    raw_response     TEXT,
    generated_at     TIMESTAMPTZ   NOT NULL
);
