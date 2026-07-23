# [t231] 交易雷達排程自動匯出 Excel 到指定伺服器目錄（多時間點，比照公開資訊爬蟲）

**對應 Requirements:** Requirement 48 追加（今日交易雷達結果快照落地 Redis 與 Excel 匯出——本追加新增「使用者設定多個每日執行時間點與一個輸出資料夾，系統到點自動把當日快照寫成 Excel 到該伺服器資料夾」，與既有的手動瀏覽器下載並存）
**前置任務:** t230（交易雷達結果快照落地 Redis 與 Excel 區間匯出——本任務直接重用它建立的 `TradingRadarSnapshotStore`（per-owner Redis 快照與 `range()` 讀取）與 `TradingRadarExportService`（三分頁 Excel 產檔）；沒有那兩支就沒有可寫出的內容與可共用的產檔邏輯。t230 已實作並部署完成。）
**Liquibase changeset:** `v1.70.0-trading-radar-export-schedule.sql`

## 背景

### 使用者的問題（本任務推翻了 t230 對「指定目錄」的解讀）

原始需求是「匯出 excel，可以指定時間及目錄」。t230 把「指定目錄」實作成**瀏覽器存檔對話框**（File System Access API `showSaveFilePicker`），把「指定時間」實作成**匯出資料的日期區間**。

使用者看到成品後指出：**要的是「像爬蟲那樣」的匯出功能**，並提供了「公開資訊新聞爬蟲（NewsPoller）」設定頁的畫面作為範本：

- **「爬蟲執行時間設定」卡**：`08:20`／`11:30`／`20:30` 三個時間點，每個可獨立「停用／啟用」toggle 與「移除」，另有「＋新增時間點」與「儲存設定」。
- **「爬蟲輸出檔案設定」卡**：「輸出資料夾」欄位＋「選擇」鈕；說明「以主機家目錄 `/home/steven`（對映主機 `/Users/steven`）為根，只能選其下的子資料夾。目前落點 `/home/steven/Project/SRPP/data/input/public_info_2026-07-21.json`，檔名固定為 `public_info_<日期>.json`；同日多輪覆寫、跨日產生新檔。」

所以正確語意是：

| | t230 做的（保留） | 本任務要做的 |
|---|---|---|
| 觸發 | 使用者手動按按鈕 | **系統在設定的多個時間點自動執行** |
| 「時間」 | 匯出資料的日期區間 | **每日執行時間點，可設定多個** |
| 「目錄」 | 瀏覽器另存對話框選 | **伺服器上的資料夾**（`/home/steven` 下，volume 對映主機） |
| 產出 | 下載到使用者電腦 | 系統把 Excel 寫進該資料夾 |

**使用者已明確決定兩者並存**：t230 的瀏覽器下載按鈕**保留不動**，本任務**新增**排程寫檔。且**每次排程寫出的內容＝當日全部快照、一天一檔覆寫**（比照爬蟲 `public_info_<日期>.json`）。

### 既有基礎設施（照抄，不要自創）

- **多時間點排程的既有範本**是 `crawler_schedule`。其現況以 `db/schema.sql`（本專案 DB 現況的基準線；**不要引用 `db/changelog/**` 判斷現況**，那裡有永不執行的 changeset）為準：

  ```sql
  CREATE TABLE public.crawler_schedule (
      id bigint NOT NULL,
      crawler_key character varying(64) NOT NULL,
      run_hour integer NOT NULL,
      run_minute integer NOT NULL,
      enabled boolean DEFAULT true NOT NULL,
      updated_at timestamp without time zone,
      CONSTRAINT ck_crawler_schedule_hour CHECK (((run_hour >= 0) AND (run_hour <= 23))),
      CONSTRAINT ck_crawler_schedule_minute CHECK (((run_minute >= 0) AND (run_minute <= 59)))
  );
  ```

  即**一列一時間點**；API 為 `GET /api/crawler-schedule` 與 `PUT`（整批覆寫，見 `backend/src/main/java/com/steven/assets/controller/CrawlerScheduleController.java`）。實測目前列為 `news-poller` 的 `08:20`／`11:30`／`20:30`，與使用者畫面一致。但它是**全域設定、無 `owner_user_id`**；交易雷達是 per-user（快照與持股都 owner-scoped），故本任務的時間點表**必須帶 `owner_user_id` 與 `@Filter(ownerFilter)`**。
