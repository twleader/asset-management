--liquibase formatted sql

--changeset steven:v1.63.0-index-export-schedule
--comment Requirement 43（Task 209）：股市大盤指數日線每日排程自動匯出，per-user 設定表。結構比照 v1.62.0 的 exchange_rate_export_schedule（每個 owner 一列、owner_user_id UNIQUE、@Filter(ownerFilter) 隔離，存啟用開關、每日執行時分、輸出相對子路徑、匯出範圍月數、上次執行日期(當日 guard)/時間/結果）。range_months 為 NULL 代表匯出全部十年；非 NULL 則以「執行當日往前推 N 個月」計算起訖，使留存檔隨時間滾動。同 Requirement 41/42：指數日線為全域公開行情（twse_index_daily_history 與 us_index_daily_history 皆無 owner_user_id、無 @Filter），背景 cron 直接產檔即可，不需要 exportXxxForOwner(ownerId) 手動 enableFilter——排程設定 per-user，但資料本身全域。與 Requirement 42（單一幣別頁刻意不設 currency 欄）相反，本表設 market 欄：本頁下拉本來就有 9 個指數可選，「匯出哪一個」是使用者當下的實際選擇，不是為不存在的需求預留。market 刻意不設 CHECK 約束——合法代碼清單的單一來源在 Java 端 MacroHistoryService.OVERSEAS_INDEX_CODES，寫進 DDL 會變成第二份清單，日後新增指數要改兩處且漏改只在 runtime 才炸；改由 service 層白名單驗證。本 changeset 寫成冪等（CREATE TABLE IF NOT EXISTS），避免日後版號避讓改名導致 changeset id 變動、Liquibase 視為新 changeset 重跑時因表已存在而失敗。表內僅使用者自建設定、無 seed，故毋須補償語句。
CREATE TABLE IF NOT EXISTS index_export_schedule (
    id              BIGSERIAL PRIMARY KEY,
    owner_user_id   BIGINT       NOT NULL,
    enabled         BOOLEAN      NOT NULL DEFAULT FALSE,
    run_hour        INT          NOT NULL DEFAULT 8,
    run_minute      INT          NOT NULL DEFAULT 0,
    market          VARCHAR(16)  NOT NULL DEFAULT 'TWSE',
    output_subpath  VARCHAR(255) NOT NULL DEFAULT 'input',
    range_months    INT,
    last_run_date   DATE,
    last_run_at     TIMESTAMP,
    last_run_status VARCHAR(500),
    updated_at      TIMESTAMP,
    CONSTRAINT uq_index_export_schedule_owner UNIQUE (owner_user_id),
    CONSTRAINT ck_index_export_schedule_hour CHECK (run_hour BETWEEN 0 AND 23),
    CONSTRAINT ck_index_export_schedule_minute CHECK (run_minute BETWEEN 0 AND 59),
    CONSTRAINT ck_index_export_schedule_range CHECK (range_months IS NULL OR range_months BETWEEN 1 AND 120)
);
