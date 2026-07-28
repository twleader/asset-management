# [t245] 爬蟲輸出檔案同步上傳 Google Drive（本機輸出照寫不變，Drive 為附加副本）

**對應 Requirements:** Requirement 50（「爬蟲資訊查詢」頁的輸出資料夾除本機外，可另外把爬蟲產出的公開資訊 JSON 同步一份到使用者 Google 雲端硬碟的指定目錄）
**前置任務:** 無（建立在 Task 212 已完成的 `crawler_export_setting` 本機輸出路徑機制之上）
**Liquibase changeset:** `v1.75.0-crawler-gdrive-output.sql`

## 背景

> **⚠ 後續變更（2026-07-27，Task 242 之前）：config 佈局已改為單一檔。** 本任務原設計為
> 「獨立 config 檔 `rclone-gdrive-output.conf`，主 config 絕不掛入 ext」以避免爬蟲服務取得備份的
> crypt 解密密碼。**使用者其後明示決定改為共用單一 `~/.config/rclone/rclone.conf`**（三個 section），
> 同一份掛入 business 與 ext；已知代價是 ext 亦可讀備份憑證，取捨理由與復原方式記於
> `spec/steering/tech.md` §4。下方 241.1／241.1.2／241.6.2／241.6.3 與驗證步驟 1b／7b 描述的是
> **當時的決定**，與現行部署不同——現況以 `spec/steering/tech.md` 與 `docker-compose.yml` 為準。
>
> **另一項實作後的實測修正**：本任務原訂「上傳逾時 45 秒」為主要時間控制手段，實測證明治錯了地方——
> rclone 預設 `--low-level-retries 10` ＋ `--timeout 5m` 會把單次上傳放大到數十秒至數分鐘，外層
> process timeout 砍掉它只會讓上傳失敗、不會變快（實測無參數 >180 秒未結束；加上
> `--retries 1 --low-level-retries 3 --contimeout 10s --timeout 30s` 後同一上傳只需 2.4 秒）。
> `ProcessGdriveUploader` 與 `ProcessRcloneClient` 均已加上該組參數。


「爬蟲資訊查詢」頁（`/crawler-data`）目前可設定 `NewsPoller` 每輪產出的公開資訊 JSON 要寫進哪個**本機**資料夾（Task 212）：DB 只存相對子路徑 `crawler_export_setting.output_subpath`，實際目錄 = 容器基底 `EXPORT_OUTPUT_DIR`（`/home/steven`）resolve 之，經 docker volume 對映 host `${EXPORT_OUTPUT_DIR_HOST:-/Users/steven}`。使用者希望除了本機之外，也能同步一份到自己 Google 雲端硬碟的「投資理財 / 資產管理」目錄，這樣沒開這台電腦時也看得到爬回來的資料。

**本任務是「附加」而非「替換」，這一點是硬約束。** `Project/SRPP/data/input/public_info_<yyyy-MM-dd>.json` 正被 **SRPP 退休規劃專案**取用（Task 177 起即為此檔的唯一消費者）。若把 Google Drive 做成「儲存目標」單選，使用者選了 Drive 就會靜默切斷 SRPP 的資料來源——症狀是 SRPP 讀到過期檔案而不會報錯。故：**本機那一份一律照寫、行為完全不變**，Drive 只在本機檔寫成功之後多上傳一份副本，且不提供「只寫 Drive」的選項。

**為什麼不能沿用既有的 Drive 整合。** 全庫既有的唯一 Google Drive 整合是 DB 備份／還原（`BackupService`），走 rclone crypt remote `gdrive-crypt:`（＝`GoogleDriver:asset-management-backup` 的加密層）。它不適用於本任務有兩個獨立原因：

1. **crypt remote 的檔名與內容皆加密**，使用者無法在 Drive 網頁上閱讀，違背本需求目的。
2. **底層 `GoogleDriver:` 的 OAuth `scope = drive.file`**——該 scope 下 rclone **只看得到自己建立的檔案**。已實測：`rclone lsd GoogleDriver:` 只列出 `asset-management-backup` 一個目錄，使用者手動建立的「投資理財」根本列不出來，因此無法寫進去。

故本任務改用**獨立的新 remote**（`scope = drive`、未加密）。刻意不改既有 `GoogleDriver:` 的 scope：重新授權若失敗會連帶弄壞正在運作的 DB 備份／還原，而備份是災難復原的最後一道防線。

## 要做什麼

### 241.1 使用者前置作業：建立 rclone remote（不由程式執行）

- [ ] 241.1 這一步**由使用者本人執行一次**，實作者不得代跑、不得把 client secret 寫進程式或 repo。**必須建立在獨立的 config 檔**（理由見 241.1.2，這是硬約束）：

  ```bash
  rclone config create --config ~/.config/rclone/rclone-gdrive-output.conf \
    GDriveOutput drive scope=drive
  ```

  會開瀏覽器要求 Google 授權（須選使用者本人的 Drive 帳號）。完成後以下指令應能列出目標目錄，確認 `scope=drive` 生效（`drive.file` 下這條會回 `directory not found`）：

  ```bash
  rclone lsd --config ~/.config/rclone/rclone-gdrive-output.conf "GDriveOutput:投資理財"
  ```

  產出的檔案**必須只含 `[GDriveOutput]` 一段**（可用 `grep '^\[' ~/.config/rclone/rclone-gdrive-output.conf` 確認只有一行）。

  remote 名稱**不得寫死在程式碼裡**，由環境變數 `GDRIVE_OUTPUT_REMOTE`（預設 `GDriveOutput`）提供。

- [x] 241.1.1 若 `config create` 沒有自動開瀏覽器，改用互動式：`rclone config --config ~/.config/rclone/rclone-gdrive-output.conf` → `n`（new remote）→ 名稱 `GDriveOutput` → storage 選 `drive` → scope 選 **`1` (drive，完整存取)** → 其餘留空 → `y` 確認。注意：不帶 `client_id`／`client_secret` 時 rclone 使用其**內建共用 Google OAuth client**（配額與所有 rclone 使用者共享，尖峰可能被限流）；若遇限流，可自建 Google Cloud OAuth client 並填入，但**憑證由使用者持有、不得進 repo**。

- [x] 241.1.2 **為什麼必須是獨立 config 檔（資安硬約束，不可簡化成「沿用既有那份」）**：既有的 `~/.config/rclone/rclone.conf` 含兩段機密——`[GoogleDriver]` 的 `token`（DB 備份 remote 的 OAuth refresh token）與 `[gdrive-crypt]` 的 `password`（加密備份的解密密碼）。而 `external-materials-service` 是**全 stack 唯一對外打第三方的服務**（TWSE／NASDAQ／FinMind／新聞爬蟲），攻擊面最大。若把主 config 掛進 ext，等於讓爬蟲服務具備**解密整庫財務備份**的能力，這比本任務要求的 `scope=drive` 是高一個數量級的擴權。故：**主 config 絕不掛入 ext**，ext 只掛只含 `[GDriveOutput]` 的獨立檔。

- [x] 241.1.3 `scope = drive` 的權衡須在程式註解中記載：新 remote 取得使用者 Drive 的**完整讀寫權**（非 `drive.file` 的「僅限自己建立的檔案」），這是能寫進使用者**手動建立**的既有目錄所必要的。程式端自我約束：**只實作 `lsjson --dirs-only`（列目錄）與 `copyto`（寫入指定子路徑）兩種 rclone 操作，不實作任何刪除既有 Drive 檔案的程式路徑**，把完整權限的實際使用面縮到最小。

