# [t464] 大盤指數匯出改為多筆獨立排程

**對應 Requirements:** Requirement 45（大盤指數日線匯出與自動排程）<br>
**前置任務:** Task 216（初版匯出）、Task 255（多筆排程／by-id run-now）、Task 270–272（雙格式匯出）、Task 282（共用匯出結果提示）、Task 287（多時間點與多指數）、Task 439（匯出排程短交易與 immutable capture）<br>
**Liquibase changeset:** `v1.137.0-index-export-schedules-multi.sql`

## 背景

目前 GDP-TWSE 頁只有一筆 owner 共用設定，時間與指數放在 `index_export_schedule_time`／`index_export_schedule_time_market` 子表。前端讓使用者修改表單後另按「立即匯出」，但 `/api/index-export/run-now` 只讀資料庫中已儲存的設定，沒有收到表單內容；剛選了另一個市場就執行時，會輸出舊設定，接著頁面重新載入舊值。

改為每位使用者最多 10 筆彼此獨立的排程。每筆只有一個每日執行時間，並有自己的名稱、啟用狀態、多指數集合、匯出範圍、本機目錄、Drive 設定與執行結果。新增／編輯在對話框按確定時即寫入；「立即匯出」只放在已儲存的排程列上，並以該列 id 執行，消除未儲存表單與 run-now 脫節的狀態。

**既定產品決策：**同一排程複選多個指數時，每個指數各產一組 `.json`＋`.xlsx`，不合併成多工作表活頁簿；手動圖表下載維持單一市場，不在本任務範圍。

## 要做什麼

### 464.1 資料庫與 migration

- [ ] 新增 `v1.137.0-index-export-schedules-multi.sql` 並在 `db.changelog-master.yaml` 尾端註冊。實作前再次檢查所有 worktree、主線及執行中 `databasechangelog`；如有碰撞，整組更新檔名／changeset id／註冊位置。現有基準已包含 v1.88.0。
- [ ] 將 `index_export_schedule` 調整成「一列一筆排程」：新增 `name`、`run_hour`、`run_minute`、`last_run_date`、`last_run_at`、`last_run_status`；移除 `uq_index_export_schedule_owner`，改建 `owner_user_id` 非唯一索引；保留並複製既有 `enabled`、`output_subpath`、`range_months` 與 Google Drive 欄位。保留／建立 range、hour、minute 的資料庫 CHECK。
- [ ] 新增 `index_export_schedule_market(schedule_id, market)` 正規化子表，複合主鍵為 `(schedule_id, market)`、schedule FK `ON DELETE CASCADE`。不建立代碼 CHECK；Java `MacroHistoryService.DAILY_INDEX_CODES` 是唯一白名單。
- [ ] 資料遷移以既有 parent＋time＋time-market 為來源：舊 parent 的每個時間點成為一筆新排程；依 `(run_hour, run_minute, id)` 升序，第一個時間點沿用 parent id，其餘時間點複製成新的 parent row。新排程的 `enabled = 舊 parent.enabled AND 舊 time.enabled`；逐列保留舊時間、markets、last-run date/time/status，並複製 range、本機路徑及 Drive 開關／路徑。一般列名稱為 null，確保舊檔名保持原樣；若同 owner 的舊時間點重複選擇同一 market，該 market 涉及的每一列都設為 `legacy_HHmm`（HHmm 取該列舊執行時間；舊時間點在 parent 內唯一），避免拆分後同日輸出覆寫。遷移前若舊 time table 存在但 `index_export_schedule_time_market` 缺失，必須在任何 schema／資料變更前明確中止，不能跳過市場搬移後刪除來源時間表。
- [ ] 在任何資料搬移或移除舊表之前，計算每位 owner 遷移後的列數（每個舊時間點一列，無時間點的 parent 算一個 placeholder）。若任一 owner 超過 10 列，changeset 必須以包含 owner 與筆數的明確錯誤中止並回滾整個 changeset；舊表與資料保持原狀，供人工處理，不得截斷、停用或丟棄列。
- [ ] 若舊 parent 沒有時間點，保留一筆 disabled、08:00、空 markets 的 placeholder，保留其範圍／目錄／Drive 設定；不得自動補 TWSE，也不得意外啟用排程。UI 可顯示並編輯該列；更新時若 markets 仍空、或對空 markets placeholder 執行 run-now，回 400 並提示先選至少一個指數；選取合法指數後可正常儲存並成為一般排程。前端在 markets 空時停用該列 run-now 並顯示提示。舊 parent 的 Drive last-run 是 owner 層彙總，無法可靠歸屬拆分後的某一列；拆分時清空 `gdrive_last_run_at/status`，保留 Drive 設定本身。
- [ ] 在確認資料複製完成後移除 `index_export_schedule_time` 與 `index_export_schedule_time_market`。SQL 可安全重跑：`IF EXISTS`／`IF NOT EXISTS`／`ON CONFLICT`；在 `DO $$` 中先以 table／column existence 條件守門，再執行舊 schema 讀取；當條件已保證舊表存在時，可使用區塊內的靜態 SQL，不強制改成動態 SQL。changeset 設 `splitStatements:false`。不得修改已提交的 migration。
- [ ] 以專案 schema 產生方式更新 `db/schema.sql`，使 schema drift 檢查通過。