- **輸出資料夾的既有範本**是 `crawler_export_setting`（實測現況：`id / crawler_key`(UNIQUE)`/ output_subpath varchar(512) NOT NULL / updated_at`；此表晚於 `db/schema.sql` dump 基準線建立，故不在該檔內，以實際 DB 為準）。對應 entity `backend/src/main/java/com/steven/assets/model/CrawlerExportSetting.java` 的 Javadoc 明載**為何與時間點分表**：「併入會使路徑隨時間點列數重複儲存同一事實（CLAUDE.md 資料庫完整正規化），且刪一個時間點會連帶弄丟路徑」。**本任務沿用此分表決定。**
- **排程寫檔到目錄的既有範本**是 `backend/src/main/java/com/steven/assets/service/IndexExportScheduleService.java`（Requirement 45）：`@Scheduled` 每分鐘 poll ＋ `lastRunDate` 當日 guard ＋ `ApplicationReadyEvent` 自癒 ＋ `AtomicBoolean` 防重入 ＋ tmp/ATOMIC_MOVE ＋ 路徑跳脫防護。**整套照抄，只把「單一時間點」改成「多時間點各自 guard」。**
- **business-services 可寫主機家目錄**（實測 `docker-compose.yml`）：
  ```yaml
  business-services:
    environment:
      EXPORT_OUTPUT_DIR: /home/steven          # docker-compose.yml:75
    volumes:
      - ${EXPORT_OUTPUT_DIR_HOST:-/Users/steven}:/home/steven   # docker-compose.yml:81
  ```
  故排程放 business-services（它同時握有 t230 的 Redis 快照與 POI 產檔）。**不需要改 docker-compose。**

## 要做什麼

> 約束總則：**不得取代或改動 t230 既有的手動瀏覽器下載**（端點 `GET /api/trading-radar/export?from&to` 與前端「匯出 Excel」鈕維持原樣）。排程寫檔與手動匯出**必須共用同一支產檔邏輯**，不得各自實作而使內容漂移。

- [ ] **231.1 建立 Liquibase changeset `v1.70.0-trading-radar-export-schedule.sql`**

  檔案置於 `backend/src/main/resources/db/changelog/changes/v1.70.0-trading-radar-export-schedule.sql`，並在 `backend/src/main/resources/db/changelog/db.changelog-master.yaml` 末尾以既有格式追加：

  ```yaml
    - include:
        file: db/changelog/changes/v1.70.0-trading-radar-export-schedule.sql
        relativeToChangelogFile: false
  ```

  **版號說明：DB 已套用的最新 changeset 為 `v1.69.0-stock-underlying-currency`（實查 `databasechangelog`）；`v1.67.0`／`v1.68.0` 曾被其他任務預留但從未建檔，為避免混淆一律不使用。本任務用 `v1.70.0`。**

  **全部語句必須冪等**（`CREATE TABLE IF NOT EXISTS`、`CREATE INDEX IF NOT EXISTS`）——本專案曾因版號避讓改 changeset id，Liquibase 視為新 migration 重跑，非冪等語句會失敗並造成服務 crash loop。

  ```sql
  --liquibase formatted sql

  --changeset steven:v1.70.0-trading-radar-export-schedule
  --comment Requirement 48 追加（Task 231）：交易雷達排程自動匯出。時間點與輸出資料夾刻意分兩張表——
  --comment 併表會使同一路徑隨時間點列數重複儲存（違反正規化），且刪一個時間點會連帶弄丟路徑
  --comment （沿用 crawler_schedule / crawler_export_setting 的既有分表理由）。
  --comment last_run_date 放在時間點列上：當日 guard 必須 per 時間點，否則同日設多個時間點只會跑第一個。

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

  COMMENT ON COLUMN trading_radar_export_time.last_run_date IS
    '當日 guard，per 時間點。成功或失敗都設為當日，避免命中分鐘後每 poll 重試整天。';
  COMMENT ON COLUMN trading_radar_export_setting.output_subpath IS
    '輸出目錄相對子路徑（相對容器基底 EXPORT_OUTPUT_DIR=/home/steven，volume 對映主機家目錄）。'
    '只存相對子路徑：絕對路徑不可攜且繞過基底防護，一律於 service 層擋下。';
  ```

- [ ] **231.2 JPA entity 與 repository**

  兩個 entity 皆為 Lombok `@Data` entity（本專案 entity 用 `@Data` 是既有慣例，**不是 record**），皆掛 `@Filter(name = "ownerFilter", condition = "owner_user_id = :ownerId")`（FilterDef 已統一定義於 `backend/src/main/java/com/steven/assets/model/package-info.java`，不需另外宣告）。

  - `backend/src/main/java/com/steven/assets/model/TradingRadarExportTime.java` — `@Table(name = "trading_radar_export_time")`，欄位 `id / ownerUserId(Long) / runHour(Integer) / runMinute(Integer) / enabled(Boolean) / lastRunDate(LocalDate) / updatedAt(LocalDateTime)`。
  - `backend/src/main/java/com/steven/assets/model/TradingRadarExportSetting.java` — `@Table(name = "trading_radar_export_setting")`，欄位 `id / ownerUserId(Long) / outputSubpath(String) / lastRunAt(LocalDateTime) / lastRunStatus(String) / updatedAt(LocalDateTime)`；常數 `DEFAULT_SUBPATH = "input"`。
  - 對應 `TradingRadarExportTimeRepository`（需 `List<TradingRadarExportTime> findAllByOwnerUserIdOrderByRunHourAscRunMinuteAsc(Long ownerUserId)` 與 `deleteByOwnerUserId(Long)`）與 `TradingRadarExportSettingRepository`（需 `Optional<TradingRadarExportSetting> findByOwnerUserId(Long)`）。

