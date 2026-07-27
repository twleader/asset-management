--liquibase formatted sql

--changeset steven:v1.75.0-crawler-gdrive-output
--comment Requirement 50（Task 240）：爬蟲輸出檔案同步上傳 Google Drive。本欄位組是「附加」而非「替換」——本機 output_subpath 的寫入行為完全不變、一律照寫，gdrive_enabled 為真時才在本機檔寫成功後多上傳一份副本到使用者的 Google 雲端硬碟。刻意不做成「儲存目標」單選：SRPP 退休規劃專案依賴本機 Project/SRPP/data/input/public_info_<日期>.json，單選會讓使用者選了 Drive 就靜默切斷 SRPP 的資料來源（症狀是 SRPP 讀到過期檔案而不報錯）。gdrive_enabled 用布林而非新增「儲存目標」分類主檔表：CLAUDE.md「禁止 Enum 寫死」針對的是使用者會自行增修的業務分類（銀行／券商／存款類型／市場類型），而「要不要多上傳一份到 Drive」對應的是程式中一條具體的 rclone code path，DB 多一列並不會讓程式自動支援新的儲存後端，建主檔表只是假的擴充性。gdrive_last_run_at／gdrive_last_status 記錄執行結果而非衍生值（比照既有排程設定表的 last_run_* 慣例）：上傳目的地不在使用者眼前的檔案系統，若不回報狀態，「檔案沒上去」只會體現為 Drive 上少一個檔案，使用者無從得知。seed 刻意不啟用（不寫任何 UPDATE ... SET gdrive_enabled = true），既有部署升級後行為與現況完全一致，且不要求 rclone remote 存在。全部語句寫成冪等，避免日後版號避讓改名時 Liquibase 視為新 migration 重跑而失敗。
ALTER TABLE crawler_export_setting ADD COLUMN IF NOT EXISTS gdrive_enabled BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE crawler_export_setting ADD COLUMN IF NOT EXISTS gdrive_subpath VARCHAR(512);
ALTER TABLE crawler_export_setting ADD COLUMN IF NOT EXISTS gdrive_last_run_at TIMESTAMP;
ALTER TABLE crawler_export_setting ADD COLUMN IF NOT EXISTS gdrive_last_status VARCHAR(512);

COMMENT ON COLUMN crawler_export_setting.gdrive_enabled IS
  '是否在本機檔寫成功後，額外上傳一份副本到 Google Drive。false（預設）＝只寫本機，行為與 Task 212 完全相同。本欄為「附加」開關，不是儲存目標單選——本機一律照寫，SRPP 依賴那一份。';

COMMENT ON COLUMN crawler_export_setting.gdrive_subpath IS
  'Google Drive 上的相對子路徑（例 投資理財/資產管理），基底為 rclone remote（名稱由環境變數 GDRIVE_OUTPUT_REMOTE 指定，預設 GDriveOutput）。只存相對子路徑、不存絕對路徑或含 remote 前綴的完整 spec，理由同 output_subpath。gdrive_enabled 為真時必填（service 層驗證，空值回 400）。';

COMMENT ON COLUMN crawler_export_setting.gdrive_last_run_at IS
  '上次 Drive 上傳的判斷時間（含跳過與失敗，非僅成功）。由 external-materials-service 的 NewsPoller 寫入——刻意的所有權例外：上傳結果只有 ext 知道，沒有別的地方能寫；ext 只碰本欄與 gdrive_last_status，列的所有權仍在 backend（UPDATE 命中 0 列時只 warn，不得 upsert）。';

COMMENT ON COLUMN crawler_export_setting.gdrive_last_status IS
  '上次 Drive 上傳結果：成功記落點與檔案大小、失敗記錯誤摘要、跳過記原因（子路徑不合法／config 不可用／本機檔寫入失敗）。截斷至 512 字元內。凡「已啟用 Drive 但未實際上傳」也必須寫入，否則本欄會停在上一次的成功、顯示過期的好消息。';
