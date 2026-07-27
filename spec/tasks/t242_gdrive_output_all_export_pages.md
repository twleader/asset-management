# [t242] Drive 輸出推廣的地基：共用元件、上傳能力、八張表欄位

**對應 Requirements:** Requirement 51（歷年資產／交易日曆／已實現損益／油價金價／台幣兌美元匯率／GDP-TWSE／交易雷達／交易紀錄八頁的匯出檔案，除本機外可同步一份到 Google Drive；本機照寫不變）
**前置任務:** t241（爬蟲資訊查詢頁已打通端到端；本任務沿用並**收斂**它建立的 `RcloneClient`／`ProcessRcloneClient`／驗證邏輯）
**後續任務:** t243（六個結構相近的頁面）、t244（交易雷達與交易日曆兩個結構例外）
**Liquibase changeset:** `v1.76.0-gdrive-output-all-export-pages.sql`

## 背景

Task 241 已讓「爬蟲資訊查詢」頁的輸出資料夾支援 Google Drive 並實測打通（本機與 Drive 兩份 `public_info_2026-07-27.json` 皆 93159 bytes、內容一致）。Requirement 51 要把同一套模型推廣到其餘八頁。

**這八頁 × 六層（DB／entity／DTO／service／BFF／前端）遠超「一支任務檔＝一個可獨立驗收的交付」的界線**（`spec/tasks/README.md` 鐵則 1），故刻意拆成三支：

| 任務 | 交付 |
|---|---|
| **t242（本檔）** | 地基：共用元件、上傳能力、changeset、八個 entity、單元測試 |
| t243 | 六個結構相近的頁面（DTO／service／BFF／前端） |
| t244 | 兩個結構例外：交易雷達、交易日曆 |

**本任務刻意不含任何 UI 與 service 整合**，因此完成後功能仍未啟用——這是正確狀態。它的可獨立驗收判準是：八張表欄位到位、共用元件單元測試綠、既有功能（含 t241 的爬蟲頁 Drive 同步）無回歸。

**本任務同時要收斂 t241 留下的技術債。** t241 實作時把 Drive 子路徑驗證寫成 `CrawlerExportPathService` 的 **private static** 方法、remote 名稱則分別注入 `CrawlerExportPathService` 與 `ExportScheduleService` 兩處。private 無法重用，若新元件只是「照抄一份」，做完 Requirement 51 後同一個 module 內會有 2 份驗證邏輯 ＋ 3 處 remote 注入——正是本需求宣告要防的事，只是數量從 8 降到 2。

## 要做什麼

### 242.1 共用元件 `GdriveOutputSupport`

- [ ] 242.1 backend 新增 `service/GdriveOutputSupport.java`（`@Component`），承擔五件事：
  1. **子路徑正規化與驗證**（規則見 242.1.2）
  2. **上傳並回傳狀態字串**（best-effort，不擲例外，見 242.1.3）
  3. **remote 名稱**（`@Value("${GDRIVE_OUTPUT_REMOTE:GDriveOutput}")`，全 backend **唯一**一處注入）
  4. **Drive 目錄列舉**（由 `ExportScheduleService.browseGdrive` 遷入，見 242.2.3）
  5. **權限判定** `boolean isDriveAllowedFor(Long ownerUserId)`（見 242.1.6）

- [ ] 242.1.6 **`isDriveAllowedFor(Long ownerUserId)` 是唯一的權限判定入口，且必須 fail-closed。** 內部流程：`AppUserRepository.findById(ownerUserId)` → `getEmail()` → `UserAdminService.isConfiguredAdmin(email)`。
  - **查不到該使用者一律回 `false`**（fail-closed）。找不到 owner 卻照傳，等於把資料送去一個無法歸屬的帳號。
  - **`email` 為 null／空白同樣回 `false`**。
  - 八個 service 的 `PUT` 與背景排程**一律呼叫這個方法，不得自行查 `AppUserRepository`**——兩處判定分開實作，日後只會有一處被改寬。
  - **不得用 `role == ADMIN` 或 `CurrentUserContext.isAdmin()`**：`role` 是 DB 欄位、可以有多列 ADMIN（實測 `app_user` 現有兩名使用者），第二位若被升為 ADMIN，其報表照樣進到 rclone remote 擁有者的 Drive。`isConfiguredAdmin` 比對 `ADMIN_EMAIL`，全庫唯一一人。
  - **代看（impersonation）情境**：`CurrentUserContext` 只有 `effectiveUserId`／`role`／`status`，**business 端拿不到「登入者是誰」**（`X-User-Id` 在代看時帶的就是被代看者），所以無法在此比對兩者。**明文接受這個限制**：代看時 `isDriveAllowedFor(effectiveUserId)` 天然 fail-closed——被代看者若非 `ADMIN_EMAIL` 即回 false。殘餘風險是「第二位 `role=ADMIN` 者代看主要管理者時仍可 PUT」，以背景排程逐列複驗為最終防線（那一關看的是設定列自己的 owner）。**不要**為此在 `isDriveAllowedFor` 內讀 `CurrentUserContext`——背景排程沒有 request context，會讓所有排程上傳靜默跳過。

