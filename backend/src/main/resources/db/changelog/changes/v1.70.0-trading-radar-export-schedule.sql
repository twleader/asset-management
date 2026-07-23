--liquibase formatted sql

--changeset steven:v1.70.0-trading-radar-export-schedule
--comment Requirement 48 追加（Task 231）：交易雷達排程自動匯出到指定伺服器目錄。時間點與輸出資料夾刻意分兩張表——
--comment 併表會使同一路徑隨時間點列數重複儲存同一事實（CLAUDE.md 資料庫完整正規化），且刪一個時間點會連帶
--comment 弄丟路徑（沿用 crawler_schedule / crawler_export_setting 的既有分表理由）。
--comment last_run_date 放在時間點列上：當日 guard 必須 per 時間點，否則同日設多個時間點只會跑第一個。
--comment 全部語句冪等（IF NOT EXISTS）：本專案曾因版號避讓改 changeset id，Liquibase 視為新 migration 重跑，
--comment 非冪等語句會失敗並造成服務 crash loop。

CREATE TABLE IF NOT EXISTS trading_radar_export_time (
    id            BIGSERIAL PRIMARY KEY,
    owner_user_id BIGINT      NOT NULL,
    run_hour      INT         NOT NULL,
    run_minute    INT         NOT NULL,
    enabled       BOOLEAN     NOT NULL DEFAULT TRUE,
    last_run_date DATE,
    updated_at    TIMESTAMP,
    CONSTRAINT uq_tr_export_time_owner_time UNIQUE (owner_user_id, run_hour, run_minute),
    CONSTRAINT ck_tr_export_time_hour   CHECK (run_hour BETWEEN 0 AND 23),
    CONSTRAINT ck_tr_export_time_minute CHECK (run_minute BETWEEN 0 AND 59)
);
CREATE INDEX IF NOT EXISTS idx_tr_export_time_owner ON trading_radar_export_time (owner_user_id);

CREATE TABLE IF NOT EXISTS trading_radar_export_setting (
    id              BIGSERIAL PRIMARY KEY,
    owner_user_id   BIGINT       NOT NULL,
    output_subpath  VARCHAR(512) NOT NULL,
    last_run_at     TIMESTAMP,
    last_run_status VARCHAR(500),
    updated_at      TIMESTAMP,
    CONSTRAINT uq_tr_export_setting_owner UNIQUE (owner_user_id)
);

COMMENT ON TABLE trading_radar_export_time IS
  '交易雷達排程匯出的執行時間點（Requirement 48 追加／Task 231）。一列一時間點、per owner；比照公開資訊爬蟲可設定多個。';
COMMENT ON COLUMN trading_radar_export_time.last_run_date IS
  '當日 guard，per 時間點（不是 per owner，否則同日多時間點只會跑第一個）。成功或失敗都設為當日，避免命中分鐘後每 poll 重試整天。';
COMMENT ON TABLE trading_radar_export_setting IS
  '交易雷達排程匯出的輸出資料夾與上次執行狀態（Requirement 48 追加／Task 231）。一使用者一列。';
COMMENT ON COLUMN trading_radar_export_setting.output_subpath IS
  '輸出目錄相對子路徑（相對容器基底 EXPORT_OUTPUT_DIR=/home/steven，docker volume 對映主機家目錄）。只存相對子路徑：絕對路徑不可攜且繞過基底防護，一律於 service 層擋下。';