### 241.2 Liquibase changeset `v1.75.0-crawler-gdrive-output.sql`

- [x] 241.2 於 `backend/src/main/resources/db/changelog/changes/` 新增，並在 `db.changelog-master.yaml` 尾端 `include`（現有最大版號為 `v1.74.0-asset-transaction-price-scale.sql`，故用 `v1.75.0`）。為 `crawler_export_setting` 加四欄：

  ```
  gdrive_enabled       BOOLEAN      NOT NULL DEFAULT false
  gdrive_subpath       VARCHAR(512)                          -- nullable
  gdrive_last_run_at   TIMESTAMP                             -- nullable
  gdrive_last_status   VARCHAR(512)                          -- nullable
  ```

- [x] 241.2.1 **changeset 必須冪等**（`ADD COLUMN IF NOT EXISTS`）。且 changeset 一旦跑過就**不得改名或改 id**——改名會被 Liquibase 認成新 migration 重跑，`column already exists` 會讓 business 進 crash loop 而整站掛掉。若需為版號避讓而調整，先查 `databasechangelog` 是否已跑過。
- [x] 241.2.2 **不要用 `db/schema.sql` 判斷這張表的現況**（實測陷阱）：真正的 schema 基準線是 `db/init/01_dump.sql`（pg_dump），但它含真實財務資料而被 `.gitignore` 排除（資安 Requirement 29），版控中看不到。`db/schema.sql` 只是它「去除資料」後的可版控鏡像、**刻意不會被執行**（放在 `db/` 而非 compose 掛載的 `db/init/`），且**目前已落後**——其中沒有 `crawler_export_setting`（v1.64.0 / Task 212 建立）也沒有 `asset_transaction`（v1.72.0）。要確認欄位現況請直接查運行中的 DB：
  ```bash
  docker exec asset-postgres psql -U assets -d assets -c '\d crawler_export_setting'
  ```
- [x] 241.2.3 **seed 不啟用**：不寫任何 `UPDATE ... SET gdrive_enabled = true`。既有部署升級後行為與現況完全一致（只寫本機、不碰 Drive、不要求 rclone remote 存在）。

### 241.3 backend：entity、DTO、驗證

- [x] 241.3 `model/CrawlerExportSetting.java` 加四個欄位（`@Column(name=...)`，型別 `Boolean`／`String`／`Instant`／`String`）。`gdriveEnabled` 對應 `nullable = false`。
- [x] 241.3.1 `dto/CrawlerExportPathDto.java`：
  - `Response` record 加 `gdriveEnabled`（boolean）、`gdriveSubpath`、`gdriveRemote`（＝`GDRIVE_OUTPUT_REMOTE` 現值，**衍生顯示值不入庫**，讓前端能在錯誤訊息中指名 remote）、`gdriveLastRunAt`（格式化字串，比照既有 `updatedAt` 用 `yyyy-MM-dd HH:mm:ss` ＋ Asia/Taipei）、`gdriveLastStatus`。
  - `Request` record 加 `gdriveEnabled`、`gdriveSubpath`。
- [x] 241.3.2 `service/CrawlerExportPathService.java` 的 `update()` 加 Drive 驗證，全部違反回 `IllegalArgumentException`（既有 controller 已對映 400）：
  - `gdriveSubpath` 正規化：去頭尾空白、**只剝結尾** `/`。**開頭的 `/` 刻意不剝除，直接回 400**——這與既有 `CrawlerExportPathService` 對本機 `outputSubpath` 的處理一致，但**注意職責分在兩個 method**：`normalizeSubpath()` 只做去頭尾空白＋剝結尾斜線（不處理開頭 `/`、不擲例外），400 是由 `resolveDir()` 產生的，該註解原文即在 `resolveDir()` 上：「開頭的 `/` 刻意**不**先剝除——絕對路徑一律回 400 讓使用者知道只能填相對子路徑，勝過默默改寫語意」。Drive 側**不能沿用 `resolveDir()` 那套 `Path` 判斷**（見本節末），所以要另寫純字串檢查，但語意要對齊它。**注意：先剝開頭斜線再檢查「是否以 `/` 開頭」的寫法會讓該 400 分支永遠不可達**，241.10 的對應測試就會失敗。
  - **不得含 `..` 路徑段**：以 `/` 切開後**逐段**比對是否等於 `..`，不可只用 `contains("..")`——那會誤擋合法目錄名如 `a..b`（241.10 有此案例）。**理由要寫對**：rclone 對 Drive remote **不做**路徑正規化，`..` 被當字面目錄名（實測 `rclone lsd "GoogleDriver:asset-management-backup/.."` 回 `directory not found`、exit 3），所以擋 `..` **不是**在防目錄跳脫；目的是避免在使用者 Drive 上建出字面名為 `..` 的怪目錄，並與本機規則保持一致。
  - **額外拒收含 `:` 的子路徑**。rclone 指令以 `<remote>:<subpath>` 形式**拼接在同一個 argv 元素內**，而 rclone 取第一個 `:` 之前為 remote 名，故 `GDriveOutput:other-remote:/x` 的 remote 仍是 `GDriveOutput`——remote 注入在結構上已被擋住。但這個保證依賴「remote 前綴與子路徑同一個 argv 元素」這個實作細節，一旦日後被重構拆開（或把 `gdrive_subpath` 當完整 remote spec 使用）就失效，故多擋一層。（Windows 風格 `\` 在 Drive 上是合法檔名字元、不構成逃脫，不需特別處理。）
  - **`gdriveEnabled == true` 時 `gdriveSubpath` 為必填**，空值回 400（避免啟用了卻把檔案倒在 Drive 根目錄）。
  - `gdriveEnabled == false` 時允許 `gdriveSubpath` 為空，且**不得清掉使用者先前填過的值**（關掉再開回來不必重填）。
  - **不得**用 `Path.of(...).normalize().startsWith(base)` 驗 Drive 路徑：Drive 路徑不是本機檔案系統路徑，在容器內 resolve 沒有意義，且會讓驗證結果隨容器 OS 的路徑分隔符改變。用純字串分段驗證。
- [x] 241.3.3 `get()` 回傳時**不得因既有 Drive 值不合法而擲例外**（沿用既有 `absolutePathOrNull` 的同一理由：DB 值可能被繞過 API 直改，若讀取也失敗，設定頁會 500，使用者就**沒有任何入口能把它改回正常值**——唯一的修正入口被自己鎖死）。不合法值照原樣回傳供前端顯示，存檔時才驗。
- [x] 241.3.4 `update()` **不得**碰 `gdrive_last_run_at` / `gdrive_last_status`（那是 ext 寫入的執行結果，不是使用者設定）。

### 241.4 backend：Drive 目錄列舉端點

- [x] 241.4 `service/ExportScheduleService.java` 新增 `browseGdrive(String subpath)`，與既有 `browse(String subpath)` **並列於同一支 service**。兩者語意不同（一個列本機基底下子目錄、一個列 Drive remote 下子目錄），故為兩支方法／兩支端點而非加參數。
  **「Drive 目錄列舉」全庫只准有這一支實作。** 本機目錄列舉目前**已經是分裂的**（實測：9 個有資料夾選擇器的 view 中，8 支 BFF passthrough 到 `GET /api/export-schedule/browse`，但**交易日曆走自己的第二份** `GET /api/trading-calendar-export/browse` → `TradingCalendarExportService.browse()`）。本任務**不修**那個既有分裂（超出範圍），但**不得複製它**：日後其餘頁面接 Drive 時一律沿用 `browseGdrive`，**含交易日曆頁**——即交易日曆的 Drive 列舉也走 `export-schedule/browse-gdrive`，不得在 `TradingCalendarExportService` 再開第二份 Drive 實作（CLAUDE.md「不同頁面顯示同樣意義的值須呼叫同一支 business service API」）。
- [x] 241.4.1 `browseGdrive` 行為：`requireOwnerId()`（需登入）；以 `rclone lsjson --dirs-only "<remote>:<subpath>"` 列舉，解析 JSON 取 `Name`，依名稱不分大小寫排序，回與本機 `browse` **同形狀**的 response（`baseDir` 欄位放 `<remote>:`、`subpath`、`directories[{name,path}]`，`absolutePath` 放 `<remote>:<subpath>`），讓前端能沿用同一個 `el-tree` 元件與同一組欄位。**唯讀：不建立、不刪除、不修改 Drive 上任何內容。**
- [x] 241.4.2 **`browseGdrive` 的子路徑規則與 `update()` 刻意不同，不要統一成一條**：`..` 段檢查與 `:` 拒收兩者相同（同 241.3.2），但**開頭斜線的處理相反**——`browseGdrive` 比照既有 `ExportScheduleService.browse()` 的寬鬆版**剝除**開頭 `/`（`while (sub.startsWith("/")) sub = sub.substring(1);`），`update()` 則不剝、直接 400（同 `CrawlerExportPathService.normalizeSubpath()`）。這個不對稱是本專案既有的刻意設計：瀏覽是唯讀導覽、對輸入寬容；儲存是寫入設定、要讓使用者明確知道只能填相對路徑。
- [x] 241.4.3 **remote 未設定／授權失效時必須回可讀的錯誤訊息，不得 500、不得回空樹**——空樹會被使用者誤讀為「Drive 裡沒有資料夾」。rclone 找不到 remote 時 exit code 非 0 且 stderr 含 `didn't find section in config file`；此情形回明確訊息如「Google Drive remote `GDriveOutput` 尚未設定或授權失效，請先執行 rclone config create」。
- [x] 241.4.4 `controller/ExportScheduleController.java` 新增 `GET /api/export-schedule/browse-gdrive?subpath=`，權限與既有 `browse` 相同（`authenticated`）。
- [x] 241.4.5 rclone 呼叫抽成**介面**（如 `RcloneClient`，方法 `listDirs(remote, subpath)`）並以 `ProcessBuilder` 實作，讓 241.10 的測試能替換掉、不實際連網。逾時作法沿用 `BackupService`（`p.waitFor(timeout, SECONDS)`，逾時 `destroyForcibly()` 後擲例外），但**逾時值必須是 20 秒、不得沿用 `BackupService.PROCESS_TIMEOUT_SEC` 的 300 秒**——那是為 pg_dump／整庫上傳設的，而 `browse-gdrive` 是使用者點樹節點的懶載入，300 秒等於點一下卡五分鐘。
- [x] 241.4.6 **`RcloneClient` 只認 output config，絕不碰備份用的主 config**（承 241.1.2 的憑證隔離）。business 容器會同時掛到兩份 config，用途不得混用：

  | 用途 | 唯讀掛入路徑 | 可寫副本 | 使用者 |
  |---|---|---|---|
  | DB 備份／還原（既有） | `/etc/rclone/rclone.conf` | `/tmp/rclone.conf` | `BackupService`（既有，本任務不動） |
  | Drive 輸出／目錄列舉（新增） | `/etc/rclone/rclone-output.conf` | `/tmp/rclone-output.conf` | `RcloneClient`（本任務新增） |

