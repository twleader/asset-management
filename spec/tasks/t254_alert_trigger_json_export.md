# [t254] 警示觸發即時匯出 JSON 到指定目錄（本機即時 ＋ Google Drive 合併同步）

**對應 Requirements:** Requirement 54（警示條件一旦觸發，立刻把該使用者當日的觸發內容寫成 JSON 到指定目錄；本機一律照寫，Drive 為可選的附加副本）
**前置任務:** 無（t242／t243 建立的 `GdriveOutputSupport` 與 `RcloneClient.copyTo` 已在 main 上；t253 的複合條件群組亦已在 main 上。本任務只沿用，不修改它們）
**Liquibase changeset:** `v1.81.0-stock-alert-export-setting.sql`

## 背景

警示觸發目前有三個出口，**全部是給人看的**：

1. 覆寫 `stock_alert.last_triggered_*`／`stock_alert_group.last_triggered_*`（畫面顯示）
2. 寫一筆 `stock_alert_trigger`（歷史，保留 30 天）
3. `AlertNotificationDispatcher.enqueue` / `enqueueGroup`（email 通知）

**沒有任何機器可讀的即時出口。** 下游程式要知道「剛剛觸發了什麼」，只能 poll 資料庫或解析信件。本任務補上第四個出口：觸發當下把該使用者當日的觸發寫成 JSON 檔到指定目錄。

**這是第十個「輸出資料夾」頁面，但它不是排程匯出。** 既有九頁（歷年資產／交易日曆／爬蟲資訊查詢／已實現損益／油價金價／台幣兌美元匯率／GDP-TWSE／交易雷達／交易紀錄）全部是「每天某個時刻跑一次」，本任務是**事件驅動**。這個差異決定三件事，套排程頁的樣板一定會做錯：

- 設定表**沒有** `run_hour`／`run_minute`（沒有執行時刻可設），也沒有 `last_run_date` 當日 guard（不是一天跑一次）。
- 寫檔發生在 **Redis pub/sub 訂閱者執行緒**上，不是排程執行緒——能佔用的時間預算完全不同（見 254.4）。
- 同一分鐘內可能連續觸發多次，而排程頁一天只跑一次。

其餘語意一律比照既有九頁：**本機一律照寫、Drive 只是附加副本、上傳失敗 best-effort、Drive 開關限主要管理者**。不提供「只寫 Drive」的選項。

### 現況事實（實作前不必再查，但 DB 斷言請以運行中的 DB 複核）

- `stock_alert_trigger` 現有欄位：`id`／`alert_id`（nullable）／`group_id`（nullable）／`stock_code`／`market`／`triggered_at`／`price`／`monthly_ma`／`quarterly_ma`／`annual_ma`／`k_value`／`d_value`／`created_at`。**沒有 `owner_user_id`，也沒有掛 `@Filter(ownerFilter)`。**
- `alert_id` 與 `group_id` **恰好一個非空**，DB 以 CHECK 約束 `ck_sat_alert_xor_group` 保證。
- `stock_alert` 與 `stock_alert_group` 都有 `owner_user_id`（NOT NULL）且都掛 `@Filter(name = "ownerFilter", condition = "owner_user_id = :ownerId")`。
- **`stock_alert` 沒有 `stock_name` 欄位**——股名一律查 `stock` 主檔。
- 運行中 DB 的 `databasechangelog` 最新為 `v1.79.0-naive-timestamp-to-taipei`（另有 `v1.78.0-stock-alert-group-constraints`），`stock_alert_export_setting` 表**不存在**（`SELECT to_regclass('stock_alert_export_setting')` 回空）。
- `RcloneClient` 介面現有兩個方法：`List<String> listDirs(String remote, String subpath)` 與 `String copyTo(String remote, Path localFile, String subpath, String destFileName)`。**本任務不新增 rclone 操作、不實作任何刪除 Drive 檔案的程式路徑。**

## 要做什麼

### 254.1 Liquibase changeset `v1.81.0-stock-alert-export-setting.sql`

- [ ] 254.1 於 `backend/src/main/resources/db/changelog/changes/` 新增，並在 `db.changelog-master.yaml` **尾端** `include`（現有最後一筆為 `v1.79.0-naive-timestamp-to-taipei.sql`）。建表 `stock_alert_export_setting`：

  | 欄位 | 型別 | 約束 |
  |---|---|---|
  | `id` | BIGSERIAL | PRIMARY KEY |
  | `owner_user_id` | BIGINT | NOT NULL、**UNIQUE**（一人一列） |
  | `enabled` | BOOLEAN | NOT NULL DEFAULT false |
  | `output_subpath` | VARCHAR(512) | nullable |
  | `last_run_at` | TIMESTAMP | nullable |
  | `last_run_status` | VARCHAR(512) | nullable |
  | `gdrive_enabled` | BOOLEAN | NOT NULL DEFAULT false |
  | `gdrive_subpath` | VARCHAR(512) | nullable |
  | `gdrive_last_run_at` | TIMESTAMP | nullable |
  | `gdrive_last_status` | VARCHAR(512) | nullable |
  | `created_at` | TIMESTAMP | NOT NULL |
  | `updated_at` | TIMESTAMP | NOT NULL |

- [ ] 254.1.1 **changeset 必須冪等**（`CREATE TABLE IF NOT EXISTS`）。**跑過就不得改名、不得改 id、不得改內容（含註解）**——改 id 會被 Liquibase 認成新 migration 重跑（`relation already exists` → business crash loop）；同 id 改內容（**連 SQL 註解都算**，checksum 涵蓋註解）會 checksum 驗證失敗（`was ... but is now ...`）→ 同樣 crash loop。若因其他 worktree 並行推進而需要版號避讓，**先查 `databasechangelog` 再改，且 sed 類批次改號一律排除 `db/changelog/`**。
- [ ] 254.1.2 **seed 不啟用任何一列**：不寫任何 `INSERT`／`UPDATE ... SET enabled = true`。既有部署升級後行為與現況完全一致（不寫任何檔、不碰 Drive、不要求 rclone remote 存在）。
- [ ] 254.1.3 **刻意沒有 `run_hour` / `run_minute` / `last_run_date`**：事件驅動，無執行時刻可設、也不是一天跑一次。加了就是永遠沒人讀的欄位。

### 254.2 Entity 與 Repository

- [ ] 254.2 新增 `model/StockAlertExportSetting.java`，欄位對應 254.1 的表。
  - `@Table(name = "stock_alert_export_setting")`、`@Filter(name = "ownerFilter", condition = "owner_user_id = :ownerId")`（`@FilterDef` 已定義於 `model/package-info.java`，此處只掛 `@Filter`）。
  - `enabled`／`gdriveEnabled` 為 `boolean` ＋ `@Column(nullable = false)`；`gdriveSubpath`／`gdriveLastStatus`／`outputSubpath`／`lastRunStatus` 為 `String`（`length = 512`）；四個時間欄為 **`LocalDateTime`**——與既有**八支 owner-scoped** 匯出設定 entity 一致。**第九支 `CrawlerExportSetting` 用的是 `Instant`，不要照它**：那是無 `owner_user_id`、無 `@Filter` 的全域設定表，且有 business（Hibernate）與 ext（JdbcTemplate）兩個寫入端，型別選擇的前提與本表完全不同。
  - `createdAt`／`updatedAt` 以 `@Builder.Default` 初始化為 `LocalDateTime.now(ZoneId.of("Asia/Taipei"))`。