- [ ] **231.3 產檔邏輯改為可顯式指定 owner（讓手動與排程共用同一支）**

  `backend/src/main/java/com/steven/assets/service/TradingRadarExportService.java` 目前的 `export(String from, String to)` 由 `CurrentUserContext` 取 owner。**背景排程沒有 request context，取不到 request-scoped 的 `CurrentUserContext`**，故須拆成兩層：

  - 新增 `public byte[] exportForOwner(long ownerId, long fromEpoch, long toEpoch) throws IOException`——真正的產檔邏輯（呼叫 `store.range(ownerId, fromEpoch, toEpoch)` 後產三分頁 Excel）。
  - 既有 `export(String from, String to)` 改為：解析 from/to → 由 `CurrentUserContext` 取 owner → 委派 `exportForOwner(...)`。**對外行為與回傳完全不變**（t230 的手動下載端點不得受影響）。

  **必須保留既有的 `ownerId == null` 分支，不得把 null 交給 `exportForOwner(long)`**——現況為：

  ```java
  Long ownerId = currentUserContext.getEffectiveUserId();
  TradingRadarSnapshotStore.SnapshotRange range = (ownerId == null)
          ? new TradingRadarSnapshotStore.SnapshotRange(List.of(), 0, 0)
          : store.range(ownerId, fromEpoch, toEpoch);
  ```

  新簽章收 `long`（基本型別），直接委派會在 `Long → long` 拆箱時 NPE → 500，違反 Requirement 48「零快照仍回含表頭合法檔、不得回 5xx」。故 `export()` 內須先判 null：為 null 時走「空 SnapshotRange 產檔」路徑，非 null 才呼叫 `exportForOwner(ownerId, ...)`。

  排程路徑一律呼叫 `exportForOwner(...)`，**嚴禁在背景路徑觸碰 `CurrentUserContext`**（request-scoped bean 在無 request 時存取會拋 `Scope 'request' is not active`）。