### 464.2 Entity、Repository 與交易邊界

- [ ] `IndexExportSchedule` 改為每筆排程一個 aggregate，加入 name、時分、markets 與該列 last-run 欄位；移除對 time child 的 `@OneToMany`。保留 `@Filter(ownerFilter)`；同 owner 多列合法。
- [ ] Repository 支援 owner 排序列表、by-id-and-owner 查詢、owner count，以及排程 runner／writeback 所需的 id＋owner 鎖定查詢。禁止以 `findById`／`deleteById` 實作使用者 by-id 存取。
- [ ] `IndexExportScheduleExecutionStore` 保留短 `REQUIRES_NEW` 交易：建立／更新／刪除、run-now capture、due capture 與 writeback 都在各自短交易內完成。每個 create/update/delete 交易在讀取 owner 筆數、檢查碰撞或寫入前，先取得以固定 namespace 加 owner id 組成的 PostgreSQL transaction-scoped advisory lock；鎖定不得依賴既有排程列，因此零列 owner 的並行新增也會序列化。owner count、碰撞檢查及寫入在同一交易與鎖內完成；競態測試需覆蓋兩個併發新增爭奪第 10 個名額及兩筆併發新增相同衝突鍵。實際查行情、render、寫檔與 Drive I/O 一律在交易外，輸入以 immutable capture 傳遞。writeback 重新以排程 id＋owner 鎖列，且 run-now 更新 last-run time/status 但不改 `last_run_date` 當日 guard。
- [ ] 排程每分鐘 tick、`Asia/Taipei`、`AtomicBoolean` 防重入、`ApplicationReadyEvent` 自癒及「成功或失敗都寫當日 guard」維持現況；不新增、不移除、不調整任何 `@Scheduled`。

### 464.3 DTO、API 與驗證

- [ ] 將單筆端點取代為以下五支；Controller 不注入 Repository、不承擔領域判斷：

  | Method | Path | 行為 |
  |---|---|---|
  | GET | `/api/index-export/schedules` | 回目前 owner 排程清單 |
  | POST | `/api/index-export/schedules` | 新增一筆；回變更後完整清單 |
  | PUT | `/api/index-export/schedules/{id}` | 更新該筆；回變更後完整清單 |
  | DELETE | `/api/index-export/schedules/{id}` | 刪除該筆；回變更後完整清單 |
  | POST | `/api/index-export/schedules/{id}/run-now` | 執行該筆已儲存設定 |