- [ ] 254.2.1 新增 `repository/StockAlertExportSettingRepository.java`：`Optional<StockAlertExportSetting> findByOwnerUserId(Long ownerUserId)` ＋ 繼承 `JpaRepository`。
- [ ] 254.2.2 於 `StockAlertTriggerRepository` 新增**owner-scoped 的當日觸發查詢**：

  ```java
  @Query("SELECT t FROM StockAlertTrigger t WHERE t.createdAt >= :start AND t.createdAt < :end AND ("
       + "  (t.alertId IS NOT NULL AND EXISTS (SELECT 1 FROM StockAlert a "
       + "     WHERE a.id = t.alertId AND a.ownerUserId = :ownerId)) OR "
       + "  (t.groupId IS NOT NULL AND EXISTS (SELECT 1 FROM StockAlertGroup g "
       + "     WHERE g.id = t.groupId AND g.ownerUserId = :ownerId))) "
       + "ORDER BY t.createdAt ASC")
  List<StockAlertTrigger> findByOwnerAndCreatedAtInDay(@Param("ownerId") Long ownerId,
          @Param("start") LocalDateTime start, @Param("end") LocalDateTime end);
  ```

  - **owner 條件必須顯式寫在 SQL 裡，不得依賴 `@Filter(ownerFilter)`**：本查詢的主要呼叫端是背景執行緒（Redis 訂閱者），**沒有 request context，`ownerFilter` 不會被 enable**，靠它等於沒有隔離——`stock_alert_trigger` 本身也沒掛 filter，漏掉 owner 條件就是把所有使用者的觸發寫進每個人的檔案（實質跨租戶外流）。
  - **不得在 `stock_alert_trigger` 加 `owner_user_id` 欄位**來省掉這個 join：違反 CLAUDE.md「相同的資料只能存一份」，且該欄會與來源分家（alert 換 owner 後舊 trigger 列不會跟著改）。

### 254.3 `StockAlertTriggerExportService`（本任務的核心）

- [ ] 254.3 新增 `service/StockAlertTriggerExportService.java`（`@Service`），注入 `StockAlertExportSettingRepository`／`StockAlertTriggerRepository`／`StockAlertRepository`／`StockAlertGroupRepository`／`StockMasterService`（股名，見 254.7）／`GdriveOutputSupport`／`ObjectMapper`／`ObjectProvider<CurrentUserContext>`／`@Value("${EXPORT_OUTPUT_DIR:/home/steven}") String baseDir`。
- [ ] 254.3.1 **`ObjectMapper` 必須由 Spring 注入，不得 `new ObjectMapper()`**：Spring Boot 的 bean 已註冊 `JavaTimeModule` 且 `WRITE_DATES_AS_TIMESTAMPS=false`，`LocalDateTime` 才會序列化成 ISO 字串；裸 `new ObjectMapper()` 會直接擲 `InvalidDefinitionException`（Java 8 date/time not supported by default）。注意同 package 的 `PriceQueryService`／`PriceStreamService` 用的是 `new ObjectMapper()`——那兩支只讀 `JsonNode`、不碰時間型別，**不要照抄**。
- [ ] 254.3.2 **拆成三支方法，不要只做一個入口**（這三條路徑的 `enabled` 閘門與去抖語意各不相同，混成一支必然做錯其中一條）：

  | 方法 | 誰呼叫 | 看 `enabled`？ | Drive 上傳套 60 秒去抖？（**本機一律即時，不受此欄影響**） | 取 per-owner 鎖？ |
  |---|---|---|---|---|
  | `private Path writeExport(Long ownerUserId, boolean debounceDriveUpload)`（真正產檔） | 下面兩支 | 否（閘門在上層） | 依參數 | **是**（254.3.7） |
  | `public void exportForTrigger(Long ownerUserId)` | `recordTrigger`／`recordGroupTrigger`（254.6） | **是**，查無設定列或 `enabled=false` → **直接 return，什麼都不做**（不查觸發、不寫檔、不碰 Drive、不寫狀態欄） | **是** → 以 `true` 呼叫 `writeExport` | 經 `writeExport` |
  | `public RunNowResult runNow(Long ownerUserId)` | `POST .../run-now`（254.5.2） | **否**——它是驗證工具，`enabled=false` 時仍須產檔（但不改變 `enabled` 的值） | **否**——使用者刻意要驗證這一次 → 以 `false` 呼叫 `writeExport` | 經 `writeExport` |

  **`writeExport` 必須帶 `debounceDriveUpload` 這個鑑別參數**（或改成只回傳落檔 `Path`、由兩支上層各自決定 Drive 那一側怎麼走）。少了它，實作者唯一能收斂的寫法是「一律走去抖」，那就直接違反 `runNow` 不套去抖與 254.3.2.1。

  `writeExport` 的流程：
  1. `settingRepo.findByOwnerUserId(ownerUserId)`（查無則以 `builder().ownerUserId(ownerUserId).build()` 取預設值，**不寫入 DB**）。
  2. 產生當日 JSON bytes（254.3.4）。
  3. 本機 tmp ＋ atomic move 落檔（254.3.5），寫 `lastRunAt`／`lastRunStatus`、`settingRepo.save(s)`。**這一步永遠是同步、即時的**，與 `debounceDriveUpload` 無關。
  4. `gdriveEnabled=true` 時交給 Drive 那一側：`debounceDriveUpload=true` → 走 254.4 的延後排程；`false` → 直接上傳。
  - **整支包 try/catch 只 `log.warn`**（見 254.6）。
- [ ] 254.3.2.1 **`runNow` 的上傳不得更新去抖用的「最近一次上傳時間」**：更新了的話，使用者按一次「立即匯出」就會讓觸發路徑的 Drive 同步靜默停 60 秒。
- [ ] 254.3.3 **「當日」一律以 `created_at`（台北牆鐘）界定，不可用 `triggered_at`**（本任務最容易寫反的一條）。`stock_alert_trigger` 兩個時間欄語意不同：
  - `triggered_at` 存**市場牆鐘**（美股存紐約時間、英股存倫敦時間，由 `StockAlertService.computeTriggeredAt()` 寫入）。
  - `created_at` 存**台北牆鐘**（`LocalDateTime.now()`，容器 `TZ=Asia/Taipei`）。

  檔名日期與內容篩選**都用 `created_at`**：`LocalDate today = LocalDate.now(ZoneId.of("Asia/Taipei"))`，查詢窗為 `[today.atStartOfDay(), today.plusDays(1).atStartOfDay())`。**用 `triggered_at` 的後果**：台北 2026-07-30 凌晨 01:00 觸發的美股警示，其 `triggered_at` 是 2026-07-29 13:00（紐約），會被歸進前一天的檔案——檔名日期與檔案實際產生日期分家，下游依日期取檔會抓不到剛剛那一筆。