- [ ] 242.1.1 **必須抽共用元件，不得複製八份。** 這與 t241 的「不為兩處 rclone 呼叫建共用 module」**不衝突**：那條講的是跨越三個獨立 Maven 專案（`backend`／`bff`／`external-materials-service`，無父 pom）為兩處數十行程式碼建 module；這裡是**同一個 backend module 內**的八個 service 共用同一段邏輯，八份複製是明確的錯誤。

- [ ] 242.1.2 驗證規則（**與 t241 的爬蟲頁必須完全一致**，因為兩邊寫進同一個 Drive）：
  - 正規化：去頭尾空白、**只剝結尾** `/`；空字串 → null（未設定）。**不套用任何預設值**——Drive 沒有「合理的預設目錄」，猜錯的代價是把檔案倒進使用者雲端硬碟的非預期位置。
  - **開頭 `/` 回 400**，刻意不剝除（讓使用者知道只能填相對子路徑，勝過默默改寫語意）。**先剝再檢查會讓該 400 分支永遠不可達**。
  - **不得含 `..` 路徑段**：以 `/` 切開**逐段**比對，不可用 `contains("..")`——會誤擋合法目錄名如 `a..b`（242.6 有此案例）。理由要寫對：rclone 對 Drive remote **不做**路徑正規化（實測 `rclone lsd "remote:x/.."` 回 `directory not found`、exit 3），擋它**不是**防目錄跳脫，而是避免在使用者 Drive 上建出字面名為 `..` 的怪目錄。
  - **拒收含 `:`**：rclone 取第一個 `:` 之前為 remote 名，防子路徑被解讀成切換 remote。指令一律以 `<remote>:<subpath>` 拼在**同一個 argv 元素**內，此保證依賴該實作細節，故多擋一層。
  - **不得**用 `Path.of(...).normalize().startsWith(base)`：Drive 路徑不是本機檔案系統路徑，在容器內 resolve 沒有意義，且結果會隨容器 OS 的路徑分隔符改變。

- [ ] 242.1.3 上傳方法為 **best-effort、不擲例外**，回傳可直接寫進 `gdrive_last_status` 的字串：成功記落點與檔案大小（例 `成功：GDriveOutput:資產管理/交易紀錄_1_20260727.xlsx（12345 bytes）`）、失敗記錯誤摘要、跳過記原因。**一律截斷至 512 字元內**（欄位長度上限，rclone stderr 可能很長）。

- [ ] 242.1.4 **遷入 t241 留下的兩份重複（不是照抄）**：
  - `CrawlerExportPathService` 的 `normalizeGdriveSubpath()`／`validateGdriveSubpath()`（現為 private static）遷入本元件，該 service 改為注入使用。
  - `CrawlerExportPathService` 與 `ExportScheduleService` 的 `@Value("${GDRIVE_OUTPUT_REMOTE:...}")` 注入一併移除，改由本元件提供。
  - **驗收方式**：`grep -rn '@Value("${GDRIVE_OUTPUT_REMOTE' backend/src/main/java` 應**恰為 1 行**（只認注入點）。注意 `CrawlerExportPathDto.java` 與 `CrawlerExportSetting.java` 的 Javadoc 也提到這個變數名，那是正常說明、**不要刪**——所以不能用 `grep -rc 'GDRIVE_OUTPUT_REMOTE'` 當判準（它對目錄是逐檔計數，也不會輸出單一數字）。
  - **爬蟲頁的既有行為不得改變**——它已實測部署在跑，遷移是純重構。t241 的測試必須仍然綠。

