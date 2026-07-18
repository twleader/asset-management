--liquibase formatted sql

--changeset steven:v1.64.0-crawler-export-path
--comment Requirement 38（Task 212）：公開資訊爬蟲（NewsPoller）輸出檔案路徑設定，由「爬蟲資訊查詢」頁維護。一爬蟲一列（全域設定、無 owner），NewsPoller 每輪寫 public_info_<日期>.json 前讀取。只存「相對子路徑」，實際目錄＝容器基底 EXPORT_OUTPUT_DIR（/home/steven ← host /Users/steven）resolve 之。Seed 'Project/SRPP/data/input' 等同改為 DB 驅動前 /srpp-input volume 的同一個 host 目錄（/Users/steven/Project/SRPP/data/input），行為不變。本 changeset 刻意寫成冪等（IF NOT EXISTS／ON CONFLICT），理由同 v1.58.0-crawler-schedule：本檔原編為 v1.63.0 並已在開發 DB 執行過，因另一個 worktree 先占用 v1.63.0（v1.63.0-index-export-schedule，14:05 已 EXECUTED）而改號避讓 → changeset id 隨之改變、Liquibase 視為新 changeset 會重跑，故建表與 seed 皆須容忍既有資料。全新資料庫走一般建表路徑，行為不變。
CREATE TABLE IF NOT EXISTS crawler_export_setting (
    id             BIGSERIAL PRIMARY KEY,
    crawler_key    VARCHAR(64)  NOT NULL,
    output_subpath VARCHAR(512) NOT NULL,
    updated_at     TIMESTAMP,
    CONSTRAINT uq_crawler_export_setting_key UNIQUE (crawler_key)
);

INSERT INTO crawler_export_setting (crawler_key, output_subpath, updated_at) VALUES
    ('news-poller', 'Project/SRPP/data/input', NOW())
ON CONFLICT (crawler_key) DO NOTHING;