- [ ] 254.3.4 **JSON 內容（全量重寫，不是 append）**。檔名 `alert_triggers_{ownerUserId}_{yyyyMMdd}.json`（日期格式 `yyyyMMdd`，台北當日）。結構：

  ```json
  {
    "ownerUserId": 1,
    "date": "2026-07-29",
    "exportedAt": "2026-07-29T14:30:12",
    "triggerCount": 2,
    "triggers": [
      {
        "triggerId": 4821,
        "source": "ALERT",
        "alertId": 137,
        "groupId": null,
        "stockCode": "2330",
        "stockName": "台積電",
        "market": "台股",
        "condition": "低於季線 10%（92.09）",
        "triggeredAt": "2026-07-29T14:30:00",
        "triggeredAtZone": "Asia/Taipei",
        "createdAt": "2026-07-29T14:30:12",
        "price": 92.0,
        "monthlyMa": 95.31,
        "quarterlyMa": 102.32,
        "annualMa": 88.4,
        "kValue": 12.34,
        "dValue": 15.1
      }
    ]
  }
  ```

  - `source` 為 `"ALERT"`（獨立條件，`alertId` 非空）或 `"GROUP"`（複合 AND 群組，`groupId` 非空）。
  - `triggeredAtZone` 是該市場的時區 id（台股 `Asia/Taipei`／美股 `America/New_York`／英股 `Europe/London`），由 `com.steven.assets.util.MarketZones.resolve(market).getId()` 取得。**必須輸出**——`triggeredAt` 是市場牆鐘，沒有這一欄下游無法正確解讀。`createdAt` 恆為台北牆鐘。
  - **數值一律輸出為 JSON number，不是字串**；指標資料不足時輸出 `null`，**不得以 `0` 或空字串充數**。
  - **`condition` 一律取自 `StockAlertService.buildLabel` / `buildGroupLabel`，不得在匯出端自行串接**：警示頁／觀察頁／email digest／補發四條既有路徑已共用同一支，第五份必然分歧（CLAUDE.md「不同頁面顯示同樣意義的值須呼叫同一支 business service API」）。
    - `source=ALERT`：`alertRepo.findById(alertId)` → `StockAlertService.buildLabel(alert, ind)`。
    - `source=GROUP`：`alertRepo.findByGroupIdOrderByDisplayOrderAsc(groupId)` → `StockAlertService.buildGroupLabel(members, ind)`（成員以 `" 且 "`（**前後各一個半形空白**）串接，該分隔符字面值不可改）。
    - **`ind` 必須用該 trigger 列自己的指標值構造，不得呼叫 `indicatorService.computeAll()` 重算**：`new TechnicalIndicatorService.FullIndicators(t.getMonthlyMa(), t.getQuarterlyMa(), t.getAnnualMa(), t.getKValue(), t.getDValue(), null, null)`（record 有 7 個參數，最後兩個 `previousK`／`previousD` 傳 `null`——`buildLabel` 不使用它們）。重算得到的是「匯出當下」的均線，會讓 MA% 條件的換算觸發價與觸發當下不符，而且一次匯出要為每筆觸發打一次指標計算。
    - alert／group 已被刪除（`findById` 為空、成員清單為空）時，`condition` 輸出 `null` 而非讓整份匯出失敗——`buildGroupLabel` 對空 list 本來就回空字串，比照放行。
  - **`triggers` 依 `createdAt` 升冪**（與 254.2.2 的查詢一致）。
  - 當日無任何觸發時輸出 `"triggerCount": 0, "triggers": []` 的**合法 JSON**（run-now 會用到，見 254.5.2）。
- [ ] 254.3.5 **本機寫檔：tmp ＋ atomic move**，比照既有八頁的 `writeAtomically`：

  ```java
  Path dir = resolveDir(subpath);            // baseDir resolve 子路徑並驗證不跳脫基底
  Files.createDirectories(dir);
  Path file = dir.resolve(filename);
  Path tmp  = dir.resolve(filename + "." + UUID.randomUUID() + ".tmp");   // 唯一後綴，理由見下
  Files.write(tmp, data);
  try { Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE); }
  catch (AtomicMoveNotSupportedException e) { Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING); }
  ```

  理由：下游程式可能正在讀同一個檔案，就地覆寫會讓對方讀到半截 JSON。**Drive 端不做 tmp ＋ rename**（Drive API 未完成的上傳不會產生可見檔案，且 rename 需要 delete 權限的程式路徑，與「不實作任何刪除路徑」的自我約束衝突）。
- [ ] 254.3.5.1 **tmp 檔名必須帶 `UUID.randomUUID()` 唯一後綴，不可用固定的 `filename + ".tmp"`。** 既有八頁中有四支（`ExchangeRateExportScheduleService`／`IndexExportScheduleService`／`CommodityExportScheduleService`／`TradingCalendarExportService`）用固定 `.tmp`，**那是因為它們只有排程一條寫檔路徑**；唯一一支有兩條非互斥路徑的 `TradingRadarExportScheduleService`（該檔 392–401 行）已經改用 UUID 後綴，其 javadoc 明寫理由：「同一 owner 同一日可能有兩條路徑併發寫檔；固定 tmp 名會讓後者 `Files.move` 撞 `NoSuchFileException`」。**本任務有三條寫檔路徑**（觸發、`POST /api/stock-alerts/check`、run-now），情境與它相同而非與那四支相同。這與 254.3.7 的鎖是互補的兩層防護，兩者都要做。
- [ ] 254.3.6 **本機路徑驗證**：`resolveDir` 比照既有八頁——`Path base = Path.of(baseDir).toAbsolutePath().normalize(); Path target = base.resolve(subpath).normalize();` 若 `!target.startsWith(base)` 擲 `IllegalArgumentException("輸出子路徑不可跳脫基底目錄：" + subpath)`。`outputSubpath` 為空白時預設 `"input"`（與交易紀錄頁一致）。
- [ ] 254.3.7 **同一 owner 的寫檔必須序列化，鎖取在 `writeExport` 內**（254.3.2 的三支都經過它）。**三條路徑會由不同執行緒寫進同一個檔名**：
  1. Redis 訂閱者執行緒 → `PriceStreamService.onPriceUpdate` → `checkAlertsFor` → 觸發
  2. HTTP 執行緒 → `POST /api/stock-alerts/check` → `checkAlerts` → 觸發
  3. HTTP 執行緒 → `POST /api/stock-alerts/export-setting/run-now`

  用 per-owner 鎖（例 `ConcurrentHashMap<Long, ReentrantLock>` 的 `computeIfAbsent`）。**不得用單一全域鎖**——一個使用者的慢速磁碟會拖累其他人。

### 254.4 Drive 上傳：非同步 ＋ 合併去抖（不遵守會讓整條即時價管線停擺）

- [ ] 254.4 **Drive 上傳絕不可在觸發執行緒同步做。** `PriceStreamService.onPriceUpdate` 對 `stockAlertService.checkAlertsFor(code, market)` 是**同步呼叫**（該處註解寫「避免阻塞訂閱者執行緒」但實作並非如此——屬既有狀況，**本任務不修**，但它正說明風險），其上游是 Redis 訂閱者執行緒，同時還負責 SSE 廣播（`sink.tryEmitNext`）與交易雷達評估。rclone 上傳逾時上限為 **45 秒**，一次卡住就是整條價格更新管線停擺 45 秒。
- [ ] 254.4.1 Drive 上傳丟給**單執行緒排程 executor**（`Executors.newSingleThreadScheduledExecutor`，daemon thread、具名 thread factory 便於 log 辨識）。**必須是 `Scheduled` 版本**——254.4.2 的去抖要靠 `schedule(task, delay, unit)`。**不得用 `@Async`**（本專案 backend 未啟用 `@EnableAsync`，加上去會影響全域）。
- [ ] 254.4.2 **合併去抖：一律以「延後至間隔到期的單一任務」表達，最小間隔 60 秒**（常數寫死並加註解說明，但**須可注入以便測試**；不做成 DB 設定欄位——多一個沒人會調的欄位）。per-owner 狀態為「是否已有排定中的任務 ＋ 最近一次實際上傳完成時間」。行為：
  - 該 owner **沒有**排定中的任務 → `executor.schedule(uploadTask, max(0, lastUploadAt + 60s − now), MILLISECONDS)`。
  - 該 owner **已有**排定中的任務 → **什麼都不做**（那個任務到期時會讀最新的檔案，內容自然是最新的）。
  - 任務執行時先清掉「排定中」旗標，再上傳；上傳完成後更新 `lastUploadAt`。
  - **絕對不可寫成「距上次上傳 < 60 秒就只標記 pending，由已排入的那個任務完成後補跑」**——那是有資料遺失的寫法：假設 13:20:00 排入、13:20:02 完成，13:29:00 發生當日最後一次觸發時距上次上傳 < 60 秒，於是只標記 pending；但那個任務早在 13:20:02 就結束了，**沒有任何執行中的任務會回頭看 pending**。當天不再有觸發 → Drive 上那份檔案永久停在 13:20 的內容、缺最後一筆；隔天檔名滾到新日期，舊檔再也不會被修正。合併之所以安全，靠的是「晚一點上傳的那一份必然包含先前所有內容」——而在這個寫法下那個「晚一點」永遠不會來。
  - **不得每次觸發都排一個任務**——那只是把同步的擁塞搬到 executor 佇列上。
  - 理由：Drive 端每次上傳都有固定成本（既有實測記載：token 幾乎每次呼叫都已過期需 refresh、未設 `root_folder_id` 需從 Drive 根目錄逐層查找子路徑；加上 `RCLONE_LIMITS` 四個參數後單次仍需約 2.4 秒），開盤時段連續觸發會讓上傳次數遠多於有意義的內容變化。