### 242.2 `RcloneClient` 加上傳能力

- [ ] 242.2 `RcloneClient` 介面（t241 建立，現有唯一方法 `listDirs`）新增 `copyTo(...)`（上傳單一檔案至 `<remote>:<subpath>/<destFileName>`）。
- [ ] 242.2.1 **維持既有自我約束不變：只實作 `lsjson --dirs-only` 與 `copyto` 兩種操作，不實作任何刪除既有 Drive 檔案的程式路徑。** `scope = drive` 是使用者 Drive 的完整讀寫權，把實際使用面縮到最小是刻意的。
- [ ] 242.2.2 `ProcessRcloneClient` 實作 `copyTo`：`rclone copyto <localFile> <remote>:<subpath>/<destFileName>`。
  - **config 佈局不變**：沿用該類別既有的 `CONFIG_SOURCE = /etc/rclone/rclone.conf` → `/tmp/rclone-output.conf` 可寫副本 ＋ per-process `RCLONE_CONFIG` 覆寫（`pb.environment().put(...)`）。單一 config 檔同時含備份與輸出兩用途的 section，是使用者明示的決定（代價與復原方式記於 `spec/steering/tech.md` §4）；**本任務不變更 config 佈局**。
  - **逾時 45 秒**（比照 t241 的 ext 端上傳）。**不得沿用 `BackupService.PROCESS_TIMEOUT_SEC` 的 300 秒**——那是為 pg_dump／整庫上傳設的。
- [ ] 242.2.2.1 **`exec()` 必須先參數化（timeout ＋ 操作名稱），不可直接重用。** 既有 `exec()` 把 `LIST_TIMEOUT_SEC`（20 秒）與「目錄列舉」字樣**硬編**在裡面。若上傳直接重用它：（a）會套到 20 秒而非 45 秒；（b）失敗訊息會寫成「Google Drive 目錄列舉逾時」，而這個字串會**原樣寫進使用者可見的 `gdrive_last_status`**。改為傳入 timeout 與操作名稱：列目錄 20 秒／「目錄列舉」，上傳 45 秒／「上傳」。
- [ ] 242.2.2.2 **`copyTo` 的 argv 必須附加 `RCLONE_LIMITS`（`cmd.addAll(RCLONE_LIMITS)`），`exec()` 不會自動加。** 該常數已存在於 `ProcessRcloneClient`（`--retries 1 --low-level-retries 3 --contimeout 10s --timeout 30s`）。理由（t241 實測 2026-07-27）：rclone 預設 `--low-level-retries 10` ＋ `--timeout 5m`，遇到任何 Drive API 延遲就會把單次上傳放大到數十秒至數分鐘——**外層 45 秒只會讓上傳失敗，不會讓它變快**。實測無參數的手動上傳超過 180 秒未結束、20:30 那輪排程以「逾時（45 秒）」失敗；帶上這四個參數後同一上傳只需 **2.4 秒**。放大效應之所以容易觸發，有兩個實測到的固定成本：token 幾乎每次呼叫都已過期（每次都要 refresh 並寫回 config），以及未設 `root_folder_id`（每次都得從 Drive 根目錄逐層查找子路徑）。
- [ ] 242.2.3 `ExportScheduleService.browseGdrive` 的 Drive 目錄列舉邏輯遷入 `GdriveOutputSupport`，該 service 改為委派。**controller 端點路徑 `GET /api/export-schedule/browse-gdrive` 不變**，避免動到 t241 已完成並部署的 BFF 與前端。理由：`ExportScheduleService` 已 353 行，同時承擔「歷年資產排程」＋「本機目錄列舉」＋「全庫唯一的 Drive 目錄列舉」，而 t243 還要它再加上傳；Drive 相關邏輯本就該落在共用元件。
- [ ] 242.2.3.1 **「目錄不存在」不得回 500。** 先釐清現況（不要照舊版描述做白工）：`RcloneUnavailableException` **已經**被 `ExportScheduleController` 攔成 503（`ResponseStatusException(SERVICE_UNAVAILABLE)`），那條路徑是通的。實測 `GET /api/export-schedule/browse-gdrive?subpath=zzz-not-exist` 回 **500** 的原因不同——rclone 對不存在的目錄以**非零退出**收場，`exec()` 擲的是**裸 `RuntimeException`**，落到 `Exception` 兜底。
  - 修法：`ProcessRcloneClient.listDirs` 攔截 stderr 含 `directory not found` 的情形，**回空清單**——目錄不存在只代表該層沒有內容，與本機 `browse` 遇到不存在目錄時的行為一致（`Files.isDirectory` 為 false 即回空）。`BackupService` 對 `lsjson` 也是同樣處理。使用者展開一個剛被刪掉的資料夾不該看到「伺服器錯誤」。
  - **`RcloneUnavailableException` 與 `RcloneTimeoutException` 必須原樣往上拋**，不可被這個 catch 吞掉——那兩者要讓使用者看到原因。
  - 另在 `GlobalExceptionHandler` 補 `@ExceptionHandler(RcloneUnavailableException.class)` → 503 作為縱深防禦（日後若有新的呼叫點忘了在 controller 攔，不會退化成 500）。
