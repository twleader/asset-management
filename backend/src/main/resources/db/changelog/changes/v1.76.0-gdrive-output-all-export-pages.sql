--liquibase formatted sql

--changeset steven:v1.76.0-gdrive-output-all-export-pages
--comment Requirement 51（Task 242）：把 Requirement 50 的「本機照寫＋Google Drive 附加副本」模型推廣到其餘八個匯出頁——歷年資產（R34）／已實現損益（R39）／交易紀錄（R49）／油價金價（R41）／台幣兌美元匯率（R42）／GDP-TWSE（R45）／交易雷達（R48）／交易日曆（R37）。八張設定表各加同一組四個欄位。語意與 v1.75.0 的 crawler_export_setting 完全相同：本機輸出行為完全不變、一律照寫，gdrive_enabled 為真時才在本機檔寫成功後多上傳一份副本；不提供「只寫 Drive」的選項（這八頁匯出的是使用者的資產快照、已實現損益、交易紀錄等財務報表，本機那一份是既有的留存機制）。與 crawler_export_setting 的關鍵差異：那張表是全域無 owner，這八張全部帶 owner_user_id ＋ @Filter(ownerFilter)（per-user）。由此衍生一條隱私硬約束——rclone remote 全機只有一份、綁定某一個特定 Google 帳號，若允許其他使用者啟用，B 的財務報表就會被上傳到那個帳號的雲端硬碟，故 Drive 開關只有「主要管理者」（ADMIN_EMAIL，經 UserAdminService.isConfiguredAdmin 判定，非 role==ADMIN——role 可有多列）能啟用，且背景排程須逐列再驗一次。gdrive_last_run_at／gdrive_last_status 與既有的 last_run_at／last_run_status 刻意分離、不得併入：「本機成功、Drive 失敗」是正常且必須可分辨的狀態，共用一欄會讓本機明明寫成功卻顯示失敗，使用者去做不必要的排查。seed 不啟用任何一列（不寫任何 UPDATE ... SET gdrive_enabled = true），既有部署升級後行為與現況完全一致、不要求 rclone remote 存在。全部語句寫成冪等，避免日後版號避讓改名時 Liquibase 視為新 migration 重跑而失敗。
ALTER TABLE export_schedule_setting ADD COLUMN IF NOT EXISTS gdrive_enabled BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE export_schedule_setting ADD COLUMN IF NOT EXISTS gdrive_subpath VARCHAR(512);
ALTER TABLE export_schedule_setting ADD COLUMN IF NOT EXISTS gdrive_last_run_at TIMESTAMP;
ALTER TABLE export_schedule_setting ADD COLUMN IF NOT EXISTS gdrive_last_status VARCHAR(512);

ALTER TABLE realized_gain_export_schedule ADD COLUMN IF NOT EXISTS gdrive_enabled BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE realized_gain_export_schedule ADD COLUMN IF NOT EXISTS gdrive_subpath VARCHAR(512);
ALTER TABLE realized_gain_export_schedule ADD COLUMN IF NOT EXISTS gdrive_last_run_at TIMESTAMP;
ALTER TABLE realized_gain_export_schedule ADD COLUMN IF NOT EXISTS gdrive_last_status VARCHAR(512);

ALTER TABLE asset_transaction_export_schedule ADD COLUMN IF NOT EXISTS gdrive_enabled BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE asset_transaction_export_schedule ADD COLUMN IF NOT EXISTS gdrive_subpath VARCHAR(512);
ALTER TABLE asset_transaction_export_schedule ADD COLUMN IF NOT EXISTS gdrive_last_run_at TIMESTAMP;
ALTER TABLE asset_transaction_export_schedule ADD COLUMN IF NOT EXISTS gdrive_last_status VARCHAR(512);

