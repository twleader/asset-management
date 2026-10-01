# [t468] 修正 Google Drive 備份目的地與手動／每日備份失敗

**對應 Requirements:** Requirement 15／Task 468 修訂（資料庫加密備份目的地、巢狀身分守門與全流程逾時）
**前置任務:** t388
**Liquibase changeset:** 無

## 背景

2026-09-28 線上手動備份多次 `pg_dump` 成功產生 13,637,225 bytes，但早期 API 呼叫在 Google Drive 上傳前回 503。錯誤訊息指出 `GoogleDriver:asset-management-backup` 不存在。線上 `ProcessBackupRemoteClient` 仍只允許這個位於 My Drive 根目錄的 exact raw root；使用者確認預期目的地是 My Drive 的「投資理財／資產管理」。host `[GoogleDriver]` 最初使用 `drive.file`，無法列舉使用者手動建立的資料夾；依使用者明確授權，scope 已改為 `drive` 並以相同帳號重新授權。其後業務服務日誌顯示手動檔 `asset_manual_20260928_201113.dump`（13,637,225 bytes）於 20:11:13 Taipei 開始，20:13:42 Taipei 記錄 uploaded/indexed，DB 同時有該檔的 `backup_record`；這只證明服務端完成記錄，尚無該物件的獨立 Drive readback，且使用者仍回報 UI 失敗。現行加密 remote 與 gate 仍指向舊 `asset-management-backup` root，所以新目標問題尚未修復。實際運行中的 Nginx 對一般 `/api/` 僅設 60 秒 `proxy_read_timeout`，備份路由沒有專屬 timeout；前端手動備份 Axios timeout 為 120 秒。149 秒的服務端完成時間超過代理 timeout，與 UI 先失敗、服務端之後完成相符，但沒有讀到該次 Nginx access/error log 證明它實際回了 504；驗收須分別確認 HTTP 結果、DB row 與 Drive object。每日排程與手動備份共用同一個 upload gate；非交易日跳過仍維持既有日曆規則。

2026-10-01 唯讀盤點已見 DB 有 21 筆索引、舊 crypt root 至少有一筆手動備份、新目標的 raw `backups/` 暫無可列舉物件，舊 crypt `weekly/` 回目錄不存在。這些證據尚不足以安全切換或清除 DB 索引；須先完成逐筆對帳，查明索引中缺少的物件。任何資料缺口都保留為待處理項，不用 sync 自動刪除。

## 要做什麼