- [ ] 242.2.4 **改寫 `RcloneClient` 的類別 Javadoc**：它目前明文寫「刻意只有『列目錄』一個方法…backend 端只實作列目錄…實際上傳（`copyto`）在 `external-materials-service` 端」。加 `copyTo` 後這三句全部變成錯的敘述，留在原地會誤導後續讀者。改為「backend 端實作列目錄與上傳兩種操作，仍不實作任何刪除路徑」。
- [ ] 242.2.5 **Drive 側不做 tmp＋rename**：Drive API 未完成的上傳不會產生可見檔案，逾時被強殺後最壞情況是該次上傳沒發生。這是有意識的接受（同 t241），且 tmp＋改名需要 delete 權限的程式路徑，與 242.2.1 的約束衝突。
- [ ] 242.2.5.1 **但反向的「假失敗」會發生，逾時與確定性失敗的措辭必須分開。** `exec()` 的判準是「行程未在時限內 exit」，**不是**「檔案沒上去」。實測（2026-07-27）：`crawler_export_setting.gdrive_last_status` 記「失敗：rclone 上傳逾時（45 秒）」，同一輪 Drive 端 `rclone lsl` 卻有 122053 bytes 的完整檔案、與本機檔逐 byte 同大小——**上傳其實成功了，只是行程沒在時限內收攤**。照原文推廣到八頁會把這個誤判複製八份。故：
  - 逾時 → 寫 `逾時（45 秒）：Drive 端可能已完成，請於下一輪確認`
  - 只有 **rclone 非零退出** → 才寫 `失敗：<rclone 錯誤>`
  - 兩種措辭在 t243.3／t244.2 一併沿用；242.5 與 243.8 各加一條測試斷言區分它們。

### 242.3 Liquibase changeset `v1.76.0-gdrive-output-all-export-pages.sql`

- [ ] 242.3 於 `backend/src/main/resources/db/changelog/changes/` 新增，並在 `db.changelog-master.yaml` 尾端 `include`（現有最大版號為 `v1.75.0-crawler-gdrive-output.sql`，運行中 DB 的 `databasechangelog` 最新一筆亦為它，故用 `v1.76.0`）。為**以下八張表各加四欄**：

  | # | 資料表 | 對應頁面 | Requirement | 後續任務 |
  |---|---|---|---|---|
  | 1 | `export_schedule_setting` | 歷年資產 | 34 | t243 |
  | 2 | `realized_gain_export_schedule` | 已實現損益 | 39 | t243 |
  | 3 | `asset_transaction_export_schedule` | 交易紀錄 | 49 | t243 |
  | 4 | `commodity_export_schedule` | **油價金價** | **41** | t243 |
  | 5 | `exchange_rate_export_schedule` | **台幣兌美元匯率** | **42** | t243 |
  | 6 | `index_export_schedule` | GDP-TWSE | 45 | t243 |
  | 7 | `trading_radar_export_setting` | 交易雷達 | 48 | t244 |
  | 8 | `trading_calendar_export_schedule` | 交易日曆 | 37 | t244 |

  > **注意 R41／R42 的對應**：R41 是「油價金價」（`commodity_export_schedule`）、R42 是「台幣兌美元匯率」（`exchange_rate_export_schedule`）。這兩個曾在 spec 初稿被寫反，實作時請以本表為準（可用 `grep -n '^### Requirement 4[12]:' spec/requirements.md` 自行複核）。

  每張表加：
  ```
  gdrive_enabled       BOOLEAN      NOT NULL DEFAULT false
  gdrive_subpath       VARCHAR(512)
  gdrive_last_run_at   TIMESTAMP
  gdrive_last_status   VARCHAR(512)
  ```

