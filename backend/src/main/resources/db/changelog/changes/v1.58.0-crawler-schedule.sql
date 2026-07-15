--liquibase formatted sql

--changeset steven:v1.58.0-crawler-schedule
--comment Requirement 38（Task 192）：公開資訊爬蟲（NewsPoller）執行時間設定，由「爬蟲資訊查詢」頁維護。一列一時間點（全域設定、無 owner），NewsPoller 每分鐘讀已啟用列比對當前 HH:mm 觸發。Seed 預設 08:20/11:30/18:00 等同原寫死 cron 行為（Task 184／188）。
CREATE TABLE crawler_schedule (
    id           BIGSERIAL PRIMARY KEY,
    crawler_key  VARCHAR(64) NOT NULL,
    run_hour     INT         NOT NULL,
    run_minute   INT         NOT NULL,
    enabled      BOOLEAN     NOT NULL DEFAULT TRUE,
    updated_at   TIMESTAMP,
    CONSTRAINT uq_crawler_schedule_key_time UNIQUE (crawler_key, run_hour, run_minute),
    CONSTRAINT ck_crawler_schedule_hour CHECK (run_hour BETWEEN 0 AND 23),
    CONSTRAINT ck_crawler_schedule_minute CHECK (run_minute BETWEEN 0 AND 59)
);
CREATE INDEX idx_crawler_schedule_key ON crawler_schedule (crawler_key);

INSERT INTO crawler_schedule (crawler_key, run_hour, run_minute, enabled) VALUES
    ('news-poller', 8, 20, TRUE),
    ('news-poller', 11, 30, TRUE),
    ('news-poller', 18, 0, TRUE);