- [ ] 設定 request 為完整單筆：`name`、`enabled`、`runHour`、`runMinute`、`markets`、`rangeMonths`、`outputSubpath`、`gdriveEnabled`、`gdriveSubpath`。`name` null／純空白代表清除；否則先 trim，長度 1–20，必須符合 `^[\p{IsHan}A-Za-z0-9_ -]{1,20}$`。Drive 欄位沿用 `GdriveOutputSupport.resolveUpdate` 的 null／權限／必填語意。
- [ ] 清單回應含 `schedules[]`、`baseDir`、`gdriveRemote`、`gdriveSelfCheckWarning` 與後端產生的 `marketOptions[{value,label}]`。每筆包含 id、name、enabled、時分、markets、range、output path、last-run 與該列 Drive 狀態。中文指數標籤只由 `ExcelExportService.indexLabel` 產生；前端不得另造一份排程標籤表。
- [ ] run-now 回應含逐指數 `results[]` 與整筆 Drive 摘要。每個結果包含 market／marketLabel、`path`（xlsx）、`jsonPath`（json）、大小、各自 Drive 落點、該指數原始 `gdriveStatus` 與 `error`。單一指數失敗不得阻止其他指數嘗試；不得把彙總 Drive 字串當成逐指數狀態。
- [ ] 使用者只可操作自己的排程；不存在與非本人 id 均回 404。新增時 owner 排程數達 10 回 400。時分超界、range 非 null 且不在 1–120、markets 為 null／空／含空白元素或不在 `DAILY_INDEX_CODES`、name 不符白名單，均回 400。markets 先 `trim().toUpperCase()` 再驗證；禁止空字串 fallback 成 TWSE。僅 migration 產生的 disabled、空 markets placeholder 可暫存；更新 request 不可保存空 markets，run-now 對 placeholder 回 400 並提示先選至少一個指數。
- [ ] 同一 owner 不同排程的衝突鍵為 `(ownerId, normalizeName(name), market, target)`。`normalizeName` 將 null／純空白轉 null，其餘 trim；本機 `target = resolveDir(normalizeSubpath(outputSubpath))`。比較前先以 `toRealPath` 解析 base 與 target 最近存在的前綴，再接回尚不存在的路徑元件；解析後 target 必須仍位於解析後 base 內，拒絕會跳出 base 的 symlink，指向 base 內同一實體位置的 symlink alias 則視為相同 target。執行寫檔時也須重新解析及驗證，避免已儲存路徑後來被改成跳出 base 的 symlink。本機名稱與落點比較須遵從該輸出 volume 的實際大小寫語意：從每個候選 target 最近已存在的 directory 開始，只檢查其中既有 entries 的大小寫變體 sibling；以 `BasicFileAttributes` 搭配 `NOFOLLOW_LINKS` 比較非 null 的 `fileKey`，兩個 entry 都不能是 symlink，且 directory listing 不得有只差大小寫的另一 entry，才可確認是同一 entry。只有 `Files.getFileStore(dir).equals(Files.getFileStore(parent))` 時才能向 parent 繼續探測，遇到不同 volume 或無法確認 FileStore 時停止，不得跨 mount boundary 以 parent volume 推斷 target volume 語意。只在 target volume 確認大小寫不敏感時，名稱與 target 才用 `equalsIgnoreCase`；大小寫敏感或探測證據不足時維持精確比較。目標目錄尚未建立時也須從既存 ancestor 探測，不得建立檔案或目錄作為探針。Drive 啟用時另以相同前三項及 `GdriveOutputSupport.normalizeSubpath(gdriveSubpath)` 作為 Drive 衝突鍵，維持既有 Drive 比較語意。儲存時命中相同鍵必須回 400 並指出衝突列；不同名稱或不同落點可使用相同指數。掃描既有列時遇到無法解析的舊路徑須 warn 並跳過該列，避免髒資料阻斷修正。
- [ ] run-now 先在 try/catch 外以 id＋owner 取列，查無回 404；市場檔案逐一產生。更新該列 `last_run_at/status` 與適用的 Drive 結果，狀態欄寫入前截到 DB 長度限制；執行成功或失敗都不得設定當日排程 guard。

### 464.4 排程執行與檔名

