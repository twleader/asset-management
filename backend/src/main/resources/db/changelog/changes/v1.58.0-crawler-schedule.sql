--liquibase formatted sql

--changeset steven:v1.58.0-crawler-schedule
--comment Requirement 38（Task 192）：公開資訊爬蟲（NewsPoller）執行時間設定，由「爬蟲資訊查詢」頁維護。一列一時間點（全域設定、無 owner），NewsPoller 每分鐘讀已啟用列比對當前 HH:mm 觸發。Seed 預設 08:20/11:30/18:00 等同原寫死 cron 行為（Task 184／188）。本 changeset 刻意寫成冪等（IF NOT EXISTS／ON CONFLICT）：開發環境曾以已作廢的 changeset id v1.55.0-crawler-schedule 建過同一張表並 seed 舊時點 08:00/12:00/18:00（該檔已改名為本檔、id 隨之改變，Liquibase 視為新 changeset 會重跑），故需容忍表已存在並把殘留的舊 seed 對齊為現行時點。全新資料庫走一般建表路徑，行為不變。
CREATE TABLE IF NOT EXISTS crawler_schedule (
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
CREATE INDEX IF NOT EXISTS idx_crawler_schedule_key ON crawler_schedule (crawler_key);

INSERT INTO crawler_schedule (crawler_key, run_hour, run_minute, enabled) VALUES
    ('news-poller', 8, 20, TRUE),
    ('news-poller', 11, 30, TRUE),
    ('news-poller', 18, 0, TRUE)
ON CONFLICT (crawler_key, run_hour, run_minute) DO NOTHING;

-- 清掉舊 seed 殘留（僅開發環境會命中）：08:00／12:00 為 v1.55.0 時期的時點，現行為 08:20／11:30。
-- 全新資料庫無此兩列，為 no-op。此處只刪「與舊 seed 完全相同」的列，不動使用者自行新增的時間點。
DELETE FROM crawler_schedule
 WHERE crawler_key = 'news-poller'
   AND ((run_hour = 8 AND run_minute = 0) OR (run_hour = 12 AND run_minute = 0));