ALTER TABLE commodity_export_schedule ADD COLUMN IF NOT EXISTS gdrive_enabled BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE commodity_export_schedule ADD COLUMN IF NOT EXISTS gdrive_subpath VARCHAR(512);
ALTER TABLE commodity_export_schedule ADD COLUMN IF NOT EXISTS gdrive_last_run_at TIMESTAMP;
ALTER TABLE commodity_export_schedule ADD COLUMN IF NOT EXISTS gdrive_last_status VARCHAR(512);

ALTER TABLE exchange_rate_export_schedule ADD COLUMN IF NOT EXISTS gdrive_enabled BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE exchange_rate_export_schedule ADD COLUMN IF NOT EXISTS gdrive_subpath VARCHAR(512);
ALTER TABLE exchange_rate_export_schedule ADD COLUMN IF NOT EXISTS gdrive_last_run_at TIMESTAMP;
ALTER TABLE exchange_rate_export_schedule ADD COLUMN IF NOT EXISTS gdrive_last_status VARCHAR(512);

ALTER TABLE index_export_schedule ADD COLUMN IF NOT EXISTS gdrive_enabled BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE index_export_schedule ADD COLUMN IF NOT EXISTS gdrive_subpath VARCHAR(512);
ALTER TABLE index_export_schedule ADD COLUMN IF NOT EXISTS gdrive_last_run_at TIMESTAMP;
ALTER TABLE index_export_schedule ADD COLUMN IF NOT EXISTS gdrive_last_status VARCHAR(512);

ALTER TABLE trading_radar_export_setting ADD COLUMN IF NOT EXISTS gdrive_enabled BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE trading_radar_export_setting ADD COLUMN IF NOT EXISTS gdrive_subpath VARCHAR(512);
ALTER TABLE trading_radar_export_setting ADD COLUMN IF NOT EXISTS gdrive_last_run_at TIMESTAMP;
ALTER TABLE trading_radar_export_setting ADD COLUMN IF NOT EXISTS gdrive_last_status VARCHAR(512);

ALTER TABLE trading_calendar_export_schedule ADD COLUMN IF NOT EXISTS gdrive_enabled BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE trading_calendar_export_schedule ADD COLUMN IF NOT EXISTS gdrive_subpath VARCHAR(512);
ALTER TABLE trading_calendar_export_schedule ADD COLUMN IF NOT EXISTS gdrive_last_run_at TIMESTAMP;
ALTER TABLE trading_calendar_export_schedule ADD COLUMN IF NOT EXISTS gdrive_last_status VARCHAR(512);

COMMENT ON COLUMN export_schedule_setting.gdrive_enabled IS
  '是否在本機檔寫成功後額外上傳一份副本到 Google Drive。false（預設）＝只寫本機，行為與 Requirement 51 之前完全相同。僅「主要管理者」（ADMIN_EMAIL）可啟用——rclone remote 全機只有一份且綁定特定 Google 帳號，若他人啟用，其財務報表會被上傳到該帳號。';

COMMENT ON COLUMN export_schedule_setting.gdrive_subpath IS
  'Google Drive 上的相對子路徑（基底為 rclone remote，名稱由環境變數 GDRIVE_OUTPUT_REMOTE 指定）。啟用時必填。驗證：不得以 / 開頭、不得含 .. 路徑段、不得含冒號（會被 rclone 解讀為切換 remote）。';

COMMENT ON COLUMN export_schedule_setting.gdrive_last_run_at IS
  '上次 Drive 上傳的判斷時間（含成功／失敗／跳過，非僅成功）。與 last_run_at 分離：本機成功而 Drive 失敗是正常且必須可分辨的狀態。';

COMMENT ON COLUMN export_schedule_setting.gdrive_last_status IS
  '上次 Drive 上傳結果，截斷至 512 字元內。成功記落點與檔案大小；rclone 非零退出記「失敗：…」；逾時記「逾時（N 秒）：Drive 端可能已完成」（逾時的判準是行程未在時限內 exit，不是檔案沒上去——實測發生過狀態記失敗但 Drive 上檔案完整的假失敗）；已啟用但未實際上傳記「跳過：…原因」。';