- [ ] 254.4.3 **上傳一律走 `GdriveOutputSupport.syncQuietly(ownerUserId, gdriveSubpath, localFile)`**，回傳 `SyncResult(status, path)`。**不得自行呼叫 `RcloneClient`、不得自行判權限、不得自行組狀態字串**——該元件是全 backend 唯一的 Drive 出口，內含每次上傳前的 owner 複驗（`isDriveAllowedFor`）、子路徑驗證、逾時／速率限制／失敗三種措辭的區分與 512 字元截斷。上傳後把 `r.status()` 寫進 `gdriveLastStatus`、`LocalDateTime.now(台北)` 寫進 `gdriveLastRunAt` 並 `save`。
- [ ] 254.4.4 **被去抖合併時，`gdriveLastStatus` 與 `gdriveLastRunAt` 兩欄一律不碰（值保持不變）。** 那兩欄的語意是「上次**上傳**的結果」，寫進去會覆蓋掉昨晚真正上傳成功的落點與 bytes，並把 `gdriveLastRunAt` 寫成一個根本沒發生過上傳的時刻——`GdriveOutputSupport` 為了自檢警告已經明文禁止過同一件事（該檔 166–169 行）。**尤其絕不可寫成 `失敗：…`**，那會讓使用者去排查一個設計上正確的行為。使用者要知道「本機即時、Drive 最多延遲約一分鐘」，走 UI 常駐文案（254.9.3），**不入庫**。真正該寫狀態欄的「跳過」只有 `syncQuietly` 自己回的那四種（owner 非主要管理者、子路徑不合法、未設定 Drive 目標資料夾、本輪未產生本機檔案），它已經以 `跳過：…` 措辭處理好了。
- [ ] 254.4.5 **背景執行緒內存取 entity 要小心 lazy／session**：executor 執行時已脫離原本的交易與 session。上傳任務**只傳原始值**（`ownerUserId`、`gdriveSubpath` 字串、`Path`），需要更新狀態欄時在任務內重新 `findByOwnerUserId` 取一次再 save，**不要把 entity 物件跨執行緒傳遞**。
- [ ] 254.4.6 **本機檔寫成功後才上傳，順序不可顛倒**；本機寫檔失敗時**完全不上傳**（絕不上傳前一次的舊檔）。
- [ ] 254.4.7 **必須把新表加進 `GdriveSelfCheck` 的「全庫是否有任一列啟用 Drive」查詢，否則啟動自檢會給假綠燈。** `GdriveSelfCheck.ANY_ENABLED_SQL`（該檔 65–76 行）目前是**八張表**的 `UNION ALL`（`export_schedule_setting`／`trading_calendar_export_schedule`／`index_export_schedule`／`exchange_rate_export_schedule`／`trading_radar_export_setting`／`commodity_export_schedule`／`realized_gain_export_schedule`／`asset_transaction_export_schedule`），它是啟動時 L3 探測（`rclone lsd`）的前置閘門，其 javadoc 明寫「八張表一次查完，**少查一張就會給假綠燈**」。
  - 追加 `UNION ALL SELECT 1 FROM stock_alert_export_setting WHERE gdrive_enabled`，並把該 javadoc 的「八張表」改為「九張表」。
  - **不改的後果**：使用者只在本頁啟用 Drive、其餘九頁維持 false 時，rclone token 失效或 remote 被改名，容器重啟時 `anyGdriveEnabled()` 回 false → **整個 L3 啟動探測被跳過、不噴任何 WARN**，故障要等到下一次觸發才會體現在 `gdrive_last_status`。
  - `GdriveSelfCheck` 走 `JdbcTemplate` 原生 SQL（刻意繞過 Hibernate `@Filter`，因為問的是「全庫有沒有人啟用」而非「我的設定」），新表照同一寫法即可。
  - **測試要自己新增一條，不能只說「更新既有斷言」——既有的根本沒有斷言可更新。** `backend/src/test/java/com/steven/assets/service/GdriveSelfCheckTest.java` 現有四處 `jdbc.query` 的 stub 全部用 `anyString()`（該檔 146／161／173／195 行），唯一提到「八張表」的是 145 行的**註解**；漏加新表不會讓任何測試變紅。新增：以 `ArgumentCaptor<String>` 捕獲 `jdbc.query` 的 SQL，斷言它同時含九張表名（含 `stock_alert_export_setting`）且 `UNION ALL` 出現 8 次；並把 145 行註解的「八張表」改為「九張表」。
  - `external-materials-service` 那一支同名的 `GdriveSelfCheck` 只查 `crawler_export_setting`（全域設定表），**不受本任務影響、不要改**。

### 254.5 API 端點（business）

- [ ] 254.5 於 `StockAlertController` 新增三個端點（沿用該 controller 既有的 `@RequestMapping("/api/stock-alerts")`）：
  - `GET /api/stock-alerts/export-setting` → 回當前使用者設定；查無時回**預設值但不寫入 DB**（比照既有八頁的 `getForCurrentUser`）。
  - `PUT /api/stock-alerts/export-setting` → upsert。
  - `POST /api/stock-alerts/export-setting/run-now` → 立即產檔（見 254.5.2）。
- [ ] 254.5.1 **`PUT` 的 Drive 兩欄一律走 `GdriveOutputSupport.resolveUpdate(ownerId, req.gdriveEnabled(), req.gdriveSubpath(), s.isGdriveEnabled(), s.getGdriveSubpath())`**，它一次做完：`null`＝未送出＝不變更的解析、正規化、驗證（開頭 `/` → 400、含 `..` 路徑段 → 400、含 `:` → 400、啟用時必填 → 400）、以及**「明確要求啟用時」的主要管理者權限檢查**（不通過擲 `AdminRequiredException` → `GlobalExceptionHandler` 已映射為 **403**）。
  - **不得自行查 `AppUserRepository`、不得用 `role == ADMIN`／`CurrentUserContext.isAdmin()`**：`role` 是 DB 欄位、可以有多列 ADMIN，第二位若被升為 ADMIN，其資料照樣會進到 rclone remote 擁有者的 Drive。判準只能是 `isConfiguredAdmin(email)`（比對 `ADMIN_EMAIL`，全庫唯一一人），而它已封裝在 `resolveUpdate` 內。
  - 回應的 `gdriveSelfCheckWarning` 取自 `DriveSettings.selfCheckWarning()`，**不入庫**（尤其不得寫進 `gdriveLastStatus`）。
  - **本機 `outputSubpath` 與 `enabled` 維持所有使用者皆可設定**（不限 ADMIN）——只有 Drive 那一項會把資料送出本機，此不對稱是刻意的。
  - `PUT` 刻意**不碰** `lastRunAt`／`lastRunStatus`／`gdriveLastRunAt`／`gdriveLastStatus`（那是執行結果，不是使用者設定）。
- [ ] 254.5.2 **`run-now`**：以當前使用者身分把**當日已發生的觸發**重新產檔並（`gdriveEnabled=true` 時）上傳 Drive，回報本機落點、bytes 與 Drive 落點／狀態。
  - **當日尚無任何觸發時，須寫出 `triggers: []` 的合法 JSON 並明白回報「當日尚無觸發」**，**不得回 404、不得靜默不產檔**——使用者按這顆按鈕的目的是驗證落點正確，空檔案同樣達成該目的。
  - run-now 的 Drive 上傳**不套 60 秒去抖**（使用者是刻意要驗證這一次），但仍走 executor 或同步皆可——這條路徑在 HTTP 執行緒上，不影響價格管線；若同步做，須沿用 `syncQuietly` 的 45 秒逾時語意並如實回報。
  - `enabled=false` 時 run-now **仍應產檔**（它是驗證工具），但不改變 `enabled`。