- [ ] **231.4 新增 `TradingRadarExportScheduleService`（設定 CRUD ＋ 背景排程）**

  新增 `backend/src/main/java/com/steven/assets/service/TradingRadarExportScheduleService.java`。注入：兩個 repository、`TradingRadarExportService`、`ObjectProvider<CurrentUserContext>`（HTTP 路徑用）、`@Value("${EXPORT_OUTPUT_DIR:/home/steven}") String baseDir`。

  **設定 CRUD（HTTP 路徑，owner 由 `CurrentUserContext.getEffectiveUserId()` 取）：**
  - `listTimes()`：回目前使用者的時間點清單（`@Filter` 會自動 owner-scoped，仍建議顯式用 `findAllByOwnerUserId`）。
  - `replaceTimes(List<...>)`：**整批覆寫**（比照 `CrawlerScheduleController.replace`）。時分驗證 `0..23` / `0..59`，非法值丟 `IllegalArgumentException`（→400）。覆寫時**保留既有相同時分列的 `last_run_date`**，避免使用者存檔就讓當日 guard 消失而重跑。**送入清單須先偵測重複時分並丟 `IllegalArgumentException`（→400）**——表上有 `UNIQUE (owner_user_id, run_hour, run_minute)`，前端「＋新增時間點」連按兩次不改值即可送出兩筆相同時分，若不先擋會拋 `DataIntegrityViolationException` → **500**（`GlobalExceptionHandler` 只把 `IllegalArgumentException` 轉 400）。
  - `getSetting()` / `saveSetting(subpath)`：資料夾相對子路徑；儲存前先 `resolveDir()` 驗證（見下），非法即 400。

  **背景排程（照抄 `IndexExportScheduleService` 的骨架，只把單一時間點換成多時間點各自 guard）：**

  ```java
  private final AtomicBoolean ticking = new AtomicBoolean(false);

  /** 每分鐘檢查各使用者的各個時間點，命中且當日未跑者即產檔寫入其設定目錄。 */
  @Scheduled(cron = "0 * * * * *", zone = "Asia/Taipei")
  public void tick() {
      if (!ticking.compareAndSet(false, true)) return;
      try { runDueExports(); }
      catch (RuntimeException e) { log.warn("交易雷達排程匯出 tick 例外：{}", e.getMessage(), e); }
      finally { ticking.set(false); }
  }

  /** 服務重啟自癒：補跑「今日已到點但尚未執行」者（與 tick 同一判斷，冪等）。 */
  @EventListener(ApplicationReadyEvent.class)
  public void selfHealOnStartup() {
      try { runDueExports(); }
      catch (RuntimeException e) { log.warn("交易雷達排程匯出開機自癒失敗：{}", e.getMessage(), e); }
  }

  private void runDueExports() {
      LocalDate today = LocalDate.now(TW_ZONE);
      LocalTime now = LocalTime.now(TW_ZONE);
      for (TradingRadarExportTime t : timeRepo.findAll()) {   // 背景無 request context → ownerFilter 不啟用，讀全部 owner
          if (!Boolean.TRUE.equals(t.getEnabled())) continue;
          if (today.equals(t.getLastRunDate())) continue;      // 當日 guard：per 時間點
          if (!now.isBefore(LocalTime.of(t.getRunHour(), t.getRunMinute()))) {
              runScheduled(t, today);
          }
      }
  }

  private void runScheduled(TradingRadarExportTime t, LocalDate today) {
      try {
          Path file = writeDailyExport(t.getOwnerUserId(), today);
          recordStatus(t.getOwnerUserId(), "成功：" + file);
      } catch (Exception e) {
          recordStatus(t.getOwnerUserId(), "失敗：" + e.getMessage());
          log.warn("交易雷達排程匯出失敗 owner={} {}:{}：{}",
                   t.getOwnerUserId(), t.getRunHour(), t.getRunMinute(), e.getMessage(), e);
      } finally {
          // 成功或失敗都設 guard，避免命中分鐘後每 poll 重試整天
          t.setLastRunDate(today);
          t.setUpdatedAt(LocalDateTime.now(TW_ZONE));
          timeRepo.save(t);
      }
  }
  ```

  **約束（逐項都要做到）：**
  - **`now >= 設定時分` 而非「分鐘精確相等」**：Spring 預設排程池只有 1 條執行緒且與其他 `@Scheduled` 共用，被長工作卡住跨分鐘時，精確相等會造成**整日靜默漏跑**；用 `now >=` ＋當日 guard 才會在後續 tick 自動補跑。
  - **當日 guard 放在時間點列（`last_run_date`）**，不是 owner 層。放 owner 層會使同日設 `08:20`／`11:30`／`20:30` 只跑第一個。
  - **單一使用者／單一時間點失敗只記 `last_run_status` ＋ log，不得中斷其他列**（`runScheduled` 內自行 try/catch）。
  - 背景路徑**不得**取 `CurrentUserContext`；owner 一律取自 `t.getOwnerUserId()`。

  **`recordStatus(long ownerId, String status)`（狀態寫入，必須自行 upsert）：**
  `last_run_at`／`last_run_status` 存在 `trading_radar_export_setting`（一使用者一列）。使用者可能**只設了時間點、還沒設資料夾**，該列因此不存在（此即下文「無設定則 `input`」的情境）。故 `recordStatus` 須：`settingRepo.findByOwnerUserId(ownerId)` 取不到時**建立一列**（`outputSubpath` 帶 `TradingRadarExportSetting.DEFAULT_SUBPATH`），再寫入 `lastRunAt = LocalDateTime.now(TW_ZONE)`、`lastRunStatus = status` 後 `save`。**不可假設該列必定存在**——`IndexExportScheduleService` 是單表、設定列必存在，那段不能直接照抄。

  **寫檔（`writeDailyExport(long ownerId, LocalDate today)`）：**
  - 區間＝該 owner **當日 `00:00` 至當下**：`fromEpoch = today.atStartOfDay(TW_ZONE).toInstant().toEpochMilli()`、`toEpoch = ZonedDateTime.now(TW_ZONE).toInstant().toEpochMilli()`。
  - **先查當日快照筆數；為 0 時不得寫檔**：快照只在使用者開啟／刷新雷達頁的 HTTP 路徑產生（背景不產生），故使用者當天若在該時間點前沒開過頁面即查無快照。此時**直接回傳「未產檔」語意**（例如回 `null` Path），由 `runScheduled` 把 `last_run_status` 記為「當日尚無快照，未產檔」並**仍設當日 guard**；**不得**寫出一個只有表頭的空檔（會每天在使用者目錄留下無用檔案，且蓋掉同名前一版）。判斷方式：`store.range(ownerId, fromEpoch, toEpoch).snapshots().isEmpty()`。
  - `byte[] data = exportService.exportForOwner(ownerId, fromEpoch, toEpoch)`（與手動匯出同一支）。
  - 檔名 `"交易雷達_" + ownerId + "_" + today.format(DateTimeFormatter.ofPattern("yyyyMMdd")) + ".xlsx"`——**同日多個時間點覆寫同一檔、跨日新檔**；含 ownerId 的理由同 Requirement 45：多使用者可能指向同一共用目錄，不帶 ID 會互相覆蓋。
  - 子路徑取該 owner 的 `trading_radar_export_setting.output_subpath`（無設定則 `"input"`）。
  - 路徑防護與原子寫入**照抄 `IndexExportScheduleService`**：

  ```java
  /** 基底 resolve 子路徑並驗證仍在基底內（拒 `..`／絕對路徑跳脫）。 */
  private Path resolveDir(String subpath) {
      Path base = Path.of(baseDir).toAbsolutePath().normalize();
      Path target = base.resolve(subpath).normalize();
      if (!target.startsWith(base)) {
          throw new IllegalArgumentException("輸出子路徑不可跳脫基底目錄：" + subpath);
      }
      return target;
  }

  /** 先寫 .tmp 再 atomic move：避免覆寫既有檔時中途失敗留下半截殘檔。 */
  private Path writeAtomically(String subpath, String filename, byte[] data) throws IOException {
      Path dir = resolveDir(subpath);
      Files.createDirectories(dir);
      Path file = dir.resolve(filename);
      // tmp 檔名帶唯一後綴：selfHealOnStartup 與 runNow 都不走 ticking 旗標，
      // 同一 owner 同一日可能有兩條路徑併發寫檔；固定 tmp 名會讓後者 Files.move 撞 NoSuchFileException。
      Path tmp = dir.resolve(filename + "." + java.util.UUID.randomUUID() + ".tmp");
      Files.write(tmp, data);
      try {
          Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE);
      } catch (AtomicMoveNotSupportedException e) {
          Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
      }
      return file;
  }
  ```

  **`runNow()`（立即匯出到目錄）**：以目前使用者 owner 走**同一支** `writeDailyExport(...)`，回傳實際落點路徑與檔案大小；**不得更動任何時間點的 `last_run_date`**（不影響當日排程）。

