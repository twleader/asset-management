--liquibase formatted sql

--changeset steven:v1.45.0-news-headline
--comment 本地財經新聞爬蟲（Requirement 31 / Task 149.21）：external-materials-service 抓取權威來源（鉅亨網/自由時報/經濟日報）＋證交所公開資訊（三大法人買賣超、大盤成交統計）寫入本表，供 MarketAnalysisService 餵入今日股市分析 prompt，以降低/可關閉付費 web_search。全域參考資料，無 owner 欄位（比照 twse_index_daily_history / stock_dividend_history）。ext 以 JdbcTemplate 直寫、backend 以 JPA 讀。

CREATE TABLE news_headline (
    id            BIGSERIAL     PRIMARY KEY,
    title         VARCHAR(500)  NOT NULL,
    source        VARCHAR(100)  NOT NULL,
    url           VARCHAR(1024) NOT NULL,
    category      VARCHAR(32)   NOT NULL,
    region        VARCHAR(16),
    summary       TEXT,
    published_at  TIMESTAMPTZ   NOT NULL,
    fetched_at    TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    dedupe_key    VARCHAR(64)   NOT NULL
);

CREATE UNIQUE INDEX uk_news_headline_dedupe ON news_headline (dedupe_key);
CREATE INDEX idx_news_headline_recent ON news_headline (published_at DESC);
CREATE INDEX idx_news_headline_cat ON news_headline (category, published_at DESC);
