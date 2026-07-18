--liquibase formatted sql

--changeset steven:v1.62.0-exchange-rate-export-schedule
--comment Requirement 42（Task 204）：台幣兌美元匯率每日排程自動匯出，per-user 設定表。結構與 v1.61.0 的 commodity_export_schedule 相同（每個 owner 一列、owner_user_id UNIQUE、@Filter(ownerFilter) 隔離，存啟用開關、每日執行時分、輸出相對子路徑、匯出範圍月數、上次執行日期(當日 guard)/時間/結果）。range_months 為 NULL 代表匯出全部十年；非 NULL 則以「執行當日往前推 N 個月」計算起訖，使留存檔隨時間滾動。同 Requirement 41：匯率為全域公開資料（exchange_rate_history 無 owner_user_id、無 @Filter），背景 cron 直接產檔即可，不需要 exportXxxForOwner(ownerId) 手動 enableFilter——排程設定 per-user，但資料本身全域。刻意不設 currency 欄：本頁為「台幣兌美元」單一幣別頁，服務層以常數 USD 產檔，加欄等於為不存在的多幣別頁預留未使用欄位；日後真要多幣別再加欄補 migration。本 changeset 寫成冪等（CREATE TABLE IF NOT EXISTS），避免日後版號避讓改名導致 changeset id 變動、Liquibase 視為新 changeset 重跑時因表已存在而失敗。表內僅使用者自建設定、無 seed，故毋須補償語句。
CREATE TABLE IF NOT EXISTS exchange_rate_export_schedule (
    id              BIGSERIAL PRIMARY KEY,
    owner_user_id   BIGINT       NOT NULL,
    enabled         BOOLEAN      NOT NULL DEFAULT FALSE,
    run_hour        INT          NOT NULL DEFAULT 8,
    run_minute      INT          NOT NULL DEFAULT 0,
    output_subpath  VARCHAR(255) NOT NULL DEFAULT 'input',
    range_months    INT,
    last_run_date   DATE,
    last_run_at     TIMESTAMP,
    last_run_status VARCHAR(500),
    updated_at      TIMESTAMP,
    CONSTRAINT uq_exchange_rate_export_schedule_owner UNIQUE (owner_user_id),
    CONSTRAINT ck_exchange_rate_export_schedule_hour CHECK (run_hour BETWEEN 0 AND 23),
    CONSTRAINT ck_exchange_rate_export_schedule_minute CHECK (run_minute BETWEEN 0 AND 59),
    CONSTRAINT ck_exchange_rate_export_schedule_range CHECK (range_months IS NULL OR range_months BETWEEN 1 AND 120)
);