- [x] 241.4.7 **config 必須先複製到可寫路徑再用，不得直接用唯讀掛入路徑**——rclone 會在 OAuth token 到期時自動續期並寫回 config，對唯讀檔寫回失敗會使 rclone exit non-zero，症狀是「原本好的上傳在數十分鐘後開始整批失敗」。作法沿用 `BackupService` 既有模式（`backend/.../service/BackupService.java`）：
  - `@PostConstruct` 時 `Files.copy(唯讀來源 → /tmp/rclone-output.conf, REPLACE_EXISTING)`，並 `setPosixFilePermissions` 為僅 owner 可讀寫（`rw-------`）；來源不存在只 `log.warn`（Drive 功能不可用，但**不得**讓服務啟動失敗——備份功能與整個 business 都不該被這個新功能拖垮）。
  - 每次 `ProcessBuilder` 呼叫時以 `pb.environment().put("RCLONE_CONFIG", "/tmp/rclone-output.conf")` 覆寫（＝`BackupService` 既有作法：compose 層的 `RCLONE_CONFIG` 是預設值，per-process 覆寫才是實際生效值）。**兩者都要做**：只設 compose → token 續期寫回失敗；只覆寫而漏掉複製步驟 → 找不到 config。

### 241.5 BFF：passthrough

- [x] 241.5 `bff/.../crawlerdata/CrawlerDataBffController.java` 新增 `GET /api/bff/crawler-data/export-path/browse-gdrive?subpath=` → business `GET /api/export-schedule/browse-gdrive`（依「一個前端頁面一個 BFF」，前端不直接呼叫 business）。
- [x] 241.5.1 既有 `GET/PUT /api/bff/crawler-data/export-path` **不新增第二支設定端點**——同一張設定表、同一支 business API `GET/PUT /api/crawler-export-path`，只是多了 Drive 欄位。**該 BFF 現況是 `Map<String, Object>` 直通**（`bodyToMono(MAP)` / `bodyValue(body)`），沒有 DTO 鏡像類別，故新欄位會自動穿透、本項無需改動；**但不得順手改成強型別 DTO**——那就必須同步加上四個新欄位，漏一個就會讓 Drive 設定在 BFF 層被靜默吃掉（前端存了卻沒生效）。
- [x] 241.5.2 權限：`GET` 與 `browse-gdrive` 為 `authenticated`；`PUT` 維持限 ADMIN（Drive 子路徑決定服務往使用者雲端硬碟寫入的位置，與本機輸出路徑同理）。

### 241.6 ext 容器：rclone 與設定檔（部署前提）

- [x] 241.6 `external-materials-service/Dockerfile`：runtime stage 的 `RUN apk add --no-cache curl` 改為同時裝 `rclone`（`apk add --no-cache curl rclone`）。
- [x] 241.6.1 **中文目錄名編碼：現況已正確，不要「修」它，但不得破壞它。** 目標目錄名為中文（「投資理財/資產管理」）。Java 傳給 `ProcessBuilder` 的參數編碼取決於 `sun.jnu.encoding`；若它退化成 ASCII，中文路徑會變成 `?` 而上傳到錯誤的目錄名，且 rclone 不會報錯（靜默的錯）。**但實測 ext 容器現況已是 UTF-8**，因為 base image `eclipse-temurin:21-jre-alpine` 自帶 locale：

  ```
  # docker exec asset-external-materials-service sh -c 'java -XshowSettings:properties -version 2>&1 | grep -i encoding'
  file.encoding = UTF-8    native.encoding = UTF-8    sun.jnu.encoding = UTF-8
  # docker exec asset-external-materials-service env | grep -iE '^LANG|^LC_'
  LANG=en_US.UTF-8   LANGUAGE=en_US:en   LC_ALL=en_US.UTF-8
  ```

  故**不需要、也不要**在 ENTRYPOINT 加 `-Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8`——**實測該命令列覆寫對 `sun.jnu.encoding` 無效**（JDK 由 platform locale 決定它，非命令列；以 `-e LC_ALL=C` 模擬退化時，即使帶上這兩個 `-D`，`native.encoding` 與 `sun.jnu.encoding` 仍為 ASCII）。實際約束是：
  - **不得**在 Dockerfile 加 `ENV LANG=C`／`LC_ALL=C`，或以任何方式清除 base image 的 locale 設定；
  - **不得**更換 base image 到不帶 UTF-8 locale 的版本；
  - 若日後真需明確固定，唯一有效手段是 Dockerfile `ENV LANG=C.UTF-8 LC_ALL=C.UTF-8`（環境變數層），**不是** JVM `-D` 參數。

  驗證段第 8 步保留為**回歸守門**（確認仍是 UTF-8），不是「確認修法生效」。