- [ ] 468.1 由維運者於 host 驗證 `[GoogleDriver]` 綁定 `shi.chihung@gmail.com` 且 OAuth scope 為 `drive`，並把 `[gdrive-crypt]` 的 raw backing base 改為使用者指定的 My Drive 路徑 `GoogleDriver:投資理財/資產管理`；程式不得自行修改正式 rclone config；執行期同名目錄不能單獨證明帳號，帳號身分須在切換前由維運者獨立核實並保留紀錄。保留 `[gdrive-crypt]` 的加密密碼與 `backups/{manual,daily,weekly,monthly}` 相對目錄；不得改用未加密的 `[GDriveOutput]`。先完成 468.4 的唯讀盤點與安全遷移，再切換正式 backing base。
- [ ] 468.2 將備份 remote gate 改為先解析同一 writable config，驗證 `[GoogleDriver]` 的 type／scope 與 `[gdrive-crypt]` 的 type／`remote` exact 等於 `GoogleDriver:投資理財/資產管理`，再精確驗證巢狀目錄：同一 verified session／writable config 下先對 `GoogleDriver:` dirs-only 完整列舉並 exact match `投資理財/`，再對 `GoogleDriver:投資理財/` dirs-only 完整列舉並 exact match `資產管理/`。兩次列舉合為一次 gate attempt，沿用既有最多兩次 attempts／一次 auth recovery；設定不符、任一層不存在、清單不完整、授權不足或遠端錯誤時，在任何 crypt copy/list/delete、索引同步或還原前安全 503 fail closed。不得自動建立目錄，不得接受相似名稱或其他父路徑下同名目錄，`sync` 不得把失敗當空清單而清掉 DB 索引。
- [ ] 468.3 更新安全錯誤訊息及 Requirement 15／設計／技術指引，明確指出預期路徑、完整 Drive scope 與 host 重新授權方式；不得輸出 OAuth token、secret、crypt password 或原始 stderr。維持每個 remote workflow source fingerprint 熱重載，不要求只為設定變更 recreate `business-services`。
- [ ] 468.4 切換目的地前，唯讀盤點舊 `GoogleDriver:asset-management-backup`、新 `GoogleDriver:投資理財/資產管理` 與 `backup_record` 的 exact `(folder, filename)`。使用明確區分舊來源與新目的地、但相同 crypt 密碼的路徑／設定做遷移；不得在改掉單一 `gdrive-crypt` backing base 後，誤把兩端都指向新 root。舊 root 若完整列舉確認不存在，逐筆核對 DB 已列冊檔案在新目標可讀、可解密且可還原，缺檔時停止並報出 exact filename。若舊 root 有仍列於 DB 或可還原的備份而目標缺少，先暫停所有備份／還原／同步／輪替寫入並確認無在途工作，維持排他寫入，逐筆先確認目標 exact filename 不存在，再以具拒絕覆寫保證且不刪來源的方式複製，並讀回確認可列舉、可解密、可還原。任何帳號、scope、清單完整性或 crypt 可讀性不明、以及 rate limit，均停止遠端 mutation；不得用空或部分 remote listing 清除 DB 索引。新目標既有同名檔案不得覆寫，衝突時停止並回報 exact filename。無論舊 root 存在或明確不存在，切換前對 DB 每筆 exact `(folder, filename)` 均須在新目標獨立驗證可讀、可解密、可還原；缺檔列 exact filename、停止切換且保留索引。
- [ ] 468.5 保持 API endpoints／DTO、DB schema、每日排程時間、市場交易日判斷及 retention 語意不變；UI 僅依 468.7 新增路徑欄。修正後確認手動備份可上傳、每日／每週排程可使用同一個 verified remote session；今日是否執行依市場日曆決定，不為補跑而略過交易日閘門。
- [ ] 468.6 加入 end-to-end workflow deadline，所有 create workflow（手動、每日、每週、既有 monthly 若有啟用，以及 restore 內建的自救點）各自最長 900 秒；restore、sync、PUT retention 最長 1,500 秒。排程備份超時須終止並等待子程序退出、以安全錯誤記錄失敗，不自動重跑、不略過既有交易日閘門。`pg_restore` 命令上限由原本共用的 300 秒改為 900 秒，`pg_dump` 與一般 rclone 命令各保留 300 秒上限；所有 rclone／pg_dump／pg_restore 子程序共用所屬 workflow deadline，另保留 30 秒供終止／清理；超時必須終止並等待子程序退出，且驗收時確認無殘留 subprocess。明確定義 durable commit 邊界：create 的主要操作在 crypt upload 成功且 `backup_record` durable commit 後才算成功；若 upload 已完成但 DB commit 未完成，回安全失敗、保留該遠端檔作未索引 orphan，不自動刪除，僅在新 root 完整成功列舉後允許 sync 收錄。commit 後 rotate timeout 依既有 maintenance contract 安全警告且保留主要成功。restore 的 `pg_restore` 不具原子性：restore workflow 為 1,500 秒，前置階段（建立並 durable index 自救點、來源下載及可讀性驗證）最多 570 秒，並為 `pg_restore` 明確保留最多 900 秒完整執行預算；若前置階段耗時超過 570 秒或剩餘時間不足 930 秒，必須終止／清理尚未執行的下載程序、不啟動 `pg_restore`，回報未還原並提供已索引自救點資訊。只在確認距 workflow deadline 至少仍有 930 秒時啟動 `pg_restore`，其自身最多執行 900 秒；若超時，終止並等待退出、明確回報資料庫可能部分還原，保留還原前已上傳且已索引的自救點，提供以該 exact 自救點重新還原的指引，不宣稱自動 rollback。即使未進入 `pg_restore`，已完成的自救點仍保留供後續使用。前端 timeout 分別設為 create 960 秒、restore／sync／retention 1,530 秒；Nginx 對瀏覽器實際經過的備份 exact routes 設 1,560 秒，BFF 到 backend 的 HTTP client timeout 亦須覆蓋對應 workflow，必須確保 server deadline < 前端 timeout < Nginx timeout，避免代理先回 timeout 後 backend 繼續寫入。UI 在 create request 未結束期間停用再次觸發。
- [ ] 468.7 備份列表在「檔名」之前新增「路徑」欄，顯示該 row 對應的加密 remote **目標邏輯路徑**：`投資理財／資產管理/backups/{folder}/`。以既有 `folder` 值產生，不改 API DTO、索引 schema 或檔名；`GET /api/backups` 是 DB-only，該欄不得被當成 Drive 物件已存在的證據。create request 未結束時停用再次觸發按鈕，其他欄位與還原操作不變。
- [ ] 468.8 在獨立部署設定釘住已核實的新目標 provider folder ID 與既有 crypt `password/password2` 指紋；每個 verified session 均比對，source 熱重載或重新授權後仍 fail closed，直到維運者再次核實帳號、ID、加密設定及已知 exact archive 的解密讀回。`sync` 清索引／rotate 刪除前再驗已知檔可解密；錯密碼造成空／亂碼／錯誤／部分清單都不能刪 row；sync 對每筆候選刪除的 DB exact 路徑須再獨立唯讀確認物件明確不存在，結果不明就保留 row。所有 create 入口須服務端排他、唯一檔名、專屬暫存檔與拒絕覆寫的 remote 寫入；測試兩分頁／API 同秒請求及結果不明的重試，不能只靠前端按鈕或 DB 唯一鍵。

## 驗證

先執行 `bash scripts/spec-check.sh`，完成獨立對抗式規格審查並記錄通過，再實作。離線測試以假 config／假程序／假 remote 涵蓋巢狀 gate、錯誤 crypt backing 設定、逾時後子程序退出、遠端寫入結果不明且期限不足時回「結果未定」並保留來源／索引待後續唯讀對帳、DB commit 失敗的 orphan、restore 570／900／30 秒邊界與 post-commit rotate warning；執行 `mvn -q -f backend/pom.xml test`，並對 frontend 執行 `npm --prefix frontend run build`。正式部署前由維運者獨立核實帳號身分，再唯讀遠端列舉確認 exact `投資理財/資產管理`，盤點來源與目的地既有備份；不得在帳號、scope、完整清單、crypt 解密及歷史資料保存可驗證前執行上傳、刪除或清孤兒同步。經安全遷移與 host 設定更新後，依 `.agents/skills/run-stack/SKILL.md` 從正確 Compose workdir rebuild image、force recreate 必要服務，記錄 image digest；以一次實際手動備份分開確認 HTTP 成功、Nginx／BFF／business 日誌、正確目錄下 exact 加密物件獨立讀回、DB row durable commit 與列表讀回。再確認台／美股交易日與 weekly 時程未被改動，不為測試繞過交易日閘門。不得改動 `[GDriveOutput]`。

## 完成報告

待實作與 runtime 驗收後填寫。