- [ ] 254.5.3 **讀取路徑一律不驗證 Drive 子路徑**：DB 值可能被繞過 API 直改，若讀取也擲例外，設定頁會 500 而使用者失去唯一的修正入口。存檔時才驗（同既有八頁）。
- [ ] 254.5.4 DTO 放 `dto/StockAlertExportDto.java`，三者皆為巢狀 `public record`（`SettingRequest`／`SettingResponse`／`RunNowResponse`）。**本專案 DTO 一律為不可變 `record`，只有 entity 才用 Lombok `@Data`**；DTO 不放 JPA 註解、不持有 entity 參照。`SettingRequest` 的 `enabled`／`gdriveEnabled` 必須是**包裝型別 `Boolean`**（`null`＝欄位沒送＝不變更，與「明確送 false」語意不同）。`SettingResponse` 須含 `baseDir`（前端顯示本機落點用）與 `gdriveRemote`（`gdrive.remoteName()`，衍生顯示值、不入庫）。

### 254.6 接上觸發路徑

- [ ] 254.6 於 `StockAlertService` 注入 `StockAlertTriggerExportService`，在**兩處**觸發路徑寫入 `stock_alert_trigger` **之後**呼叫 `exportForTrigger(ownerUserId)`：
  - `recordTrigger(StockAlert alert, ...)` → `alert.getOwnerUserId()`
  - `recordGroupTrigger(StockAlertGroup group, ...)` → `group.getOwnerUserId()`
  - **只接其中一條是錯的**：使用者從畫面上看不出「複合條件觸發了但檔案沒動」。
  - **順序**：必須在 `triggerRepo.save(...)` 之後——檔案內容是從 `stock_alert_trigger` 重查產生的，先寫檔會漏掉當次這一筆。
- [ ] 254.6.1 **呼叫處必須自成一個 try/catch 只 `log.warn`**，比照該處既有的兩段（寫歷史一段、寄信一段）：匯出失敗**絕不**讓 `stock_alert_trigger` 的 INSERT 回滾、**絕不**中斷 email enqueue、**絕不**回拋而中止 `evaluate`／`evaluateGroup` 的整輪檢查。反向亦然：email 失敗不影響匯出。
- [ ] 254.6.2 **注意 `recordGroupTrigger` 的成員已 detach**（`evaluateGroup` 為避免 `matches()` 的回寫副作用被 flush 而刻意 detach 成員），匯出端**不要**重用那些物件去讀關聯；需要成員時自己用 `alertRepo.findByGroupIdOrderByDisplayOrderAsc(groupId)` 重查。
- [ ] 254.6.3 **循環依賴**：`StockAlertService` 注入新 service、新 service 又用到 `StockAlertService` 的 `buildLabel`／`buildGroupLabel`——那兩支是 **`public static`**，**直接以類別名呼叫、不要注入 `StockAlertService`**，否則會形成建構子循環依賴（Spring Boot 2.6+ 預設禁止循環參照 → `BeanCurrentlyInCreationException` → business-services 整個起不來）。

### 254.7 收斂股名解析（避免第二份分歧）

- [ ] 254.7 JSON 的 `stockName` 需要「代號 → 股名」解析，而 `AlertNotificationDispatcher` 已有一份 **private** 的 `resolveStockName(code, market)`（該檔 485–492 行）：`0000` ＋ `台股` → `"台股大盤"`；否則 `stockMasterRepo.findByCodeAndMarket(code, market).map(Stock::getName).orElse(code)`（**查無時回代號本身，不是 null、不捏造名稱**）。
- [ ] 254.7.1 **`StockMasterService` 已經有一支 `public String resolveName(String code, String market)`（該檔第 70 行），那是另一件事——本任務不得改動它，匯出端也不得呼叫它。** 兩者同名不同語意，實測差異有四處：

  | | 既有 `StockMasterService.resolveName` | `AlertNotificationDispatcher.resolveStockName` |
  |---|---|---|
  | 查無主檔 | **打外部行情 API**（`fetchTwStockName`／`fetchUsStockName`／`fetchUkStockName`）→ 查到就 `upsert` 回主檔並**背景排 10 年歷史回補** | 只查本地主檔 |
  | 查無最終回傳 | `""`（空字串） | `code`（代號本身） |
  | `0000` ＋ 美股／英股 | 回 `""` | 無此分支 |
  | code 大小寫 | `toUpperCase()` | 原樣 |

  **直接沿用既有那支的後果**：每一筆不在主檔的觸發，都會在 **Redis 訂閱者執行緒上同步打一次 FinMind／Yahoo ＋ 一次 DB upsert ＋ 排一次 10 年歷史回補**——正是 254.4 整節在防的事；而且查無時 JSON 的 `stockName` 會是空字串而不是代號。**覆寫它同樣不行**：`StockAlertController` 第 119 行的 `GET /api/stock-alerts/lookup-name` 靠它打外部抓取並寫回主檔，改了會讓警示對話框的股名自動帶入靜默失效。
- [ ] 254.7.2 **改為新增一支不同名的本地限定方法**：`StockMasterService.resolveNameLocalOnly(String code, String market)`，行為一字不差搬自 `AlertNotificationDispatcher.resolveStockName`（`0000`＋台股 → `"台股大盤"`；否則查本地主檔；**查無回代號本身**；不打外部、不寫主檔、不排回補），`AlertNotificationDispatcher` 改為委派它。**這一步是純重構**，該 dispatcher 既有測試必須全綠。匯出端呼叫的是這一支。
- [ ] 254.7.3 **不要在匯出端照抄一份**：股名是同義欄位（email 與 JSON 顯示同一件事），兩份必然分歧。

### 254.8 BFF（一頁一 BFF）

- [ ] 254.8 新增 `bff/src/main/java/com/steven/assets/bff/stockalert/StockAlertBffController.java`，`@RequestMapping("/api/bff/stock-alert")`：
  - `GET  /export-setting` → business `GET /api/stock-alerts/export-setting`
  - `PUT  /export-setting` → business `PUT /api/stock-alerts/export-setting`
  - `POST /export-setting/run-now` → business `POST /api/stock-alerts/export-setting/run-now`
  - `GET  /export-setting/browse?subpath=` → business **`GET /api/export-schedule/browse`**（本機目錄列舉，既有唯一那支）
  - `GET  /export-setting/browse-gdrive?subpath=` → business **`GET /api/export-schedule/browse-gdrive`**（Drive 目錄列舉，全庫唯一那支）
  - **不得在 business 端新開第二份目錄列舉實作**（CLAUDE.md「同義欄位、同一 business service API」）。