- [x] 241.6.2 `docker-compose.yml` 的 `external-materials-service` 加：
  - `environment`: `GDRIVE_OUTPUT_REMOTE: ${GDRIVE_OUTPUT_REMOTE:-GDriveOutput}`、`RCLONE_CONFIG: /etc/rclone/rclone-output.conf`
  - `volumes`: `- ${HOME}/.config/rclone/rclone-gdrive-output.conf:/etc/rclone/rclone-output.conf:ro`

  **絕對不要**加 `- ${HOME}/.config/rclone/rclone.conf:...`——那份含備份 remote 的 refresh token 與 crypt 解密密碼（見 241.1.2）。ext 只掛 output config。
- [x] 241.6.3 `docker-compose.yml` 的 `business-services` **新增**（既有的主 config 掛載保留不動，備份功能需要它）：
  - `environment`: `GDRIVE_OUTPUT_REMOTE: ${GDRIVE_OUTPUT_REMOTE:-GDriveOutput}`
  - `volumes`: `- ${HOME}/.config/rclone/rclone-gdrive-output.conf:/etc/rclone/rclone-output.conf:ro`

  business 端的 `RCLONE_CONFIG` 環境變數維持現值（`/etc/rclone/rclone.conf`，備份用預設）；Drive 輸出走 per-process 覆寫（241.4.7），不改 compose 層的預設值。
- [x] 241.6.4 ext 端同樣需要 **rclone config 可寫副本**（理由與作法同 241.4.7，來源 `/etc/rclone/rclone-output.conf` → 副本 `/tmp/rclone-output.conf`）。注意 ext 容器以 **非 root 的 `appuser`** 執行（`USER appuser`），複製目的地須為 appuser 可寫（`/tmp` 可）；來源不存在時只 `log.warn` 並讓 Drive 上傳整段跳過，**不得**讓 ext 啟動失敗或影響爬蟲既有功能。
- [x] 241.6.5 `.env.example`（已存在於 repo 根目錄）補上 `GDRIVE_OUTPUT_REMOTE` 與說明（含「需先依 241.1 建立獨立 config 檔」）。
- [x] 241.6.6 **`.env` 陷阱**：`docker-compose.yml` 的 `env_file` 相對 compose 檔解析，從 worktree 跑 `compose build` 前須先把主 repo 的 `.env` 複製進 worktree（`--env-file` 救不了）。

### 241.7 ext：上傳邏輯

- [x] 241.7 `service/CrawlerExportPathQuery.java` 加讀取方法，**一次查詢取回兩欄** `gdrive_enabled` 與 `gdrive_subpath`（不要拆成兩支各查一次）。用**雙參數 `RowMapper`**：

  ```java
  jdbc.query("SELECT gdrive_enabled, gdrive_subpath FROM crawler_export_setting WHERE crawler_key = ?",
      ps -> ps.setString(1, crawlerKey),
      (rs, rowNum) -> new GdriveCfg(rs.getBoolean("gdrive_enabled"), rs.getString("gdrive_subpath")));
  ```

  **關鍵是參數個數，不是有沒有大括號**：雙參數 `(rs, rowNum) -> ...` 是 `RowMapper`（由 Spring 逐列呼叫、已定位好 `rs`，**必須有回傳值**）；**單參數** `(rs) -> ...` 會被解析成 `ResultSetExtractor`（Spring 只呼叫一次、`rs` **未** `next()` 定位，讀欄位會 runtime 才炸）。查無列時 `query` 回空 list，呼叫端當作「未啟用 Drive」處理。
- [x] 241.7.1 `service/NewsPoller.java` 的 `exportPublicInfoJson(String trigger)`：在既有 `Files.move(tmp, file, ATOMIC_MOVE)` **成功之後、方法結束前**，插入 Drive 上傳步驟。順序不可顛倒——本機檔案是 SRPP 的資料來源，必須先確定它寫成功。
- [x] 241.7.2 上傳步驟以**獨立的 try-catch** 包住，`catch (Exception e)` 只記 `log.error` 並寫 DB 狀態欄：**絕不** rollback 本機檔案、**絕不**讓本輪 `news_headline` 入庫失敗、**絕不**擲例外中斷排程。（既有 `exportPublicInfoJson` 整段已有 graceful catch，但 Drive 失敗不應與「本機寫檔失敗」混為同一個 warn，需可分辨。）
- [x] 241.7.3 上傳指令：`rclone copyto <本機檔絕對路徑> <remote>:<gdrive_subpath>/public_info_<yyyy-MM-dd>.json`。用 `copyto`（而非 `copy`）以明確指定目的檔名。**檔名不開放設定**（維持 `public_info_<日期>.json`，SRPP 依此檔名取用）。同日多輪覆寫＝當日最新、跨日新檔，與本機一致。

  **Drive 側刻意不做本機那套「tmp ＋ atomic rename」**：Drive API 未完成的上傳不會產生可見檔案，逾時被 `destroyForcibly` 後最壞情況是該次上傳沒發生，而非留下半截檔。**這是有意識的接受**，不是漏想——殘餘風險由下一輪覆寫消除，且 `gdrive_last_status` 會顯示該輪失敗。不要為此在 Drive 上實作 tmp 檔＋改名（那需要 delete 權限的程式路徑，與 241.1.3 的自我約束衝突）。