- [ ] 242.3.1 **changeset 必須冪等**（`ADD COLUMN IF NOT EXISTS`）。一旦跑過就**不得改名或改 id**——改名會被 Liquibase 認成新 migration 重跑，`column already exists` 會讓 business 進 crash loop 而整站掛掉。若需為版號避讓調整，先查 `databasechangelog`。
- [ ] 242.3.2 **不要用 `db/schema.sql` 判斷這八張表的現況**：那只是離線鏡像、刻意不會被執行，且**實測已落後**——本任務目標的八張表裡有**三張**（`index_export_schedule`／`trading_radar_export_setting`／`asset_transaction_export_schedule`）在鏡像中根本不存在。要確認欄位現況一律直接查運行中的 DB：
  ```bash
  docker exec asset-postgres psql -U assets -d assets -c '\d export_schedule_setting'
  ```
- [ ] 242.3.3 **seed 不啟用任何一列**：不寫任何 `UPDATE ... SET gdrive_enabled = true`。既有部署升級後行為與現況完全一致，且不要求 rclone remote 存在。

### 242.4 八個 entity 加欄位

- [ ] 242.4 以下八個 entity 各加四個欄位：
  `ExportScheduleSetting`／`RealizedGainExportSchedule`／`AssetTransactionExportSchedule`／`CommodityExportSchedule`／`ExchangeRateExportSchedule`／`IndexExportSchedule`／`TradingRadarExportSetting`／`TradingCalendarExportSchedule`
- [ ] 242.4.1 型別：`gdriveEnabled` 為 `boolean` ＋ `@Column(name = "gdrive_enabled", nullable = false)`；`gdriveSubpath`／`gdriveLastStatus` 為 `String`（`length = 512`）；**`gdriveLastRunAt` 為 `LocalDateTime`**。八個 entity 既有的 `lastRunAt` 型別**已實測一致為 `LocalDateTime`**（`grep -rn "private .* lastRunAt" model/ | grep -E 'ExportSchedule|ExportSetting'` → 8/8，`Instant` 命中 0），新欄位對齊即可，不需逐一比對。
- [ ] 242.4.2 **狀態欄與既有 `last_run_status` 分離，不得併入**：「本機成功、Drive 失敗」是正常且必須可分辨的狀態；若共用一欄，本機明明寫成功卻顯示「失敗」，使用者會誤以為本機檔案沒產生而去做不必要的排查。
- [ ] 242.4.3 檢查各 entity 是否有 `@AllArgsConstructor` 且有程式碼以**位置參數**呼叫它——加欄位會改變參數個數。

### 242.5 「主要管理者」旗標的前端管線（沒有這條，R51 的 UI 條件無法實作）