- [ ] **231.5 新增 controller 端點**

  在 `backend/src/main/java/com/steven/assets/controller/TradingRadarController.java`（`@RequestMapping("/api/trading-radar")`）新增，注入 `TradingRadarExportScheduleService`：

  - `GET  /api/trading-radar/export-schedule/times` → 時間點清單。
  - `PUT  /api/trading-radar/export-schedule/times` → 整批覆寫（body 為時間點陣列，含 `runHour`／`runMinute`／`enabled`）。
  - `GET  /api/trading-radar/export-schedule/setting` → 回 `outputSubpath`、`lastRunAt`、`lastRunStatus`，以及**目前落點完整路徑預覽**（供畫面顯示「目前落點 /home/steven/...」那行）。
  - `PUT  /api/trading-radar/export-schedule/setting` → 儲存 `outputSubpath`。
  - `POST /api/trading-radar/export-schedule/run-now` → 立即匯出，回落點路徑與檔案大小。

  **DTO 一律用不可變 `record`**（本專案 DTO 規範；entity 才用 `@Data`）。時分非法或路徑跳脫丟 `IllegalArgumentException`，由既有 `GlobalExceptionHandler`（已確認有 `@ExceptionHandler(IllegalArgumentException.class)` → `HttpStatus.BAD_REQUEST`）轉 **400**。

- [ ] **231.6 BFF：新增資料夾列舉 passthrough（用 controller，不可加 gateway route）**

  `bff/src/main/java/com/steven/assets/bff/tradingradar/TradingRadarBffRoutes.java` 是 Spring Cloud Gateway rewrite passthrough（`/api/bff/trading-radar`、`/api/bff/trading-radar/**` → `/api/trading-radar/**`），故 `export-schedule/**` 會**自動涵蓋、不需改**。

  **資料夾列舉要沿用 Requirement 34 既有的 business 端點 `GET /api/export-schedule/browse?subpath=`（不得新增第二支目錄列舉端點）**，但該路徑不在 `/api/trading-radar/**` 底下。

  **必須用 BFF controller，不可在 `TradingRadarBffRoutes` 加 route。** 理由：`/api/bff/trading-radar/export/browse` **完全落在既有 wildcard `/api/bff/trading-radar/**` 之內**；Gateway 路由同 order（builder 產生的預設皆 0）時依宣告順序先匹配者勝出，新 route 若被 append 在 wildcard 之後，請求會被 rewrite 成 business 不存在的 `/api/trading-radar/export/browse` → **404，資料夾選擇器靜默失效**。

  作法：新增 `bff/src/main/java/com/steven/assets/bff/tradingradar/TradingRadarBffController.java`（`@RestController` + `@RequestMapping("/api/bff/trading-radar")`），加 `@GetMapping("/export/browse")` 以既有的 `businessServicesClient`（WebClient）轉呼 `/api/export-schedule/browse?subpath={subpath}`。WebFlux 的 `RequestMappingHandlerMapping`（order 0）先於 Gateway 的 `RoutePredicateHandlerMapping`（order 1），controller 自動勝出，不需調整任何 route order。

  此寫法與專案其餘 7 處同義功能一致（`RealizedGainBffController`、`ExchangeRateBffController`、`CommodityPriceBffController`、`GdpTwseBffController`、`AssetHistoryBffController`、`CrawlerDataBffController`、`TradingCalendarBffController` 皆為 BFF controller + WebClient）。可照抄 `bff/src/main/java/com/steven/assets/bff/realizedgain/RealizedGainBffController.java` 的 browse 轉呼寫法。

- [ ] **231.7 登錄排程列表頁（漏掉即漂移）**

  在 `bff/src/main/java/com/steven/assets/bff/schedulelist/SchedulePublicBffController.java` 的靜態 `JOBS` 清單（business-services 區塊）新增一筆，格式比照既有列：

  ```java
  new ScheduledJobDto(BUSINESS, "交易雷達匯出", "每日匯出排程檢查",
          "每分鐘檢查各使用者設定的多個交易雷達匯出時間點，命中執行時間即把當日 Redis 快照產出 Excel 到指定目錄（Requirement 48）",
          "每分鐘", "0 * * * * *", TPE),
  ```

  **順帶更正既有的筆數漂移（不要照著錯的數字加一）**：實測 `grep -c "new ScheduledJobDto(BUSINESS," → 15`、`grep -c "new ScheduledJobDto(EXTERNAL," → 28`，但 `SchedulePublicBffController.java:53` 區塊註解仍寫 `// ===== business-services（12）=====`、`:13` 類別 Javadoc 仍寫「business-services（12 個）與 external-materials-service（24 個）」。本任務新增一筆後正確值為 **business 16、external 28**，兩處一併更正。