- [x] 241.7.4 **設定每輪即時讀取、免重啟**：`gdrive_enabled` / `gdrive_subpath` 比照現行 `output_subpath` 於**每輪寫檔時**讀 DB 現值，不快取於欄位。頁面改設定後下一輪（含開機 warmup）即生效。
- [x] 241.7.5 **寫檔前再驗一次跳脫**（縱深防禦，比照既有 `resolveExportDir()` 的同一理由：ext 才是實際執行寫入的一方，不能只信上游驗過——DB 值可能被 psql 直改或跨環境還原繞過 API）。`gdrive_subpath` 不合法時**跳過上傳並 warn**，不要 fallback 到某個預設 Drive 目錄（本機的 fallback 是為了「不要不寫」，但把檔案倒進使用者 Drive 的非預期位置比不上傳更糟）。
- [x] 241.7.6 **不實作 retry queue**：爬蟲每輪都重新產生當日完整檔案並重新上傳，下一輪即為天然重試（Drive 端覆寫同名檔案本身冪等）。
- [x] 241.7.7 **上傳逾時上限 45 秒，逾時視為該輪上傳失敗。** 理由不是「避免阻塞排程執行緒」——`NewsPoller` 早已把抓取丟到獨立執行緒（`new Thread(() -> runGuarded("scheduled"), "news-scheduled").start()`，其註解原文即說明是為了不阻塞 ext 共用的單執行緒排程器）。真正的考量是 `runGuarded` 的 `AtomicBoolean running`（warmup 與排程輪共用）：它被持有期間，後續輪次會因「上一輪尚未結束」被整個跳過。**但要誠實看清這個旗標的持有時間主要由抓取決定**——`run()` 先做 `newsClient.fetchAll()` 等外部抓取，才呼叫 `exportPublicInfoJson`；Drive 逾時上限的作用只是**不再額外拉長**它，不是保證旗標能在某個時間內釋放。取 45 秒（明確小於 ticker 的 60 秒週期）是這個「不再額外拉長」的下限成本。**已知殘餘情形**：若使用者把兩個執行時點設在相鄰分鐘，下一輪仍可能被跳過——**這是 DB 驅動排程的既有行為，不由本任務解決，也不得為它加補償邏輯**。
- [x] 241.7.8 上傳結果寫回 DB：`UPDATE crawler_export_setting SET gdrive_last_run_at = ?, gdrive_last_status = ? WHERE crawler_key = ?`。成功記落點與檔案大小，失敗記錯誤訊息摘要（**截斷至 512 字元以內**以符合欄位長度，rclone stderr 可能很長）。這是 ext 對 `crawler_export_setting` 的**唯一寫入**，且**只碰這兩欄**——ext 原本對此表純讀（schema 由 backend 擁有），此處為刻意的例外：上傳結果只有 ext 知道，沒有別的地方能寫。

  **凡本輪「已啟用 Drive 但沒有實際上傳」也必須寫狀態欄**，內容明示原因（例「跳過：Drive 子路徑不合法」「跳過：rclone config 不可用」「跳過：本機檔寫入失敗」）。涵蓋三條跳過路徑：241.7.5 的子路徑不合法、241.6.4 的 config 來源不存在、以及本機寫檔失敗導致整段不執行。理由：若只在「嘗試過上傳」時才寫，`gdrive_last_status` 會停留在**上一次的「成功」**，設定頁顯示的就是過期的好消息——而本任務設這兩欄的立論正是「上傳目的地不在使用者眼前，不回報就是靜默失敗」。目標是讓 `gdrive_last_run_at` 恆為「最近一次判斷結果」，不是「最近一次成功」。
- [x] 241.7.9 **`UPDATE` 命中 0 列時只記 `warn`，不得改成 upsert／INSERT。** 該列可能不存在——business 端查無設定時回的是**未落庫的預設物件**（`repo.findByCrawlerKey(...).orElseGet(() -> fallback)`，不寫 DB），而 v1.64.0 的 seed 在被繞過或跨環境還原後也不保證有列。這張表的列由 backend 擁有，ext 不得代為建立（否則兩邊都能建列，`crawler_key` UNIQUE 的競態與所有權就模糊了）。
- [x] 241.7.10 **狀態欄寫入本身的失敗也必須被吞掉**：241.7.2 的 try-catch 須涵蓋 `UPDATE`（DB 短暫不可用時 `UPDATE` 會擲例外），其失敗只記 log，**不得**反過來影響本機檔案或 `news_headline` 入庫。即「回報上傳結果」這件事本身不能成為新的失敗來源。

### 241.8 前端：CrawlerDataView 設定卡

- [x] 241.8 `frontend/src/views/CrawlerDataView.vue` 的「爬蟲輸出檔案設定」卡，在既有「輸出資料夾」欄位**之下**新增：
  - 「同步上傳 Google Drive」`el-switch`
  - Drive 目標資料夾欄位 ＋「選擇」按鈕（開啟樹狀選擇器 dialog），**沿用既有本機選擇器的同一套 `el-tree` 懶載入寫法**，只把載入來源換成 `GET /api/bff/crawler-data/export-path/browse-gdrive`
  - 「上次上傳」唯讀顯示（`gdriveLastRunAt` ＋ `gdriveLastStatus`，無值顯示「—」）
- [x] 241.8.1 開關關閉時 Drive 資料夾欄位與「選擇」按鈕停用（但**保留已填的值**，不清空）。開關開啟而 Drive 資料夾為空時，前端即擋下儲存並提示（後端亦會回 400，前後端都擋）。
- [x] 241.8.2 Drive 樹載入失敗時，把後端回的錯誤訊息顯示在 dialog 內（例如 remote 未設定），**不得顯示成空樹**——空樹會被誤讀為「Drive 裡沒有資料夾」。
- [x] 241.8.3 非 ADMIN 沿用既有作法隱藏／停用儲存並提示（新開關與新欄位一併比照）。
- [x] 241.8.4 頁面文案須說明**本機仍會照寫**（例如「Drive 為額外備份，本機輸出不受影響」），避免使用者誤以為開了 Drive 就不寫本機而去改動 SRPP 的讀取設定。
- [x] 241.8.5 **必須同步改 `frontend/src/api/index.js`，漏改會讓 Drive 設定靜默存不進去。** 現況只送一個欄位：
  ```js
  // frontend/src/api/index.js（crawlerData 區塊，現況）
  saveExportPath: (outputSubpath) => api.put('/bff/crawler-data/export-path', { outputSubpath }),
  ```
  改為接受完整 payload，並新增 Drive 樹的 helper（`skipErrorToast: true` 是既有慣例，讓 241.8.2 的 dialog 內錯誤顯示不與全域 toast 打架，比照同檔其他 `browseExportDir`）：
  ```js
  saveExportPath: (payload) => api.put('/bff/crawler-data/export-path', payload),
  browseGdriveExportDir: (subpath = '') =>
    api.get('/bff/crawler-data/export-path/browse-gdrive', { params: { subpath }, skipErrorToast: true }),
  ```
  呼叫端改傳 `{ outputSubpath, gdriveEnabled, gdriveSubpath }`。
- [x] 241.8.6 前端只呼叫自己頁面的 BFF（`/api/bff/crawler-data/*`），不直接呼叫 business；且**所有請求一律經 `api/index.js`**，view 內不得裸用 `axios`／`fetch`。

### 241.9 排程列表頁說明同步

- [x] 241.9 本任務**未新增任何 `@Scheduled` 排程**（上傳掛在 `NewsPoller` 既有輪次內），故「公開資訊 → 排程列表」（`bff/.../schedulelist/SchedulePublicBffController` 的 `JOBS`）**不需新增項目**。但須把對應那筆的 description 尾端補上「輸出含 Google Drive 同步（若已啟用）」，避免頁面與實際行為漂移（Task 188／195 即為修正此類漂移而生）。

  **注意：`JOBS` 裡沒有任何字串叫 `news-poller`**（`grep news-poller` 該檔為 0 命中），它是無鍵的 `List<ScheduledJobDto>`。要改的是 `category = EXTERNAL`、group `"財經新聞"`、name `"財經新聞抓取"` 那筆（現於 `SchedulePublicBffController.java:186`，其 description 開頭為「抓取財經新聞＋公開資訊快照；執行時間改由 DB 驅動…」）。

### 241.10 測試

- [x] 241.10 在 `backend/src/test/java/com/steven/assets/service/` 新增 `CrawlerExportPathService` 的單元測試（**測試檔命名由實作者定**，比照既有 service 測試的風格與命名慣例；與實作同屬本任務交付），至少涵蓋：
  - `gdriveSubpath` 以 `/` 開頭 → 400
  - `gdriveSubpath` 含 `..` 路徑段（如 `a/../../b`）→ 400
  - `gdriveSubpath` 為 `a..b`（合法目錄名，非跳脫）→ **通過**，驗證沒有用 `contains("..")` 誤擋
  - `gdriveEnabled = true` 但 `gdriveSubpath` 為空／null → 400
  - `gdriveEnabled = false` 且 `gdriveSubpath` 為空 → 通過，且不清掉先前已存的值
  - 正常值 upsert 後可讀回（含中文子路徑 `投資理財/資產管理`）
  - **回歸**：既有本機 `output_subpath` 的驗證與正規化行為不因新欄位而改變