- [ ] 254.8.1 **既有 `StockAlertBffRoutes` 的萬用 route 會與新 controller 重疊，這是可行的但必須驗證。** 既有 route 為 `.path("/api/bff/stock-alert/**")` → rewrite `"/api/bff/stock-alert(?<seg>/?.*)"` → `"/api/stock-alerts${seg}"`，它會把 `/export-setting/browse-gdrive` 錯誤地轉成 `/api/stock-alerts/export-setting/browse-gdrive`（business 端沒有這個端點 → 404）。WebFlux 的 `RequestMappingHandlerMapping`（order 0）先於 Gateway 的 `RoutePredicateHandlerMapping`（order 1），故 controller 會先接走。**同一模式的既有先例是 `TradingRadarBffController`（`@RequestMapping("/api/bff/trading-radar")`）與 `TradingRadarBffRoutes`（`.path("/api/bff/trading-radar", "/api/bff/trading-radar/**")`）並存**，照它辦即可；但驗證步驟**必須實際打這兩個 browse 端點**確認沒有落到 route（見「驗證」第 6 項）。**不要為此刪掉或改窄既有 route**——`/api/bff/stock-alert/**` 上還有既有的 CRUD／reorder／check／lookup-name／lookup-code／recipients 全部靠它 passthrough。
- [ ] 254.8.2 沿用該 BFF 專案既有的 `WebClient` 注入與錯誤轉譯慣例（`BusinessErrorAdvice` 已統一處理 business 回傳的 4xx／5xx），**不要自行吞掉 403**——Drive 啟用被拒必須讓前端看到。
- [ ] 254.8.3 **254.8.1 的「controller 有接走、沒落到 route」必須有測試守門，而且只能用整合測試驗，不能用容器內 curl。** 兩個原因都已實測：(a) `asset-bff` 的 runtime 映像是 `eclipse-temurin:21-jre-alpine` 且 `bff/Dockerfile` **沒有任何 `apk add`**——容器內沒有 curl（compose 的 healthcheck 用的是 busybox `wget`）；(b) 就算換成 `wget` 也探不到——`bff` 的 `SecurityConfig` 是 `.anyExchange().authenticated()` ＋ JSON 401 entry point，Security filter 在 `RequestMappingHandlerMapping` 與 Gateway `RoutePredicateHandlerMapping` **之前**執行，故「controller 接走」與「落到 route → business 404」**兩種情形都回 401**，狀態碼無法區分。
  - **驗法（不需要任何新依賴）**：`bff/pom.xml` 目前測試依賴只有 `spring-boot-starter-test`（無 WireMock／MockWebServer），**不要為此引入新依賴**。改為直接驗 handler mapping 的解析結果——那本來就是 order 0 vs order 1 這件事的直接證據：於 `bff/src/test/java/com/steven/assets/bff/stockalert/` 新增 `@SpringBootTest` 測試，注入 `RequestMappingHandlerMapping`，對 `/api/bff/stock-alert/export-setting/browse` 與 `/export-setting/browse-gdrive` 各建一個 `MockServerWebExchange`，斷言 `getHandler(exchange)` 回傳的是 `HandlerMethod` 且其 method 指向 `StockAlertBffController` 的對應方法。`RequestMappingHandlerMapping` 匹配得到，就必然贏過 order 1 的 Gateway mapping。
  - 順帶斷言既有路徑**沒有被搶走**：`/api/bff/stock-alert`（列表，應由萬用 route passthrough）在同一個 `RequestMappingHandlerMapping` 上**匹配不到** controller（回 null）——避免新 controller 的 `@RequestMapping` 寫太寬而把既有 CRUD／reorder／check／lookup-name／lookup-code／recipients 一起吃掉。
  - 這是唯一能鑑別 route 順序的驗證方式，**不可省略**——一旦落到 route，使用者看到的症狀只是資料夾樹展不開，不會有任何錯誤指向 BFF 路由順序。
  - 注意 `bff` 現有測試（`TenantWebFilterTest`／`LiveAssetsOverlayTest`）都是純單元測試，本測試會是該專案第一支 `@SpringBootTest`；若 context 啟動需要 business 連線相關的必要設定，以 `@TestPropertySource` 補上假的 `business-services.url` 即可（本測試不發出任何實際請求）。

### 254.9 前端（`StockAlertView.vue`，`/stocks?tab=alert`）

- [ ] 254.9 在既有「警示條件」卡片之下新增「觸發即時匯出」設定卡，含：
  - 啟用開關（`enabled`）
  - 輸出資料夾（本機）：`el-input` ＋「選擇」按鈕開既有的檔案總管式 `el-tree` 懶載入選擇器；顯示本機落點 `{{ baseDir }}/{{ outputSubpath }}/alert_triggers_{使用者ID}_{yyyyMMdd}.json`
  - Drive 開關 ＋ Drive 目標資料夾（**僅 `authStore.isConfiguredAdmin` 為真時顯示**，沿用既有 getter，**不要用 `isAdmin`**——那是 `role === 'ADMIN'`，語意不同）。**`StockAlertView.vue` 目前尚未 import authStore**（該檔 import 區只有 icons／element-plus／sortablejs／dayjs／`bffApi` 與三個元件），需自行加 `import { useAuthStore } from '@/stores/authStore'` ＋ `const auth = useAuthStore()`，變數名沿用既有九頁的 `auth`
  - 「上次匯出」（`lastRunAt` ＋ `lastRunStatus`）與「上次上傳」（`gdriveLastRunAt` ＋ `gdriveLastStatus`）
  - 「立即匯出」按鈕（呼叫 run-now，回報落點）
- [ ] 254.9.1 **雙模式資料夾選擇器沿用既有寫法**（`TransactionView.vue` 已有完整範例）：`mode: 'local' | 'gdrive'`，兩者回傳形狀相同、共用同一棵 `el-tree`；顯示落點時**本機用 `/` 分隔、Drive 用 `remote:` 前綴**（混用會顯示成 `GDriveOutput:/投資理財` 這種錯的字串）。
- [ ] 254.9.2 儲存後若回應帶 `gdriveSelfCheckWarning`，以既有共用 util `showGdriveSelfCheckWarning(s.gdriveSelfCheckWarning)`（`@/utils/gdriveSelfCheck`）提示，**不另造一套**。
- [ ] 254.9.3 **UI 必須明示「本機即時、Drive 最多延遲約一分鐘」**（254.4.2 的去抖）。不寫的話使用者會把正常的延遲當成故障來回報。
- [ ] 254.9.4 **UI 必須明示「已匯出的檔案不會被系統刪除」**：`StockAlertService.cleanupOldTriggers`（每日 04:00 台北）只刪 `stock_alert_trigger` 的 DB 列，**不刪任何已寫出的 JSON**（既有九頁的匯出檔亦然）。DB 清空後舊檔仍在，屬預期行為。
- [ ] 254.9.5 API 呼叫加在 `frontend/src/api/index.js` 既有的 `stockAlert` 區段下，路徑一律走 `/api/bff/stock-alert/export-setting*`（一頁一 BFF，**不得直接打 business**）。

### 254.10 測試（與實作同屬本任務交付）

於 `backend/src/test/java/com/steven/assets/service/` 新增測試（檔名由實作者定），至少涵蓋：

- [ ] 254.10.1 **當日邊界（最容易寫反的一條）**：`created_at` 為台北當日、`triggered_at` 為紐約前一日的美股觸發，**必須**出現在台北當日的檔案裡；反之 `created_at` 屬前一日者不得出現。
- [ ] 254.10.2 **owner 隔離**：A 的觸發不得出現在 B 的檔案。**兩條 join 路徑都要涵蓋**（`alert_id` 走 `stock_alert.owner_user_id`、`group_id` 走 `stock_alert_group.owner_user_id`）。
- [ ] 254.10.3 **群組觸發的 `condition`** 為 `buildGroupLabel` 的合併結果（成員以 `" 且 "` 串接，前後各一個半形空白），而非單一成員的 label。
- [ ] 254.10.4 **未啟用**（查無設定列，或 `enabled=false`）時**完全不產檔**、不呼叫 Drive、不寫狀態欄。
- [ ] 254.10.5 **匯出失敗不影響觸發**：讓匯出擲例外，斷言 `stock_alert_trigger` 仍寫入、email enqueue 仍被呼叫、`evaluate` 不回拋。
- [ ] 254.10.6 **Drive 合併去抖**（間隔常數須可注入才測得動）：
  - 60 秒內連續 N 次觸發只呼叫一次 `RcloneClient.copyTo`。
  - **尾端補跑**：兩次觸發相隔 5 秒、之後不再有任何觸發 → 間隔到期後**必須**發生第二次 `copyTo`（這條是 254.4.2 那個資料遺失寫法的唯一探針，缺了它壞實作也會全綠）。
  - 被合併的那幾次，`gdriveLastStatus` 與 `gdriveLastRunAt` **值保持不變**（不是「不寫成失敗」而已——兩欄都不得被碰）。