- [ ] 242.5 **實測：全站沒有可用的「主要管理者」旗標。** 八個目標 view 對 `isAdmin`／`useAuthStore` 的 grep **皆為 0**（八頁目前都沒有引入 auth store）；`frontend/src/stores/authStore.js` 唯一的 `isAdmin` 是 `s.me?.role === 'ADMIN'`——**正是 242.1.6 禁止的 role 判準**；BFF `MeController` 的 `/api/me` body 只有 `email/name/picture/role/status/effectiveUserId/isImpersonating/effectiveUserName/switchableUsers`，**沒有 `configuredAdmin`**。`isConfiguredAdmin` 目前只經 `UserAdminController` 以 `protectedAdmin` 外露在使用者清單 API。故 R51 的驗收條「非主要管理者前端不顯示 Drive 開關」在現況**無法滿足**，本任務必須先補這條管線。
- [ ] 242.5.1 BFF `MeController` 的 `/api/me` response body 新增 `configuredAdmin`（boolean），值取自 business 既有入口 `UserAdminService.isConfiguredAdmin(email)`（與 `UserAdminController` 現行用法相同）。**不要在 BFF 自行比對 `ADMIN_EMAIL`**——那會變成第二個判定入口，與後端 403 的判準各自演化。
- [ ] 242.5.2 `frontend/src/stores/authStore.js` 新增 getter `isConfiguredAdmin: (s) => !!s.me?.configuredAdmin`。**保留既有的 `isAdmin` 不動**（其他既有功能在用），但兩者語意不同，t243／t244 的 Drive 區塊一律用 `isConfiguredAdmin`。
- [ ] 242.5.3 本項屬地基，**t243／t244 的前端條件顯示都依賴它**；若先做 t243 會發現沒有旗標可用。
- [ ] 242.5.4 前端只是「不顯示」，**真正的閘門在後端 403**（242.1.6）。不得把前端判斷當成安全機制。

### 242.6 測試（與實作同屬本任務交付）

- [ ] 242.6 在 `backend/src/test/java/com/steven/assets/service/` 新增 `GdriveOutputSupport` 的單元測試（**測試檔命名由實作者定**），至少涵蓋：
  - `gdriveSubpath` 以 `/` 開頭 → 400
  - 含 `..` 路徑段（如 `a/../../b`）→ 400
  - **`a..b`（合法目錄名，非跳脫）→ 通過**，驗證沒有用 `contains("..")` 誤擋
  - 含 `:`（如 `other-remote:/x`）→ 400
  - 正規化：`投資理財/資產管理/` → `投資理財/資產管理`；空字串／空白 → null
  - 上傳成功時回傳字串含落點與 bytes；rclone 擲例外時**回傳失敗字串而非往外擲**；狀態字串超過 512 字元被截斷
  - **逾時與確定性失敗的措辭不同**（242.2.5.1）：逾時回「逾時（45 秒）：Drive 端可能已完成…」、rclone 非零退出才回「失敗：…」
  - **`copyTo` 的 argv 含 `--retries 1 --low-level-retries 3 --contimeout 10s --timeout 30s`**（242.2.2.2）
  - **上傳失敗字串不含「目錄列舉」字樣**（242.2.2.1 的參數化是否真的做了）
  - **`isDriveAllowedFor`**（242.1.6）：`role=ADMIN` 但 email 非 `ADMIN_EMAIL` → **false**（這是與 `isAdmin` 的關鍵差異）；`ADMIN_EMAIL` 本人 → true；userId 不存在 → **false**（fail-closed）；email 為 null → false
- [ ] 242.6.1 rclone 呼叫一律以 `RcloneClient` 介面替身注入，**不實際連網**。
- [ ] 242.6.2 **t241 的爬蟲頁回歸**：`CrawlerExportPathService` 的既有測試（含本機 `output_subpath` 行為與 Drive 欄位驗證）必須在遷移後**仍然全綠**，證明 242.1.4 是純重構。
- [ ] 242.6.3 Mockito 於本專案需 `-DargLine="-Dnet.bytebuddy.experimental=true"`（直接 `-D` 無效，surefire 會 fork）。

## 驗證