- [ ] **231.8 前端：兩張設定卡（比照爬蟲設定頁）＋保留既有下載鈕**

  在 `frontend/src/views/TradingRadarView.vue` 個股表格下方新增兩張卡（**t230 既有的「匯出 Excel」按鈕與對話框維持不動**）：

  1. **「匯出執行時間設定」**：列出時間點，每列為 `el-time-picker`（`HH:mm`）＋「停用／啟用」`el-switch`＋「移除」鈕；下方「＋新增時間點」與「儲存設定」（呼叫 PUT times 整批覆寫）。說明文字比照爬蟲卡：「設定後即時生效（免重啟），下一分鐘起依新時間執行。清空全部時間點＝不再自動匯出。」
  2. **「匯出輸出資料夾設定」**：「輸出資料夾」輸入框＋「選擇」鈕（開 `el-tree` 懶載入資料夾選擇器，呼叫 `/bff/trading-radar/export/browse`）＋「儲存設定」；下方顯示「以主機家目錄 `/home/steven`（對映主機 `/Users/steven`）為根，只能選其下的子資料夾。目前落點：`<完整路徑>/交易雷達_{id}_{日期}.xlsx`」與 `lastRunAt`／`lastRunStatus`。另附「立即匯出到目錄」鈕（呼叫 run-now，成功後顯示落點路徑與檔案大小）。

  `frontend/src/api/index.js` 的 `tradingRadar` 區塊新增：`getExportTimes()`／`saveExportTimes(times)`／`getExportSetting()`／`saveExportSetting(subpath)`／`runExportNow()`／`browseExportDir(subpath)`，全部走 `/bff/trading-radar/...`（**前端不得直呼 business**）。

- [ ] **231.9 測試**

  在 `backend/src/test/java/com/steven/assets/service/` 新增排程相關單元測試（測試檔命名由實作者定），至少覆蓋：

  - **同日多個時間點各自跑一次**：owner 有 `08:20` 與 `11:30` 兩列、當下 12:00、兩列 `last_run_date` 皆為昨日 → 兩列都被執行且各自被設為今日（**這是 guard 放在時間點列的關鍵迴歸；若誤放 owner 層此測試會失敗**）。
  - `last_run_date` 已是今日的時間點不重跑。
  - `enabled=false` 的時間點不跑。
  - `now < 設定時分` 不跑；`now >= 設定時分` 會跑（含「早該跑但被延遲」的補跑情境）。
  - 單一 owner 產檔丟例外時，其他 owner 的列仍被執行，且失敗列仍被設當日 guard。
  - `resolveDir` 對 `../` 與絕對路徑丟 `IllegalArgumentException`。
  - 檔名為 `交易雷達_{ownerId}_{yyyyMMdd}.xlsx`。
  - **當日零快照時不寫檔**：`store.range(...)` 回空時，不呼叫寫檔、目錄不出現新檔，`last_run_status` 為「當日尚無快照，未產檔」且 `last_run_date` 仍被設為今日。
  - **`PUT times` 送入重複時分回 `IllegalArgumentException`**（而非讓 UNIQUE 撞成 500）。
  - **`recordStatus` 在設定列不存在時會建立一列**（只設時間點、未設資料夾的使用者）。

  本專案在 Java 25 下跑 Mockito 需要 `-DargLine="-Dnet.bytebuddy.experimental=true"`（直接傳 `-D` 無效，surefire 會 fork 新 JVM）。

## 驗證

> **環境事實（已實測，照抄即可執行）**：容器名為 `asset-postgres`／`asset-redis`／`asset-bff`／`asset-business-services`／`asset-external-materials-service`（**不是** `asset-management-*-1`）。DB 的 user 與 database 皆為 `assets`。**只有 `asset-bff` 對 host 發佈 8080**；business-services 只在容器網路內聽 8080。BFF 的 `/api/bff/**` 需登入（回 401），免 OAuth 的做法是進 business 容器帶 `X-User-Id`／`X-User-Role`／`X-User-Status` header（**注意：zsh 不會對變數做 word splitting，header 必須逐個 inline 寫，不要塞進一個變數**）。**本專案沒有 root pom**，測試要用 `-f <module>/pom.xml`。business 與 external **未啟用 actuator**，只有 bff 有。容器 `/home/steven` 對映主機 `/Users/steven`。