- [ ] 254.10.6.1 **run-now 的路徑語意**：`enabled=false` 時 `runNow` 仍產檔（且不把 `enabled` 改成 true）；`runNow` 的上傳**不更新**去抖的 `lastUploadAt`（按一次立即匯出後，緊接著的觸發仍應正常上傳，不被靜默壓住 60 秒）。
- [ ] 254.10.7 **JSON 形狀**：數值為 number 而非字串、指標不足時為 `null`（非 `0`）、`triggeredAtZone` 依市場正確（台股 `Asia/Taipei`／美股 `America/New_York`／英股 `Europe/London`）、當日無觸發時為 `triggerCount: 0` ＋ `triggers: []` 的合法 JSON。
- [ ] 254.10.8 **`PUT` 權限**：非主要管理者要求 `gdriveEnabled=true` → **403**（`AdminRequiredException`）；本機 `outputSubpath` 與 `enabled` 則所有使用者皆可改（不得被 403 擋）。
- [ ] 254.10.9 **股名解析重構為純重構**：`AlertNotificationDispatcher` 既有測試全綠；新的 `resolveNameLocalOnly` 對 `0000`＋台股回「台股大盤」、查無主檔**回代號本身**（不是空字串）、**不打外部 API**（以替身斷言 `historicalDataService` 的 fetch 方法零呼叫）。
- [ ] 254.10.9.1 **既有 `StockMasterService.resolveName` 未被改動的回歸**：主檔查無的代號，`GET /api/stock-alerts/lookup-name` **仍會走外部抓取並 upsert 回主檔**（254.7.1 的兩支不可混用）。
- [ ] 254.10.10 rclone 一律以 `RcloneClient` 介面替身注入，**不實際連網**；本機寫檔用 `@TempDir` 或注入的 `baseDir`，**不得寫進真實 `/home/steven`**。
- [ ] 254.10.11 **既有行為回歸**：警示觸發的 `last_triggered_*` 覆寫、email enqueue、24h cooldown（以市場牆鐘比較）皆未被本任務改動。
- [ ] 254.10.12 Mockito 於本專案需 `-DextraArgLine=-Dnet.bytebuddy.experimental=true`。**不要用 `-DargLine`**——那會覆蓋掉 pom 內的 `-Duser.timezone=Asia/Taipei` 設定，造成大量測試 error，而錯誤訊息會偽裝成 byte-buddy 問題。

### 254.11 不做什麼（明確排除）

- [ ] 254.11.1 **不新增任何 `@Scheduled`**（匯出掛在既有觸發路徑內），故「公開資訊 → 排程列表」（`SchedulePublicBffController.JOBS`）**不需新增項目**。
- [ ] 254.11.2 **不修 `PriceStreamService` 註解與實作的不一致**（「避免阻塞訂閱者執行緒」但實為同步呼叫）——那是既有狀況，超出本任務範圍；本任務的作法（Drive 非同步）已使該風險不因本任務而擴大。
- [ ] 254.11.3 **不改 `RcloneClient` 介面、不新增 rclone 操作、不實作任何刪除 Drive 檔案的程式路徑。**
- [ ] 254.11.4 **不做 retry queue**：下一次觸發即為天然重試（檔案為當日全量重寫，Drive 覆寫同名檔本身冪等）。
- [ ] 254.11.5 **不刪已匯出的檔案**（見 254.9.4）。

## 驗證

```bash
# 1. 後端測試（Mockito 用 extraArgLine，不要用 argLine）
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test \
  -DextraArgLine=-Dnet.bytebuddy.experimental=true

# 2. BFF 測試
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f bff/pom.xml test

# 3. 從 worktree 跑 compose 前先把主 repo 的 .env 複製進來
#    （env_file 相對 compose 檔解析，--env-file 救不了）
cp /Users/steven/Project/asset-management/.env .

# 4. JVM service 一律 --no-cache（cached build 會產出不含本次變更的 stale jar）
docker compose -p asset-management build --no-cache business-services bff frontend
docker compose -p asset-management up -d --no-deps --force-recreate business-services bff frontend

# 5. recreate business 後必須 restart bff（business 換 IP，BFF 握舊 IP 會回 500，
#    Docker DNS TTL 600s 內不自癒；business log 乾淨、錯只在 bff log 的 Connection refused）
docker compose -p asset-management restart bff

# 6. 表與 changeset 到位
docker exec asset-postgres psql -U assets -d assets -c '\d stock_alert_export_setting'
docker exec asset-postgres psql -U assets -d assets -tc \
  "SELECT id FROM databasechangelog ORDER BY orderexecuted DESC LIMIT 2"

# 7. jar 真的含本次變更（不要假設 commit = 已部署）
docker exec asset-business-services sh -c 'unzip -l app.jar | grep -i StockAlertTriggerExport'

# 8. BFF controller 有接走這兩條、沒有落到萬用 route（254.8.1 的關鍵驗證）
#    **不要用容器內 curl 打端點驗這件事**，兩個原因都已實測：
#      (a) asset-bff 的 runtime 映像是 eclipse-temurin:21-jre-alpine 且 Dockerfile 無任何 apk add，
#          容器內沒有 curl（compose 的 healthcheck 用的是 busybox wget）；
#      (b) 就算換成 wget 也探不到——bff SecurityConfig 是 .anyExchange().authenticated()，
#          未帶 session 時「controller 接走」與「落到 route→business 404」兩種情形都回 401，無法區分。
#    正確做法見 254.8.3：@SpringBootTest 注入 RequestMappingHandlerMapping，
#    斷言 /api/bff/stock-alert/export-setting/browse 解析到 StockAlertBffController 的方法
#    （匹配得到就必然贏過 order 1 的 Gateway mapping），不需要 WireMock 等新依賴。
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f bff/pom.xml test -Dtest='StockAlertBff*'

# 9. 端到端（免 Google 登入）：在 business 容器內帶 X-User-* header 模擬租戶
#    先設定並啟用，再手動觸發一次警示檢查，確認檔案落地
docker exec asset-business-services sh -c 'curl -s -X PUT \
  -H "Content-Type: application/json" -H "X-User-Id: 1" -H "X-User-Role: ADMIN" -H "X-User-Status: ACTIVE" \
  -d "{\"enabled\":true,\"outputSubpath\":\"input\"}" \
  http://localhost:8080/api/stock-alerts/export-setting'

docker exec asset-business-services sh -c 'curl -s -X POST \
  -H "X-User-Id: 1" -H "X-User-Role: ADMIN" -H "X-User-Status: ACTIVE" \
  http://localhost:8080/api/stock-alerts/export-setting/run-now'

# 10. 檔案內容為合法 JSON、欄位齊全（當日無觸發時應為 triggers: []）
docker exec asset-business-services sh -c \
  'cat /home/steven/input/alert_triggers_1_$(date +%Y%m%d).json' | head -40

# 11. owner 隔離：以另一個 user id 打 run-now，兩份檔案內容不得互相包含
docker exec asset-business-services sh -c 'curl -s -X POST \
  -H "X-User-Id: 2" -H "X-User-Role: USER" -H "X-User-Status: ACTIVE" \
  http://localhost:8080/api/stock-alerts/export-setting/run-now'

# 12. 非主要管理者啟用 Drive 應回 403
docker exec asset-business-services sh -c 'curl -s -o /dev/null -w "%{http_code}\n" -X PUT \
  -H "Content-Type: application/json" -H "X-User-Id: 2" -H "X-User-Role: USER" -H "X-User-Status: ACTIVE" \
  -d "{\"enabled\":true,\"gdriveEnabled\":true,\"gdriveSubpath\":\"投資理財/資產管理\"}" \
  http://localhost:8080/api/stock-alerts/export-setting'
```

**必須確認的回歸：**

1. **警示觸發的既有三個出口未被影響**：`POST /api/stock-alerts/check` 後，`stock_alert.last_triggered_*` 仍覆寫、`stock_alert_trigger` 仍新增一筆、email 仍 enqueue（查 business log）。
2. **複合條件群組（Task 253）仍正常**：群組觸發只寫一筆 `stock_alert_trigger`（`alert_id` 為 null、`group_id` 非空），合併 label 仍以 `" 且 "` 串接。
3. **既有九個匯出頁未被影響**：任選兩頁按「立即匯出到目錄」，本機落檔與「上次執行」狀態正常（本任務新增共用元件的呼叫端，未改元件本身）。
4. **`AlertNotificationDispatcher` 的股名顯示未變**（254.7 的遷移為純重構）：警示 email 內的股票名稱仍正確，`0000` 仍顯示「台股大盤」。
5. **價格管線未被拖慢**：開盤時段觀察 business log，`price-update` 處理與 SSE 廣播未出現長時間停頓；Drive 上傳的 log 出現在具名的 executor thread 上而非 Redis 訂閱者執行緒。