- [x] 241.10.1 `browseGdrive` 的測試以 241.4.5 的 `RcloneClient` 介面替身注入，**不實際連網**：正常列舉、remote 不存在（回可讀錯誤而非 500）、子路徑跳脫（400）、子路徑含 `:`（400）四種。
- [x] 241.10.2 Mockito 於本專案需 `-DargLine="-Dnet.bytebuddy.experimental=true"`（直接 `-D` 無效，surefire 會 fork）。
- [x] 241.10.3 **ext 端的上傳邏輯也必須有測試**——那是本任務風險最高的部分（三條「絕不」保證全在這裡），而 `external-materials-service/src/test/java/.../service/` 已有既有測試可比照（`PriceCacheWriterTest`、`IntradayTickRefresherSelfHealTest` 等），不是無處可放。以替身注入 rclone 呼叫與 `JdbcTemplate`（**不實際連網、不碰真 DB**），涵蓋：
  - `gdrive_enabled = false` → **完全不呼叫 rclone**（驗證替身零互動）
  - rclone 呼叫擲例外 → 本機檔案仍存在、`news_headline` 入庫不受影響、**不向外擲例外**
  - 狀態欄 `UPDATE` 自身擲例外（模擬 DB 短暫不可用）→ 同樣被吞掉，不影響本機檔與入庫（241.7.10）
  - `UPDATE` 命中 0 列 → 只 warn，**不得**改走 INSERT／upsert（241.7.9）
  - `gdrive_subpath` 不合法 → 跳過上傳，且**仍寫入狀態欄**說明跳過原因（241.7.8 末段）

## 驗證

```bash
# 1. 使用者已完成 241.1（remote 存在且 scope=drive 生效；drive.file 下這條會回 directory not found）
rclone lsd --config ~/.config/rclone/rclone-gdrive-output.conf "GDriveOutput:投資理財"
# 1b. 該 config 必須只含 [GDriveOutput] 一段（不得含 GoogleDriver / gdrive-crypt）
grep '^\[' ~/.config/rclone/rclone-gdrive-output.conf   # 期望只有一行：[GDriveOutput]

# 2. 後端測試（Mockito 需 argLine，直接 -D 無效）
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test \
  -DargLine="-Dnet.bytebuddy.experimental=true"

# 3. 從 worktree build 前先把主 repo 的 .env 複製進來（env_file 相對 compose 檔解析）
cp /Users/steven/Project/asset-management/.env .

# 4. JVM service 一律 --no-cache 重建（cached build 會產出不含本次變更的 stale jar，
#    症狀是前端有新功能、後端 404）
docker compose -p asset-management build --no-cache business-services external-materials-service
docker compose -p asset-management build --no-cache frontend
docker compose -p asset-management up -d --no-deps --force-recreate business-services external-materials-service frontend

# 5. recreate business 後必須 restart bff：business 換 IP 而 BFF 握舊 IP 會回 500，
#    且 Docker DNS TTL 600s 內不會自癒（business log 乾淨、錯只在 bff log 的 Connection refused）
docker compose -p asset-management restart bff

# 6. 確認 jar 真的含本次變更（不要假設 commit = 已部署）
docker exec asset-external-materials-service sh -c \
  'unzip -l app.jar | grep -i rclone'

# 7. ext 容器內 rclone 可用、且讀得到掛入的 output config（appuser 非 root，須確認讀得到
#    host 的 -rw------- 檔；Docker Desktop for Mac 的 bind mount 通常放寬權限）
docker exec asset-external-materials-service rclone --version
docker exec asset-external-materials-service sh -c \
  'RCLONE_CONFIG=/tmp/rclone-output.conf rclone lsd "GDriveOutput:投資理財"'

# 7b. 憑證隔離驗證（241.1.2，負向）——ext 必須拿不到備份 remote 的 token 與 crypt 密碼。
#     兩條都必須「查無」；任何一條有輸出即為資安缺陷，須修掛載。
docker exec asset-external-materials-service sh -c \
  'grep -l "gdrive-crypt" /etc/rclone/*.conf /tmp/*.conf 2>/dev/null; echo "exit=$?"'
docker exec asset-external-materials-service sh -c 'ls /etc/rclone/'   # 期望只有 rclone-output.conf

# 8. 中文編碼回歸守門（241.6.1）——三者現況皆應為 UTF-8。
#    這不是在驗「修法生效」（無修法），而是防止日後有人動了 base image 或 locale。
docker exec asset-external-materials-service sh -c \
  'java -XshowSettings:properties -version 2>&1 | grep -E "sun.jnu.encoding|native.encoding|file.encoding"'

# 9. 端到端：以 X-User-* header 模擬 ADMIN 租戶打 business 端點（免走 Google 登入）
docker exec asset-business-services sh -c \
  'curl -s -H "X-User-Id: 1" -H "X-User-Role: ADMIN" -H "X-User-Status: ACTIVE" \
   "http://localhost:8080/api/export-schedule/browse-gdrive?subpath=%E6%8A%95%E8%B3%87%E7%90%86%E8%B2%A1"'
```

**必須在瀏覽器實際操作確認（不可只憑 API 回應宣稱完成）：**

1. 進 `/crawler-data`，設定卡出現「同步上傳 Google Drive」開關與 Drive 資料夾欄位。
2. 開啟開關 → 點「選擇」→ 樹狀選擇器能展開 Drive 目錄、挑到「投資理財 / 資產管理」→ 儲存成功。
3. 開關開啟但 Drive 資料夾留空 → 儲存被擋並提示。
4. 觸發一輪爬蟲（或等下一個設定時間點），確認：
   - 本機 `/Users/steven/Project/SRPP/data/input/public_info_<今日>.json` **仍然照寫**（SRPP 不受影響）
   - Drive 的「投資理財 / 資產管理」出現同一份 `public_info_<今日>.json`，**檔名為正確中文路徑下的檔案、非 `?` 目錄**
   - 設定卡「上次上傳」顯示成功狀態與落點
5. 故意把 `GDRIVE_OUTPUT_REMOTE` 設成不存在的名稱並 recreate ext → 確認本機檔仍照寫、Drive 失敗只在 log 與 `gdrive_last_status`、爬蟲入庫不受影響；設定頁仍能載入與儲存（不得 500）。

## 完成報告

### 實際改動