```bash
# 1. 單元測試
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test \
  -DargLine="-Dnet.bytebuddy.experimental=true"

# 2. 前端建置（worktree 首次需先裝依賴）
cd frontend && /Users/steven/.nvm/versions/node/v22.21.0/bin/node \
  /Users/steven/.nvm/versions/node/v22.21.0/bin/npm ci --no-audit --no-fund && \
  /Users/steven/.nvm/versions/node/v22.21.0/bin/node ./node_modules/.bin/vite build && cd ..

# 3. 重建並重建容器（JVM 必須 --no-cache）＋ business 換 IP 後 restart bff
docker compose -p asset-management build --no-cache business-services bff frontend
docker compose -p asset-management up -d --no-deps --force-recreate business-services bff frontend
docker compose -p asset-management restart bff
curl -s http://localhost:8080/actuator/health

# 4. migration 已套用
docker exec -i asset-postgres psql -U assets -d assets -c \
  "SELECT id, dateexecuted FROM databasechangelog WHERE id LIKE 'v1.70.0%';"
docker exec -i asset-postgres psql -U assets -d assets -c "\d trading_radar_export_time"

# 5. 設定兩個時間點（取「現在的前一分鐘」與「前兩分鐘」，讓 now>=時間 立刻成立）
H1=$(date -v-2M +%H); M1=$(date -v-2M +%M); H2=$(date -v-1M +%H); M2=$(date -v-1M +%M)
docker exec asset-business-services curl -s -X PUT \
  -H "X-User-Id: 1" -H "X-User-Role: ADMIN" -H "X-User-Status: ACTIVE" \
  -H "Content-Type: application/json" \
  -d "[{\"runHour\":$H1,\"runMinute\":$M1,\"enabled\":true},{\"runHour\":$H2,\"runMinute\":$M2,\"enabled\":true}]" \
  http://localhost:8080/api/trading-radar/export-schedule/times -w "\nhttp=%{http_code}\n"

# 6. 設定輸出資料夾
docker exec asset-business-services curl -s -X PUT \
  -H "X-User-Id: 1" -H "X-User-Role: ADMIN" -H "X-User-Status: ACTIVE" \
  -H "Content-Type: application/json" -d '{"outputSubpath":"Project/radar-export"}' \
  http://localhost:8080/api/trading-radar/export-schedule/setting -w "\nhttp=%{http_code}\n"

# 7. 先觸發一次 get() 確保當日 Redis 有快照可寫
docker exec asset-business-services curl -s \
  -H "X-User-Id: 1" -H "X-User-Role: ADMIN" -H "X-User-Status: ACTIVE" \
  http://localhost:8080/api/trading-radar -o /dev/null -w "radar http=%{http_code}\n"

# 8. 等下一個整分 tick（最多 70 秒）後，確認檔案已落到主機家目錄
#    （容器內 /home/steven/Project/radar-export ⇄ 主機 /Users/steven/Project/radar-export）
ls -l ~/Project/radar-export/交易雷達_1_$(date +%Y%m%d).xlsx
head -c4 ~/Project/radar-export/交易雷達_1_$(date +%Y%m%d).xlsx | xxd   # 預期 PK..

# 9. 兩個時間點的 guard 都被設為今日（證明多時間點各自 guard 生效）
docker exec -i asset-postgres psql -U assets -d assets -c \
  "SELECT run_hour, run_minute, enabled, last_run_date FROM trading_radar_export_time
    WHERE owner_user_id = 1 ORDER BY run_hour, run_minute;"

# 10. 立即匯出到目錄（不動 guard）
docker exec asset-business-services curl -s -X POST \
  -H "X-User-Id: 1" -H "X-User-Role: ADMIN" -H "X-User-Status: ACTIVE" \
  http://localhost:8080/api/trading-radar/export-schedule/run-now

# 11. 路徑跳脫必須被擋（400）
docker exec asset-business-services curl -s -o /dev/null -w "%{http_code}\n" -X PUT \
  -H "X-User-Id: 1" -H "X-User-Role: ADMIN" -H "X-User-Status: ACTIVE" \
  -H "Content-Type: application/json" -d '{"outputSubpath":"../../etc"}' \
  http://localhost:8080/api/trading-radar/export-schedule/setting

# 11b. 資料夾列舉沒被 wildcard 路由吃掉（Major：若誤用 gateway route 會靜默 404）
#      (a) business 端既有端點本身可用：
docker exec asset-business-services curl -s -o /dev/null -w "business browse http=%{http_code}\n" \
  -H "X-User-Id: 1" -H "X-User-Role: ADMIN" -H "X-User-Status: ACTIVE" \
  "http://localhost:8080/api/export-schedule/browse?subpath="
#      (b) BFF 是用 controller 而非 gateway route（jar 內須有該類別）：
docker exec asset-bff sh -c 'unzip -l /app/app.jar | grep -i TradingRadarBffController' \
  || echo "！BFF 缺 TradingRadarBffController —— 若改用 gateway route 會被 /api/bff/trading-radar/** 搶匹配而 404"
#      (c) 前端實測：登入後在設定卡按「選擇」，資料夾樹要能展開（非 404）

# 12. 排程列表頁已含新排程
curl -s http://localhost:8080/api/bff/public/schedules 2>/dev/null | grep -o "交易雷達匯出" || \
  echo "（此端點需登入時改由畫面確認「公開資訊→排程列表」有『交易雷達匯出』）"

# 13. t230 的手動下載未被破壞（迴歸）
docker exec asset-business-services curl -s \
  -H "X-User-Id: 1" -H "X-User-Role: ADMIN" -H "X-User-Status: ACTIVE" \
  "http://localhost:8080/api/trading-radar/export?from=$(date +%F)T00:00:00&to=$(date +%F)T23:59:59" \
  -o /tmp/manual.xlsx -w "manual http=%{http_code} size=%{size_download}\n"
```