## 完成報告

**完成日期：** 2026-07-29

### 與原計畫的偏差

1. **Liquibase 版號由 `v1.80.0` 避讓為 `v1.81.0`。** 實作到部署階段才發現另一個 worktree 的
   `v1.80.0-asset-transaction-export-schedules-multi` 已先一步套進運行中的 DB（`databasechangelog` 有紀錄，
   `dateexecuted 2026-07-29 13:16:29`）。本 changeset 當時**尚未執行過**，故改名與改 id 安全（若已執行過就只能另開新
   changeset）。檔案、`db.changelog-master.yaml`、本任務檔三處一併同步。
2. **`resolveDir` 對 controller 不可見。** 它是 package-private，controller 在 `controller` package。
   新增 `public String resolvedDirDisplay(String subpath)` 供設定頁顯示，**不合法時回 null 而非擲例外**
   （讀取路徑擲例外會讓設定頁 500，使用者失去唯一的修正入口）。
3. **`AlertNotificationDispatcher` 的 `StockRepository` 欄位一併移除**（改注入 `StockMasterService`）：
   該欄位在 254.7 委派後成為唯一用途已消失的死欄位。既有測試只用 static method，未受影響。
4. **BFF route 順序的驗證方式**（254.8.3）改為直接查 `RequestMappingHandlerMapping` 的解析結果。
   注入時必須 `@Qualifier("requestMappingHandlerMapping")`——actuator 另註冊了 `controllerEndpointHandlerMapping`，
   依型別注入會撞「expected single matching bean but found 2」。

### 實際改了哪些檔

**backend（新增 5、修改 6）**
- 新增 `db/changelog/changes/v1.81.0-stock-alert-export-setting.sql`、`model/StockAlertExportSetting.java`、
  `repository/StockAlertExportSettingRepository.java`、`dto/StockAlertExportDto.java`、
  `service/StockAlertTriggerExportService.java`
- 新增測試 `service/StockAlertTriggerExportTest.java`（15 條）
- 修改 `db.changelog-master.yaml`（include）、`repository/StockAlertTriggerRepository.java`
  （`findByOwnerAndCreatedAtInDay`）、`service/StockAlertService.java`（注入 ＋ 兩處觸發路徑接上）、
  `controller/StockAlertController.java`（三個端點）、`service/StockMasterService.java`
  （新增 `resolveNameLocalOnly`）、`service/AlertNotificationDispatcher.java`（委派）、
  `service/GdriveSelfCheck.java`（第九張表）、`service/GdriveSelfCheckTest.java`（九表斷言）

**bff（新增 2）**
- `bff/stockalert/StockAlertBffController.java`、測試 `StockAlertBffRouteOrderTest.java`（4 條）

**frontend（修改 2）**
- `api/index.js`（`stockAlert` 區段 5 支）、`views/StockAlertView.vue`（設定卡 ＋ 雙模式資料夾選擇器）

### 測試輸出

```
backend:  Tests run: 326, Failures: 0, Errors: 0, Skipped: 0   BUILD SUCCESS
bff:      Tests run: 10（含新增 4），Failures: 0, Errors: 0
```

### 表結構

```
docker exec asset-postgres psql -U assets -d assets -c '\d stock_alert_export_setting'
→ 12 欄全部到位，uq_stock_alert_export_owner UNIQUE (owner_user_id)
→ 刻意無 run_hour / run_minute / last_run_date（事件驅動）
```

### run-now 產出的 JSON（實測，含當日邊界的關鍵證據）

```json
{
  "ownerUserId" : 1,
  "date" : "2026-07-29",
  "exportedAt" : "2026-07-29T13:21:58.949509804",
  "triggerCount" : 1,
  "triggers" : [ {
    "triggerId" : 1180, "source" : "ALERT", "alertId" : 65, "groupId" : null,
    "stockCode" : "AMZN", "stockName" : "Amazon.com, Inc. Common Stock", "market" : "美股",
    "condition" : "低於年線",
    "triggeredAt" : "2026-07-28T12:00:02.360861",
    "triggeredAtZone" : "America/New_York",
    "createdAt" : "2026-07-29T00:00:02.371523",
    "price" : 231.2450, "monthlyMa" : 243.4400, "quarterlyMa" : 251.5800,
    "annualMa" : 233.6100, "kValue" : 13.5200, "dValue" : 25.7200
  } ]
}
```

**這筆真實資料正好是 254.3.3 那條規則的實證**：`triggeredAt` 是 **2026-07-28**（紐約牆鐘）、
`createdAt` 是 **2026-07-29 00:00**（台北牆鐘），檔名為 `alert_triggers_1_20260729.json`。
若當日判準用 `triggered_at`，這筆會被歸進 07-28 的檔案，下游依日期取檔就抓不到。

### owner 隔離與權限（實測回應）

```
POST run-now (X-User-Id: 2) → {"path":".../alert_triggers_2_20260729.json","triggerCount":0,
                                "message":"當日尚無觸發，已寫出空的觸發清單（可用於驗證落點）"}
  → user 2 的檔案不含 user 1 的 AMZN；user 1 的檔案不含 user 2 的任何內容
PUT gdriveEnabled=true (X-User-Id: 2, role=USER) → HTTP 403
PUT gdriveEnabled=true (X-User-Id: 1, ADMIN_EMAIL) → 200，gdriveSubpath 存入
PUT gdriveSubpath="/絕對路徑"                      → HTTP 400
```

### Google Drive 實際上傳（未 mock，真的打 rclone）

```
{"path":"/home/steven/input/alert_triggers_1_20260729.json","size":670,"triggerCount":1,
 "gdrivePath":"GDriveOutput:投資理財/資產管理/alert_triggers_1_20260729.json",
 "gdriveStatus":"成功：GDriveOutput:投資理財/資產管理/alert_triggers_1_20260729.json（670 bytes）"}
```
Drive 端與本機同為 670 bytes。

### BFF 端點

```
GET /api/bff/stock-alert/export-setting                    → HTTP 401（有掛上、被 security 攔）
GET /api/bff/stock-alert/export-setting/browse-gdrive      → HTTP 401
```
**401 兩者相同，無法區分「controller 接走」與「落到萬用 route」**——這正是 254.8.3 改用
`RequestMappingHandlerMapping` 斷言的原因，該測試（4 條）已綠，並含「既有端點仍留給萬用 route」的反向斷言。

### 回歸

```
GET  /api/stock-alerts                          → 200
GET  /api/export-schedule/settings              → 200
GET  /api/export-schedule/browse?subpath=       → 200
GET  /api/stock-alerts/lookup-name?code=2330    → {"stockName":"台積電"}
     （證明既有 StockMasterService.resolveName 未被 254.7 的重構改動）
POST /api/stock-alerts/check                    → 204，log「檢查 57 個到價警示、10 個複合條件群組」
docker logs asset-bff --since 5m | grep -c "Connection refused|500" → 0
輸出目錄無殘留 .tmp 檔
```

### 部署

`business-services` / `bff` / `frontend` 皆 `--no-cache` 重建並 `--force-recreate`，之後 `restart bff`。
**第一次部署時映像被另一個 worktree 覆蓋**（運行中 jar 含別人的 `v1.80.0-asset-transaction-export-schedules-multi`、
不含本任務的類別），重建後以 `unzip -l /app/app.jar` 確認含 6 個本任務類別與 `v1.81.0` changeset 才繼續驗證。
全機只有一套 `asset-management-*:latest`，**其他 worktree 之後若再 build 仍會覆蓋**——merge 進 main 後
應從 main 的 worktree 重建一次。