| 層 | 檔案 | 內容 |
|---|---|---|
| DB | `db/changelog/changes/v1.75.0-crawler-gdrive-output.sql`＋`db.changelog-master.yaml` | 四個 `gdrive_*` 欄位（冪等 `ADD COLUMN IF NOT EXISTS`）＋欄位註解；seed 不啟用 |
| backend | `model/CrawlerExportSetting.java` | 四個欄位＋所有權例外說明 |
| backend | `dto/CrawlerExportPathDto.java` | `Response` 加 5 欄（含衍生顯示值 `gdriveRemote`）；`Request` 加 2 欄，`gdriveEnabled` 用包裝型別 `Boolean` 以分辨「送 false」與「沒送」 |
| backend | `service/CrawlerExportPathService.java` | Drive 驗證（純字串分段，擋開頭 `/`／`..` 段／`:`）、啟用時必填、關閉不清空、未送出＝不變更、`update()` 不碰狀態欄 |
| backend | `service/RcloneClient.java`（新） | 介面，只有 `listDirs`；`RcloneUnavailableException` 區分「remote 不可用」與「目錄為空」 |
| backend | `service/ProcessRcloneClient.java`（新） | `ProcessBuilder` 實作，20 秒逾時、只認 output config、`@PostConstruct` 複製到 `/tmp/rclone-output.conf`、per-process `RCLONE_CONFIG` 覆寫 |
| backend | `service/ExportScheduleService.java` | `browseGdrive()`＋瀏覽用寬鬆正規化（剝開頭斜線，與 `update()` 的嚴格版刻意不對稱） |
| backend | `controller/ExportScheduleController.java` | `GET /api/export-schedule/browse-gdrive`；`RcloneUnavailableException` → 503 帶可讀訊息（非 500、非空樹） |
| bff | `crawlerdata/CrawlerDataBffController.java` | `GET /export-path/browse-gdrive` passthrough，不做 `onErrorReturn` 降級 |
| bff | `schedulelist/SchedulePublicBffController.java` | 「財經新聞抓取」那筆 description 補 Drive 同步說明（避免排程列表漂移） |
| ext | `Dockerfile` | 加裝 `rclone`；註記 base image locale 不得破壞、JVM `-D` 對 `sun.jnu.encoding` 無效 |
| ext | `service/CrawlerExportPathQuery.java` | `gdriveConfig()`（雙參數 RowMapper 一次取兩欄）＋`recordGdriveResult()`（命中 0 列只 warn、狀態截斷 512） |
| ext | `service/GdriveUploader.java`（新） | 介面，只有 `upload`／`isAvailable`／`remoteName` |
| ext | `service/ProcessGdriveUploader.java`（新） | `rclone copyto`，45 秒逾時（< ticker 60 秒），只認 output config |
| ext | `service/NewsPoller.java` | 本機 `ATOMIC_MOVE` 成功後才上傳；`syncToGdrive()` 為 package-private（可測）；三層失敗全吞、跳過也寫狀態欄 |
| frontend | `api/index.js` | `saveExportPath` 改收整包 payload；新增 `browseGdriveExportDir`（`skipErrorToast`） |
| frontend | `views/CrawlerDataView.vue` | Drive 開關＋資料夾欄位＋上次上傳狀態；選擇器以 `dirPicker.mode` 共用本機／Drive 兩種來源；Drive 載入失敗顯示原因而非空樹 |
| 部署 | `docker-compose.yml`、`.env.example` | 兩個服務各加 `GDRIVE_OUTPUT_REMOTE`；ext **只掛** output config、business 兩份並存 |

### 測試

新增 3 支測試檔、29 個案例，全數通過；既有測試零回歸。

- `backend/.../service/CrawlerGdriveOutputTest.java`（13 案）：四種不合法輸入回 400、`a..b` 合法目錄名不被誤擋、啟用必填、關閉不清空、未送出＝不變更、讀取不合法既有值不擲例外、PUT 不覆寫狀態欄，＋本機既有行為迴歸。
- `backend/.../service/GdriveBrowseTest.java`（7 案）：以 `RcloneClient` 替身注入不連網——回傳形狀與本機一致、dotfiles 隱藏、排序、瀏覽剝開頭斜線、**remote 不可用時擲例外而非回空清單**、不合法子路徑不呼叫 rclone、未登入擋下。
- `external-materials-service/.../service/NewsPollerGdriveSyncTest.java`（9 案）：未啟用時零 rclone 互動、上傳擲例外不外擴且本機檔仍存在、狀態寫入自身失敗也被吞、本機寫檔失敗／config 不可用／子路徑不合法三條跳過路徑**都寫狀態欄**、`a..b` 仍會上傳。

```
backend: Tests run: 157, Failures: 0, Errors: 0
ext:     Tests run:  71, Failures: 0, Errors: 0
bff:     package -DskipTests 成功
frontend: docker build --no-cache 成功（npm run build 通過）
```

### 部署驗證（實機）

`build --no-cache business-services external-materials-service bff` ＋ `frontend`，`--force-recreate` 四個容器後 `restart bff`，全部 healthy。

| # | 驗證 | 結果 |
|---|---|---|
| 1 | Liquibase 套用 | `v1.75.0-crawler-gdrive-output` @ 2026-07-27 08:41:53 |
| 2 | DB 四欄位 | `gdrive_enabled boolean not null default false`／`gdrive_subpath varchar(512)`／`gdrive_last_run_at timestamp`／`gdrive_last_status varchar(512)` |
| 3 | ext rclone | `rclone v1.72.1-DEV`（alpine 3.23.5） |
| 4 | **憑證隔離（負向）** | ext 的 `/etc/rclone/` 只有 `rclone-output.conf`；`grep gdrive-crypt` 查無 → ext 拿不到備份 token 與 crypt 密碼 |
| 5 | 中文編碼回歸守門 | `file.encoding`／`native.encoding`／`sun.jnu.encoding` 皆 UTF-8 |
| 6 | ext 端 Drive 連通＋中文 | 容器內 `rclone lsd` 正確列出「元大銀行公有雲專案」等中文目錄名（未出現 `?`） |
| 7 | `browse-gdrive` | 回 `{"baseDir":"GDriveOutput:","subpath":"","absolutePath":"GDriveOutput:","directories":[…]}`，中文目錄名正確 |
| 8 | 設定讀取 | 含全部 `gdrive*` 欄位，`gdriveEnabled:false`（既有部署行為不變） |
| 9 | 四種不合法 PUT | 空子路徑／`..` 段／`:`／開頭 `/` → **全部 400** |
| 10 | 正常 PUT | 中文子路徑 `投資理財/資產管理` 正確存入 |
| 11 | BFF 路由 | 兩支皆回 401（路由存在且受保護，非 404） |

### 與原計畫的偏差

1. **`syncToGdrive` 從 private 改為 package-private。** 原計畫未指定可見性；改動原因是那三條「絕不」保證是本任務風險最高的部分，必須能被單元測試直接驗證，而不必跑完整抓取流程（比照本服務其他 poller 的 `updateOnce()` 測試入口慣例）。
2. **`Request.gdriveEnabled` 用 `Boolean` 而非 `boolean`。** 原計畫只寫「加 `gdriveEnabled`」；實作改用包裝型別，才能分辨「明確送 false」與「整個欄位沒送」——後者（舊版前端或只想改本機路徑的呼叫端）不應把使用者已開啟的開關靜默關掉。對應測試：`未送出啟用欄位時保留原設定`。
3. **`browse-gdrive` 的 remote 不可用回 503 而非 400。** 原計畫只要求「不得 500、不得回空樹」，未指定狀態碼。選 503（Service Unavailable）因為語意是「外部依賴不可用」而非「使用者輸入錯誤」。
4. **測試檔命名。** 依規範由實作者定：`CrawlerGdriveOutputTest`／`GdriveBrowseTest`／`NewsPollerGdriveSyncTest`。

### 阻塞事項（2026-07-28 16:09 已解除）

> 以下三點是 2026-07-27 完成報告當下的狀態，保留為歷史記錄。解除經過見本節末的兩個 `####` 小節；
> **最終可行組態與部署順序以最後一節為準。**