驗收判準：步驟 4 migration 已套用且兩表存在；步驟 8 主機家目錄出現 `交易雷達_1_<日期>.xlsx` 且為合法 xlsx；步驟 9 **兩列 `last_run_date` 皆為今日**（多時間點各自 guard）；步驟 10 回落點路徑與大小；步驟 11 回 400；步驟 13 手動下載仍 200（t230 未被破壞）。

## 完成報告

**實作日期：** 2026-07-21

**改動檔案：**
- 新增 changeset `v1.70.0-trading-radar-export-schedule.sql`（兩張表，全冪等 `IF NOT EXISTS`）＋註冊 `db.changelog-master.yaml`。
- 新增 entity `TradingRadarExportTime`／`TradingRadarExportSetting`（皆 `@Filter(ownerFilter)`）與對應 repository。
- 新增 `TradingRadarExportDto`（全 record）。
- 新增 `TradingRadarExportScheduleService`：設定 CRUD（listTimes／replaceTimes／getSetting／saveSetting／runNow）＋背景排程（`@Scheduled(cron="0 * * * * *")` tick＋`AtomicBoolean`＋`ApplicationReadyEvent` 自癒＋`runDueExports` 的 per-時間點 guard）＋`writeDailyExport`／`recordStatus`（自行 upsert）／`resolveDir`／`writeAtomically`（tmp 帶 UUID 後綴＋ATOMIC_MOVE）。
- 改 `TradingRadarExportService`：抽出 `exportForOwner(long, long, long)` 供背景使用，`export(String,String)` 保留 `ownerId == null` 分支後委派；兩者共用新的 private `build(...)`。
- 改 `TradingRadarController`：新增 5 支 `/export-schedule/**` 端點。
- 新增 BFF `TradingRadarBffController`（`@GetMapping("/export/browse")` → business `/api/export-schedule/browse`）——**刻意用 controller 而非 gateway route**。
- 改 `SchedulePublicBffController`：新增「交易雷達匯出」JOBS 一筆，並更正既有筆數漂移（business 12→16、external 24→28）。
- 前端 `api/index.js` 新增 6 支排程 API；`TradingRadarView.vue` 新增「匯出執行時間設定」「匯出輸出檔案設定」兩張卡＋`el-tree` 資料夾選擇器＋「立即匯出到目錄」，t230 既有的「匯出 Excel」瀏覽器下載鈕維持不動。
- 新增排程單元測試（10 案）。

**驗證輸出（部署後 asset-* 容器實測，owner=1）：**
- 單元測試：`Tests run: 18, Failures: 0, Errors: 0`（新增 10 ＋ t230 既有 8）；前端 `vite build` 通過。
- `spec-check.sh`：`BLOCK: 0`（含 B3 冪等、B4 master.yaml 註冊、**B6 `@Scheduled` 已同步 JOBS**）。
- migration：`v1.70.0-trading-radar-export-schedule | 2026-07-21 14:11:00`；兩張表建立成功。
- BFF jar 含 `TradingRadarBffController.class`（確認未走會被 wildcard 搶匹配的 gateway route）；bff log `Connection refused` 計數 0。
- 設定兩個時間點（00:00／00:01）＋資料夾 `Project/radar-export` → **排程於 20 秒內寫出主機家目錄檔案** `~/Project/radar-export/交易雷達_1_20260721.xlsx`（13345 bytes、`PK..` 合法 xlsx）。
- **關鍵迴歸：兩個時間點的 `last_run_date` 皆為 `2026-07-21`**——證明當日 guard 確為 per 時間點（誤放 owner 層時第二列會停在 null）。
- `last_run_status` = `成功：/home/steven/Project/radar-export/交易雷達_1_20260721.xlsx`。
- 立即匯出到目錄：回 `{"path":"/home/steven/Project/radar-export/交易雷達_1_20260721.xlsx","size":13345,"message":"匯出完成"}`。
- 路徑跳脫 `../../etc` → **400**；重複時分 `08:20 × 2` → **400**；business `browse` → 200。
- **t230 手動下載迴歸**：`/api/trading-radar/export?from&to` 仍回 200、13345 bytes（未被破壞）。
- 目錄無 `.tmp` 殘檔。

**與原計畫的偏差：**
- 對抗式審查（8/10 通過）在實作前抓到並修正 3 個 Major：(1) Requirement 48 既有 AC 與本追加自相矛盾 → 補「範圍限縮」修訂 AC；(2) **BFF 資料夾列舉若用 gateway route 會被既有 wildcard 搶匹配而靜默 404** → 改用 BFF controller（與其餘 7 頁一致）；(3) 當日零快照會寫出空檔 → 改為不寫檔、只記 `last_run_status`。另修 4 個 Minor（JOBS 筆數漂移、`exportForOwner` 拆箱 NPE、`recordStatus` 未定義且需 upsert、重複時分撞 UNIQUE 變 500）。
- 前端兩張設定卡的瀏覽器實測未執行：`/api/bff/**` 需使用者 OAuth 登入 session，無頭環境無法完成；已以「bundle 建置通過＋後端端到端全綠＋BFF controller 在 jar 內」佐證，UI 操作待使用者於登入狀態確認。
- 部署映像自本 feature worktree build（尚未 commit/merge）；依共用 stack 慣例，merge 進 main 後應改從 main 的 worktree 重建。