```bash
# 1. 後端測試（Mockito 需 argLine）
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test \
  -DargLine="-Dnet.bytebuddy.experimental=true"

# 2. remote 名稱注入已收斂到單一處（242.1.4 驗收）——只認 @Value 注入點。
#    CrawlerExportPathDto:15 與 CrawlerExportSetting:58 的 Javadoc 也提到這個變數名，那是正常說明、不要刪。
grep -rn '@Value("${GDRIVE_OUTPUT_REMOTE' backend/src/main/java   # 應恰為 1 行

# 3. 從 worktree build 前先把主 repo 的 .env 複製進來（env_file 相對 compose 檔解析）
cp /Users/steven/Project/asset-management/.env .

# 4. JVM service 一律 --no-cache（cached build 會產出不含本次變更的 stale jar）
docker compose -p asset-management build --no-cache business-services
docker compose -p asset-management up -d --no-deps --force-recreate business-services

# 5. recreate business 後必須 restart bff（business 換 IP，BFF 握舊 IP 會回 500，
#    Docker DNS TTL 600s 內不自癒；business log 乾淨、錯只在 bff log）
docker compose -p asset-management restart bff

# 6. 八張表的四個欄位都到位（每張都應回 4）
for t in export_schedule_setting realized_gain_export_schedule asset_transaction_export_schedule \
         commodity_export_schedule exchange_rate_export_schedule index_export_schedule \
         trading_radar_export_setting trading_calendar_export_schedule; do
  printf "%-38s " "$t"
  docker exec asset-postgres psql -U assets -d assets -c "\d $t" | grep -c gdrive_
done

# 7. changeset 已套用
docker exec asset-postgres psql -U assets -d assets -tc \
  "SELECT id FROM databasechangelog ORDER BY orderexecuted DESC LIMIT 2"

# 8. jar 真的含本次變更（不要假設 commit = 已部署）
docker exec asset-business-services sh -c 'unzip -l app.jar | grep -i GdriveOutputSupport'

# 9. config 掛載回歸：兩個容器都掛同一份主檔，且該檔含三個 section。
#    **必須實際讀取而不是只 ls**——host 改過 rclone config 後若沒 --force-recreate，
#    bind mount 會變 dangling：ls 看得到檔案、讀取卻回 ENOENT（設定明明在卻說找不到）。
for c in asset-business-services asset-external-materials-service; do
  printf "%-38s " "$c"
  docker exec "$c" sh -c 'head -c 1 /etc/rclone/rclone.conf >/dev/null && grep -o "^\[.*\]" /etc/rclone/rclone.conf | tr "\n" " "' || echo "讀取失敗（dangling mount？需 --force-recreate）"
  echo
done   # 兩者都應印出 [GoogleDriver] [gdrive-crypt] [GDriveOutput]
```

**必須確認的回歸（本任務不新增 UI，故以既有功能為驗收對象）：**

1. **t241 的爬蟲頁 Drive 同步仍然正常**——這是 242.1.4 遷移是否為純重構的關鍵證據。進 `/crawler-data`，確認設定頁能載入、Drive 樹能展開、「上次上傳」仍顯示先前的成功紀錄；重啟 ext 觸發一輪 warmup 後確認 Drive 上出現當日檔案：
   ```bash
   docker compose -p asset-management restart external-materials-service
   # 等 warmup 跑完（約 60 秒）後：
   docker exec asset-postgres psql -U assets -d assets -tc \
     "SELECT gdrive_last_status FROM crawler_export_setting WHERE crawler_key='news-poller'"
   rclone lsl --config ~/.config/rclone/rclone.conf "GDriveOutput:投資理財/資產管理"
   ```
2. **八頁的既有本機匯出未被影響**：任選兩頁按「立即匯出到目錄」，確認本機落檔與「上次執行」狀態正常（本任務不動 service 邏輯，此為 entity 加欄位的回歸）。
3. **八頁設定頁仍能正常載入與儲存**（entity 加欄位後 DTO 尚未變更，確認沒有序列化問題）。

## 可逆性

- **程式可退回 t241 的狀態**：本任務對 backend 的改動是「抽共用元件＋遷入既有邏輯＋加 `copyTo`」，回退即還原這些檔案。
- **八張表多出的四個欄位在 t243／t244 完成前沒有任何讀取者**（seed 不啟用、service 未整合），故程式回退後 DB 欄位留著也不影響任何行為。
- **changeset 跑過就不得改名或移除**（Liquibase 會認成新 migration 重跑 → `column already exists` → business crash loop）。要「回退 DB」只能另開一支新的 changeset 做 `DROP COLUMN IF EXISTS`，不可刪除 `v1.76.0` 檔案或改它的 id。
- **回退不涉及 `v1.75.0`**（t241 的爬蟲頁欄位），那是獨立且已在生產使用的功能。

## 完成報告

（實作者做完後回填：實際改了哪些檔、測試輸出、八張表欄位驗證輸出、`GDRIVE_OUTPUT_REMOTE` 收斂證據、`/api/me` 的 `configuredAdmin` 實際回應、t241 爬蟲頁回歸證據、與原計畫的偏差及原因。）