- **241.1 的 OAuth 授權尚未完成。** 獨立 config 檔已建立且格式正確（只含 `[GDriveOutput]` 一段、`scope = drive`、權限 `-rw-------`），但**綁到了錯誤的 Google 帳號**：該帳號根目錄為 `FaceMe`／`元大銀行公有雲專案`／`富邦證券`，遞迴至深度 3 與共用區皆查無「投資理財」；且看不到 `asset-management-backup`（在 `scope=drive` 下同帳號必定可見），容量亦不符（16 GiB／已用 8.4 GiB vs 備份帳號 17 GiB／已用 702 MiB）。需重跑 241.1 並在授權頁選正確帳號。
- **前端 UI 視覺確認尚未執行**（需 Google 登入 session，實作者不得代為登入）。程式碼層面已由 `npm run build` 通過驗證，但「驗證」段的 5 項瀏覽器手動確認待使用者執行。
- 因上述帳號問題，**端到端「檔案真的出現在 Drive 上」尚未驗證**。目前 DB 狀態為 `gdriveEnabled=false`＋`gdriveSubpath=投資理財/資產管理`（刻意保持關閉，避免上傳到綁錯的帳號）；使用者重新授權後只需在頁面上打開開關即可。

#### 2026-07-28 追加診斷：光「選對帳號」不夠，重跑授權前必須先改掉 OAuth client

使用者於 2026-07-28 13:37 左右重跑授權並把 `gdriveEnabled` 打開，仍全數失敗，`gdrive_last_status` 依時序出現兩種錯誤。實測 config 後確認**這兩個錯誤是同一個根因的兩個面向：`[GDriveOutput]` 用了自建的 Google OAuth client，而運作正常的 `[GoogleDriver]`（DB 備份）用的是 rclone 內建 client。**

| 項目 | `[GoogleDriver]`（正常） | `[GDriveOutput]`（失敗） |
|------|--------------------------|--------------------------|
| `client_id` | 無（rclone 內建 client） | `1098468643583-…`（自建，GCP 專案 1098468643583） |
| token 欄位 | `access_token` / `expires_in` / `expiry` / **`refresh_token`** / `token_type` | `access_token` / `expires_in` / `expiry` / `token_type`（**無 `refresh_token`**） |

1. **錯誤一「Drive API has not been used in project 1098468643583 …」只會發生在自建 client 上。** rclone 內建 client 不需要使用者擁有任何 GCP 專案，故 `GoogleDriver` 從未遇到這則錯誤。
2. **錯誤二「token expired and there's no refresh token」的真因是授權回應根本沒發 `refresh_token`。** token `expiry = 2026-07-28T14:37:15`，即 13:37 取得、14:37 過期後即無法續期。成因是 Google 對**同一組 (OAuth client, 帳號)再次同意**時預設不重發 refresh token（除非帶 `prompt=consent` 或先撤銷既有授權）。
3. **推論：直接 `rclone config reconnect GDriveOutput:` 會重蹈覆轍。** 它沿用現有 `client_id`，同一組 (client, 帳號) 再次同意 → 很可能再次只拿到 access token，1 小時後回到同一個錯誤。

**修正做法（改為採用 rclone 內建 client，等同 `GoogleDriver` 的做法）**，取代原 241.1／241.1.1 的自建 client 路線：

```bash
rclone config delete GDriveOutput && rclone config create GDriveOutput drive scope=drive
```

理由：(a) 完全不需碰 GCP 專案 1098468643583，免去啟用 Drive API、設定同意畫面、加測試使用者等步驟——其中「測試」發布狀態的 refresh token 7 天即失效，對每分鐘輪詢的排程是定時炸彈；(b) 換成不同的 client 即為該 (client, 帳號) 組合的**首次同意**，Google 必定發給 refresh token，直接繞開第 2 點；(c) 241.1.1 所述共用配額限流的代價在本用量（一輪一個小 JSON）下無實質影響。**取捨**：授權畫面顯示 `rclone` 而非自有專案名。

**正確帳號已確認為 `shi.chihung@gmail.com`（顯示名「史帝芬」）** — 以 `GoogleDriver` 的 token 查 Drive `about?fields=user` 取得，即持有 `asset-management-backup` 的同一帳號。授權頁務必選它。

**授權後必檢**（跳過這步等於沒修）：確認 `[GDriveOutput]` 的 token 確實含 `refresh_token`、且 `client_id` 已消失。

**容器端**：`/etc/rclone/rclone.conf` 為唯讀掛載，ext 於**啟動時**複製到 `/tmp/rclone-output.conf`（token 續期需可寫）。故 host 端重新授權後**必須 recreate ext 容器**才會讀到新 token；沿用舊容器只會繼續用 13:37 那份無 refresh token 的快照。**business-services 同樣要 recreate**——`ProcessRcloneClient` 也是啟動時複製，`/crawler-data` 的 Drive 資料夾選擇器走它。

#### 2026-07-28 16:09 解除：端到端驗證通過，並記一個部署順序的坑

**最終可行組態**（以 `rclone config delete GDriveOutput && rclone config create GDriveOutput drive scope=drive` 建立）：

| 項目 | 值 |
|------|-----|
| `client_id` | 無（rclone 內建 client） |
| `scope` | `drive` |
| `refresh_token` | 有 |
| 綁定帳號 | `shi.chihung@gmail.com` |

**已驗證的項目**（皆為實測，非推論）：ext 啟動日誌出現 `Drive 輸出用 rclone 設定已複製至可寫路徑（remote=GDriveOutput）`；ext 容器內 `rclone lsd "GDriveOutput:投資理財"` 列出 `資產管理`；ext warmup 實際上傳 `Drive 同步成功（warmup）：…/public_info_2026-07-28.json（115348 bytes）`；`gdrive_last_status` 轉為「成功：…」；business 的 `/api/export-schedule/browse-gdrive?subpath=投資理財` 回 `{"directories":[{"name":"資產管理"…}]}`；使用者於 Drive 網頁確認該檔存在。（前端頁面的 5 項視覺確認仍待使用者自行核對，不在本次實測範圍。）

**踩到的坑（本次最有價值的發現）：改 host config 與 recreate 容器不可同時進行。**

`initConfig()` 的 `configReady` 是**啟動時判定一次的旗標，失敗後不會重試**——一旦啟動當下讀不到掛入的 config，該容器整個生命週期的 Drive 同步都會被跳過（`NewsPoller` 記 `Drive 同步跳過：rclone 設定不可用`）。而單檔 bind mount 綁的是 inode，`rclone config` 是「寫暫存檔再 rename」，host 檔被換掉的瞬間，剛啟動的容器就可能解析不到。實測時序：

| 時間 | 事件 |
|------|------|
| 16:02:19 | business 啟動 |
| 16:02:23 | business 複製成功（逃過） |
| 16:02:25 | ext 啟動 |
| 16:02:26 | ext `NoSuchFileException: /etc/rclone/rclone.conf`（掛掉） |
| 16:02:29 | host `rclone.conf` 被 `rclone config` 改寫 |

結果是 business 正常、ext 靜默失效——**症狀會偽裝成「Drive 好像還在收檔案」**（各匯出頁的 xlsx 由 business 上傳、照常出現），只有爬蟲 JSON 停止更新。診斷時要分辨檔案是哪支服務送的。

**正確順序**：改 config → 驗 `refresh_token=True` → **等 host 檔穩定** → 再 recreate business 與 ext → restart bff。

**另記：`browse-gdrive` 密集呼叫會收到 429。** 實測連續列目錄後首次回 429、隔數十秒重試即正常。成因是 rclone 內建 client 的全球共用配額（`ProcessRcloneClient` 已將該 stderr 特徵轉為可讀訊息）。本任務的上傳路徑（一輪一個小 JSON）不受影響；若目錄瀏覽的 429 常態化，才需改用自建 `client_id`，且必須同時滿足：該 GCP 專案**啟用 Drive API**、OAuth 同意畫面**已發布**（停在「測試」則 refresh token 7 天失效）、授權時選對帳號。