- [ ] 排程執行仍呼叫 `ExcelExportService.indexDailyDoc(market,start,end)`，各市場各查一次，再共用該次文件產生 JSON／Excel；render 或單一 market 失敗記錄該 market 並繼續。不得把長時間 I/O 放進 DB 交易。
- [ ] 檔名為 `{indexLabel}_{ownerId}[_{排程名}]_{yyyyMMdd}`；排程名為 null 時保留現有 `{indexLabel}_{ownerId}_{yyyyMMdd}` 格式。每個 market 各寫 `.xlsx`＋`.json`，沿用 `DualFormatExportWriter` 原子寫檔與 Drive 最佳努力同步。
- [ ] 背景 runner 對每筆排程獨立判斷 enabled、設定時間、當日 guard；一筆排程失敗不影響同 owner 其他列或其他 owner。單筆狀態摘要需逐 market 呈現成功／失敗並截至 500 字；Drive 摘要截至 512 字。Drive 未啟用時不可覆蓋歷次 Drive 執行結果。

### 464.5 BFF 與前端

- [ ] `GdpTwseBffController` 將 `/export/schedule` 與 `/export/run-now` 換成 `/export/schedules` 的五支 page-specific passthrough；`/export/browse`、`/export/browse-gdrive`、手動 `/export` 不變。前端只呼叫本頁 BFF，不直呼 business。
- [ ] `frontend/src/api/index.js` 的 `gdpTwse` 改用 list/create/update-by-id/delete-by-id/run-now-by-id 方法；延續 `skipErrorToast`，run-now timeout 維持足以完成匯出。
- [ ] `GdpTwseView.vue` 排程卡呈現列表及新增／編輯對話框。每列顯示名稱、啟用、執行時間、多個指數標籤、範圍、本機／Drive 落點、上次狀態及編輯／刪除／立即匯出操作。確定新增／編輯即送 POST／PUT 並重新讀取列表；立即匯出只接受列表中已儲存的 row id。刪除需確認。Drive 控制維持現有 admin 顯示規則與後端授權。
- [ ] 指數選單與列表標籤都使用回應內 `marketOptions`；頁面上方圖表切換器既有 `MARKETS` 可保留。手動圖表下載仍維持單一 `market`。
- [ ] 逐指數結果用 `showDualExportResult` 呈現，傳入該筆 `gdriveStatus`、`jsonPath`、xlsx `path` 及市場前綴；整筆失敗另顯示 `error`。不得在頁面複製 Drive 成功正則或把彙總 gdriveStatus 傳給共用提示模組。
- [ ] 排程卡說明、`SchedulePublicBffController.JOBS` 的既有大盤指數描述同步為「每筆排程可複選指數，每指數各產 JSON 與 Excel」；保留既有片語 `同時產出 JSON 與 Excel 兩份`，不新增排程項目。

### 464.6 測試與驗收

- [ ] Backend 測試覆蓋：每人上限 10；by-id owner 隔離／404；時間、範圍、名稱及 market 驗證；CRUD 回完整清單；foreign-owned schedule 的 update/delete/run-now 都走 owner-scoped 查找並回 404；衝突路徑正規化與不同目錄可共存；本機 symlink alias 視為同一碰撞落點、指向 base 外的 symlink 被拒絕，儲存後再改成外部 symlink 也必須在寫檔前拒絕；大小寫探測只在相同 FileStore 內往 parent 檢查、遇到 volume boundary 必須停止，且 Linux symlink 不得誤判為大小寫不敏感；逐 market 雙格式匯出；部分失敗仍繼續；run-now 不動 guard 但更新 last-run；due runner 獨立執行多列與當日 guard；狀態欄截斷；Drive 權限及逐指數狀態。
- [ ] Migration 驗證以 PostgreSQL 測試既有多時間點 parent：第一列保留原 id，其餘建立新列，markets／有效 enabled／本機 guard／路徑／range／Drive 設定符合規格；同 owner 重複 market 的舊列各自獲得 `legacy_HHmm` 名稱；無時間點 parent 變成 disabled 空 markets placeholder；任一 owner 遷移後超過 10 列時整個 changeset 回滾且舊 schema／資料仍在；舊 time table 存在但 market mapping table 缺失時也須在任何變更前回滾；舊 child tables 移除；schema drift 通過。另驗證 changeset 可安全重入。
- [ ] Backend 併發測試驗證 owner advisory lock 在零既有列時仍有效：同 owner 競爭第 10 個名額只能一筆成功；相同衝突鍵並行新增只能一筆成功。
- [ ] BFF controller 測試五支 route／body／回應 passthrough；舊 route 不再存在。新增 `frontend/src/utils/gdpTwseSchedule.contract.test.js` 並納入 `frontend/package.json` 的 `test` script；測試須驗證 list/create/update/delete/run-now 使用正確 BFF URL 與 id、CRUD 成功後重讀列表、market options 取自回應、空 markets placeholder 的 run-now 停用並提示先選指數、逐 market 結果交給共用提示模組，以及手動圖表匯出仍走單市場端點。Frontend build 另確認 Vue template／script 可編譯。
- [ ] 執行 `bash scripts/spec-check.sh`、spec-auditor 審查及 `.claude/hooks/spec-review-pass.sh` 後才可改程式；完成後跑相關 backend 測試與 frontend build。部署只從 `/Users/steven/Project/asset-management-main` 重建變更服務，確認 Compose 容器載入新映像、健康檢查及未登入 API 401；已登入端到端案例若無可用 Chrome session bridge，需明確列為未驗證，不得以 401 宣稱功能驗收成功。
- [ ] 核對主線及遠端、migration readback、API contract、產生的檔名與本機／Drive 狀態；不得啟用任何使用者每日排程作為驗收副作用。

### 明確不做

- 不調整 Fubon 行情／技術指標抓取、9090 API 或 `SRPP/docs`；不呼叫任何券商下單 API。
- 不改手動圖表單市場匯出路由、日線查詢、指標計算或市場資料表。
- 不新增或修改 `@Scheduled` cron，不合併多市場成多工作表活頁簿。

## 驗證

在 `/Users/steven/Project/asset-management-main` 執行下列驗證。

### 實作前規格閘門

```bash
bash scripts/spec-check.sh
```

把完整未追蹤檔與 spec diff 交給一位獨立唯讀 spec-auditor。只有在其確認 critical／major findings 全數修正後，才執行：

```bash
bash .claude/hooks/spec-review-pass.sh
```

### 實作後測試

```bash
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -DextraArgLine=-Dnet.bytebuddy.experimental=true test
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f bff/pom.xml -DextraArgLine=-Dnet.bytebuddy.experimental=true test
npm --prefix frontend test
npm --prefix frontend run build
```

Liquibase migration 套用後，依 `db/schema.sql` 檔頭的重產程序同步 schema：

```bash
docker exec asset-postgres pg_dump -U assets -d assets --schema-only --no-owner --no-privileges | grep -v '^\\restrict\|^\\unrestrict' > /tmp/fresh-schema.sql
HDR=$(( $(grep -nxF -- '-- PostgreSQL database dump' db/schema.sql | head -1 | cut -d: -f1) - 2 ))
head -n "$HDR" db/schema.sql > /tmp/schema-header.sql
cat /tmp/schema-header.sql /tmp/fresh-schema.sql > db/schema.sql
grep -c '^CREATE TABLE' db/schema.sql
bash scripts/tests/schema-sql-drift-test.sh
```

接著以主 worktree 部署並確認實際服務：

```bash
docker compose -p asset-management build --no-cache business-services bff frontend
docker compose -p asset-management up -d --no-deps --force-recreate business-services bff frontend
docker exec asset-business-services wget -qO- http://127.0.0.1:8080/actuator/health
curl -i http://localhost/api/bff/gdp-twse/export/schedules
```

預期 health 為 `UP`、schema drift script 回 0、經 frontend／BFF 的未登入排程 API 回 401。backend 沒有 host port；health 由容器內部確認。核對容器使用本次主 worktree 映像及 migration readback；不能以未登入 401 代替功能驗收。若沒有可用 Chrome session bridge，已登入端到端流程須列為未驗證。不可為測試啟用任何使用者的每日排程或呼叫券商下單 API。

## 完成報告

實作完成後回填實際異動檔案、backend／BFF／frontend 測試結果、migration 與 schema drift 結果、容器映像及健康檢查證據、API／檔案落點驗收，以及相對本計畫的偏差與原因。
