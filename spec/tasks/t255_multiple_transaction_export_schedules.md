# [t255] 交易紀錄排程自動匯出改為「每人可設定多筆排程」

**對應 Requirements:** Requirement 49（資產交易紀錄流水帳與 Excel 手動／每日排程匯出；本任務只動其中「排程自動匯出」一段，把每人一筆放寬為每人多筆）
**前置任務:** t238（交易紀錄每日排程匯出：`asset_transaction_export_schedule` 表、`AssetTransactionExportScheduleService` 的每分鐘 tick／開機自癒／run-now）、t242–t243（該表的 Google Drive 同步四欄與 `GdriveOutputSupport`）
**Liquibase changeset:** `v1.80.0-asset-transaction-export-schedules-multi.sql`

> **編號說明（為何跳過 254）：** 建檔當下（2026-07-29）`t254` 已被另一個並行 worktree
> `.claude/worktrees/google-drive-output-directory-c23473`（分支 `claude/trigger-export-json-06f9a3`）
> 的 `t254_alert_trigger_json_export.md` 佔用，該檔尚未併入 main 故 `spec-check.sh` 查不到。
> 兩者為完全不同的工作，本任務避讓為 255。

## 背景

現況（t238）：交易紀錄頁的「排程自動匯出」卡是**每位使用者一筆**——表 `asset_transaction_export_schedule` 有 `uq_at_export_schedule_owner UNIQUE (owner_user_id)`，service 以 `findByOwnerUserId` upsert 單列，UI 是一張只能填一組值的表單。使用者要「早上匯一份到 A 資料夾、晚上再匯一份到 B 資料夾」時無路可走。

使用者要求：**交易紀錄的排程要可以多個。**

正確行為：同一位使用者可以建立多筆每日排程，每筆各自有名稱（選填）、啟用開關、執行時間（時:分）、輸出資料夾、Google Drive 同步設定，以及各自的上次執行時間／結果；系統在每筆各自的時間產檔到各自的目錄，單筆失敗不影響其他筆。

**只改交易紀錄頁這一頁。** 其餘七個匯出頁（歷年資產 R34／交易日曆 R37／已實現損益 R39／油價金價 R41／匯率 R42／GDP-TWSE R45／交易雷達 R48）與爬蟲頁維持每人一筆不變——使用者只對這一頁提出需求，一次改八頁會把風險放大八倍而沒有對應收益。

**被推翻的先前決定：** t238 的「每 `owner_user_id` 一列 UNIQUE」與「每日固定一個時間（比照 Requirement 39，非多時段）」。放寬方式是**多列**，不是在單列上長出多時間欄位——理由見下。

### 為什麼是「多列」而不是「一列多時間」

| 候選 | 為何不採 |
|------|----------|
| 一列存多個時間（`run_times VARCHAR` 如 `07:30,22:00`） | 一欄塞多值＝把結構塞進字串；之後每個時間點各自的「上次執行時間／結果／當日 guard」無處可放，違反本專案正規化原則 |
| 一列一 cron 字串 | 使用者要設的是「每天幾點」，cron 的表達力遠超需求，卻把驗證與 UI 複雜度全帶進來 |
| **多列（採用）** | 每筆排程＝一列；時間／目錄／Drive 設定／執行狀態天然各自持有，既有 tick 的 `findAll()` 逐列判斷邏輯**語意一行不用改**，只是列變多 |

### 動工前必須知道的運行中 DB 現況

以下為 2026-07-29 對運行中 DB（`docker exec asset-postgres psql -U assets -d assets -c '\d asset_transaction_export_schedule'`）實查結果，**不是抄 changelog**：

```
 id                 | bigint                      | not null | nextval(...)
 owner_user_id      | bigint                      | not null |
 enabled            | boolean                     | not null | false
 run_hour           | integer                     | not null | 8
 run_minute         | integer                     | not null | 0
 output_subpath     | character varying(255)      | not null | 'input'
 last_run_date      | date                        |          |
 last_run_at        | timestamp without time zone |          |
 last_run_status    | character varying(500)      |          |
 updated_at         | timestamp without time zone |          |
 gdrive_enabled     | boolean                     | not null | false
 gdrive_subpath     | character varying(512)      |          |
 gdrive_last_run_at | timestamp without time zone |          |
 gdrive_last_status | character varying(512)      |          |
Indexes: PK(id); "uq_at_export_schedule_owner" UNIQUE CONSTRAINT, btree (owner_user_id)
Check:   ck_at_export_schedule_hour (0..23), ck_at_export_schedule_minute (0..59)
```

`databasechangelog` 最新五筆為 `v1.78.0-stock-alert-group-constraints` / `v1.78.0-stock-alert-group` / `v1.79.0-naive-timestamp-to-taipei` / `v1.77.0-notification-recipient-calendar` / `v1.76.0-gdrive-output-all-export-pages` → **`v1.80.0` 未被佔號**（建檔前仍須再跑一次 `bash scripts/spec-check.sh` 確認，多 worktree 並行時版號會被別的分支搶走）。

## 要做什麼

### 255.1 Liquibase changeset

新檔 `backend/src/main/resources/db/changelog/changes/v1.80.0-asset-transaction-export-schedules-multi.sql`，首行 `--liquibase formatted sql`，changeset id `steven:v1.80.0-asset-transaction-export-schedules-multi`：

```sql
--liquibase formatted sql

--changeset steven:v1.80.0-asset-transaction-export-schedules-multi
-- Requirement 49 / Task 255：交易紀錄排程自動匯出改為每人可多筆。
-- 冪等（IF EXISTS / IF NOT EXISTS）：全機共用一套運行中 DB、多 worktree 並行，
-- 本 changeset 可能已被別的分支套用；非冪等即 business-services crash loop 整站掛（Task 207 教訓）。
-- 不做任何資料遷移：既有每人 1 列原地成為該使用者的第 1 筆排程，name 為 NULL → 檔名與落點完全不變。
ALTER TABLE asset_transaction_export_schedule DROP CONSTRAINT IF EXISTS uq_at_export_schedule_owner;
ALTER TABLE asset_transaction_export_schedule ADD COLUMN IF NOT EXISTS name VARCHAR(50);
-- UNIQUE 掉了以後 owner 查詢失去索引；列表端點與 by-id 驗歸屬都要走它。
CREATE INDEX IF NOT EXISTS idx_at_export_schedule_owner ON asset_transaction_export_schedule(owner_user_id);
```

- 在 `backend/src/main/resources/db/changelog/db.changelog-master.yaml` **最尾端**（`v1.79.0` 之後）新增 include，格式與既有各筆一致（`file:` ＋ `relativeToChangelogFile: false`）。
- **不得**在建檔後又改 changeset id 或內容：Liquibase 認新 id ＝新 migration 會重跑；同 id 改內容（含改註解，checksum 含註解）＝ `ValidationFailed` → business-services crash loop。若版號撞號必須避讓，**同步改檔名／changeset id／master include 三處**，且因為本 changeset 冪等，避讓後重跑安全。

### 255.2 Entity `AssetTransactionExportSchedule`

`backend/src/main/java/com/steven/assets/model/AssetTransactionExportSchedule.java`：

- 移除 `@Table` 上的 `uniqueConstraints = @UniqueConstraint(name = "uq_at_export_schedule_owner", columnNames = {"owner_user_id"})`，保留 `@Table(name = "asset_transaction_export_schedule")`。
- 保留 `@Filter(name = "ownerFilter", condition = "owner_user_id = :ownerId")`（HTTP 情境自動 owner-scope；背景 cron 無 request context 故不啟用，`findAll()` 讀全部列，這正是排程要的）。
- 新增欄位：
  ```java
  /** 排程名稱（選填）。非空時會成為檔名的一段：交易紀錄_{ownerId}_{name}_{yyyyMMdd}.xlsx */
  @Column(name = "name", length = 50)
  private String name;
  ```
- 其餘欄位、預設值、Lombok 註解一律不動。
- 類別 javadoc 更新：把「每個使用者一列（`owner_user_id` UNIQUE）」改為「每個使用者**多列**（Task 255 移除 UNIQUE）」，並保留原本「背景排程 `findAll()` 讀全部 owner 列、產檔時才對該列 owner 手動 `enableFilter`」的說明。

### 255.3 Repository

`backend/src/main/java/com/steven/assets/repository/AssetTransactionExportScheduleRepository.java`：

- **移除** `Optional<AssetTransactionExportSchedule> findByOwnerUserId(Long ownerUserId)`（單列語意已不存在，留著必被誤用成「取這個人的排程」而只拿到任意一筆）。
- 新增：
  ```java
  List<AssetTransactionExportSchedule> findByOwnerUserIdOrderByRunHourAscRunMinuteAscIdAsc(Long ownerUserId);
  Optional<AssetTransactionExportSchedule> findByIdAndOwnerUserId(Long id, Long ownerUserId);
  long countByOwnerUserId(Long ownerUserId);
  ```
- **`findByIdAndOwnerUserId` 是多租戶的關鍵，不得用 `findById` 取代**：Hibernate `@Filter` 不套用於 `EntityManager.find()`（即 `JpaRepository.findById` / `deleteById`），用 `findById` 會讓任何人以他人排程 id 讀取／修改／刪除／立即觸發他人的排程（見 `com.steven.assets.security.TenantGuard` 的 javadoc）。

### 255.4 DTO `AssetTransactionExportDto`

`backend/src/main/java/com/steven/assets/dto/AssetTransactionExportDto.java` 改為：

```java
/** 清單回應：所有變更端點（POST/PUT/DELETE）都回「變更後的完整清單」，前端不做客戶端合併。 */
@Builder
public record SchedulesResponse(
        String baseDir,          // 容器內基底目錄（顯示用衍生值，不入庫）
        String gdriveRemote,     // rclone remote 名稱（顯示用衍生值，不入庫）
        List<Item> schedules,
        // 只有「本次請求把某筆的 Drive 開關從 false 翻成 true」且本地自檢有問題時才有值，其餘一律 null。
        // 不入庫，尤其不得寫進 gdriveLastStatus（那一欄語意是「上次上傳」，寫進去會覆蓋昨晚真正的落點）。
        String gdriveSelfCheckWarning
) {}

@Builder
public record Item(
        Long id,
        String name,             // 選填，可為 null
        boolean enabled,
        Integer runHour,
        Integer runMinute,
        String outputSubpath,
        String lastRunAt,        // yyyy-MM-dd HH:mm:ss，無則 null
        String lastRunStatus,    // 「成功：/path」或「失敗：訊息」
        boolean gdriveEnabled,
        String gdriveSubpath,
        String gdriveLastRunAt,  // yyyy-MM-dd HH:mm:ss，無則 null
        String gdriveLastStatus
) {}

/**
 * 新增／修改共用，**全量取代語意**（前端一律送出整筆設定）：
 * name／enabled／runHour／runMinute／outputSubpath 送 null ＝套用預設值或清空
 * （name → null、enabled → false、runHour → 8、runMinute → 0、outputSubpath → "input"）。
 * **只有 gdriveEnabled／gdriveSubpath 兩欄** null ＝未送出＝不變更（Task 243.1.1：
 * 把已開啟的 Drive 開關靜默關掉會讓使用者以為還在同步）。
 * 兩套語意刻意不同，實作時不得統一——PUT 若把 name 當「未送出即保留」，
 * 前端「清空名稱」就無從表達；Drive 若當「未送出即 false」則會靜默關閉同步。
 */
public record SettingRequest(
        String name,
        Boolean enabled,
        Integer runHour,
        Integer runMinute,
        String outputSubpath,
        Boolean gdriveEnabled,
        String gdriveSubpath
) {}

@Builder
public record RunNowResponse(String path, long sizeBytes, String gdrivePath, String gdriveStatus) {}
```

- **移除**舊的 `SettingResponse`（單筆語意）。
- 不定義 Browse／DirEntry——目錄瀏覽沿用既有 `GET /api/export-schedule/browse`。

### 255.5 Service `AssetTransactionExportScheduleService`

`backend/src/main/java/com/steven/assets/service/AssetTransactionExportScheduleService.java`。建構子注入與常數（`TW_ZONE=Asia/Taipei`、`FILE_DATE=yyyyMMdd`、`TS_FMT=yyyy-MM-dd HH:mm:ss`、`AtomicBoolean ticking`、`baseDir` 來自 `@Value("${EXPORT_OUTPUT_DIR:/home/steven}")`）全部維持不變。

**新增常數：**
```java
/**
 * 每人排程數上限：每分鐘 tick 會逐列處理，無上限＝讓單一使用者無限放大背景工作量。
 * <b>軟上限、防呆而非防惡意</b>——先 count 再 save 非原子，並發送出多個 POST 時可能短暫超過。
 * 刻意不加 DB 層約束（那需要 owner 計數觸發器或部分索引，複雜度遠高於它擋下的風險）。
 */
private static final int MAX_SCHEDULES_PER_USER = 10;
/** 名稱會直接進檔名，故白名單驗證：中文／英數／底線／連字號／空白，長度 1..20。 */
private static final Pattern NAME_OK = Pattern.compile("^[\\p{IsHan}A-Za-z0-9_\\- ]{1,20}$");
```

**HTTP（owner-scoped）方法：**

- `SchedulesResponse listForCurrentUser()`：`requireOwnerId()` → `findByOwnerUserIdOrderByRunHourAscRunMinuteAscIdAsc(ownerId)` → 組 `SchedulesResponse`（`baseDir`、`gdrive.remoteName()`、`schedules`、`gdriveSelfCheckWarning=null`）。**沒有任何列時回空 list，不寫入 DB**（維持 t238「無則回預設、不寫 DB」的精神：讀取端點不得產生副作用）。
- `SchedulesResponse createForCurrentUser(SettingRequest req)`：
  - `requireOwnerId()`；`if (settingRepo.countByOwnerUserId(ownerId) >= MAX_SCHEDULES_PER_USER) throw new IllegalArgumentException("排程數量已達上限 " + MAX_SCHEDULES_PER_USER + " 筆，請先刪除不用的排程")`（→ 400）。
  - 以下列驗證流程建立新列後存檔，回傳**變更後的完整清單**。
- `SchedulesResponse updateForCurrentUser(Long id, SettingRequest req)`：
  - `AssetTransactionExportSchedule s = settingRepo.findByIdAndOwnerUserId(id, ownerId).orElseThrow(() -> new NoSuchElementException("找不到指定的排程設定"))`（`NoSuchElementException` 已由既有 `GlobalExceptionHandler` 對映 404）。**不可**先 `findById` 再比對 owner 後回 403——那會洩漏他人排程是否存在。
  - 套用驗證後存檔，回完整清單。
- `SchedulesResponse deleteForCurrentUser(Long id)`：同樣先 `findByIdAndOwnerUserId(...).orElseThrow(NoSuchElementException::new)` 再 `settingRepo.delete(s)`（**不得** `deleteById(id)`——那不吃 owner 過濾），回完整清單。
- `RunNowResponse runNowForCurrentUser(Long id)`：先 `findByIdAndOwnerUserId(...).orElseThrow(...)`（**在 try 之外**，否則 404 會被包成 500）；`byte[] data = excelExportService.exportAssetTransactions()`（HTTP 情境由 `TenantFilterAspect` 自動 owner-scope）→ `writeToDir(ownerId, s.getName(), s.getOutputSubpath(), data)` → 設該列 `lastRunAt`／`lastRunStatus`（成功「成功：/path」／失敗「失敗：訊息」）→ `syncGdrive(s, file)` → save → 回 `{path, sizeBytes, gdrivePath, gdriveStatus}`。**不動 `lastRunDate`**（不影響該筆當日排程 guard），也不碰其他筆。

**共用的欄位驗證（`createForCurrentUser`／`updateForCurrentUser` 都走同一段，不得各寫一份）：**

- `s.setEnabled(Boolean.TRUE.equals(req.enabled()))`（null → false，沿用 t238 既有語意）。
- `runHour` null → 8；`runMinute` null → 0；`hour<0||hour>23` 擲 `IllegalArgumentException("執行時(hour)必須介於 0～23")`；`minute<0||minute>59` 擲 `IllegalArgumentException("執行分(minute)必須介於 0～59")`。
- `normalizeSubpath(req.outputSubpath())`（null／空白 → `"input"`，否則 `trim()`）→ `resolveDir(subpath)` 驗證 normalize 後仍在基底內（拒 `..` 與絕對路徑跳脫），不合法即擲出擋下。
- `String name = normalizeName(req.name())`：null／trim 後為空 → `null`；否則 `trim()` 後長度／字元以 `NAME_OK` 驗，不符擲 `IllegalArgumentException("排程名稱只能是中英數、底線、連字號與空白，且不超過 20 字")`。
- Drive 兩欄一律走 `gdrive.resolveUpdate(ownerId, req.gdriveEnabled(), req.gdriveSubpath(), 目前 enabled, 目前 subpath)`（null＝未送出＝不變更、啟用時 subpath 必填、只有主要管理者能啟用→403、回傳含 `selfCheckWarning`）；**不得**自行改寫這段邏輯。新建列的「目前值」傳 `false` 與 `null`。
- `s.setUpdatedAt(LocalDateTime.now(TW_ZONE))`；**刻意不碰** `lastRunDate`／`lastRunAt`／`lastRunStatus`／`gdriveLastRunAt`／`gdriveLastStatus`——那些是執行結果，不是使用者設定。

**背景排程（語意不變，只是列變多）：**

- `@Scheduled(cron = "0 * * * * *", zone = "Asia/Taipei") tick()`：`AtomicBoolean` 防重入 → `runDueExports()`。
- `@EventListener(ApplicationReadyEvent.class) selfHealOnStartup()`：`runDueExports()`。
- `runDueExports()`：`for (var s : settingRepo.findAll())`（背景無 request context → filter 不啟用 → 讀全部人的全部列）→ 跳過 `!enabled`、跳過 `today.equals(lastRunDate)`、`now >= LocalTime.of(runHour, runMinute)` 才 `runScheduled(s, today)`（**維持 `>=` 而非分鐘精確相等**，否則排程執行緒被長工作卡住跨分鐘就整日靜默漏跑）。
- `runScheduled(s, today)`：`excelExportService.exportAssetTransactionsForOwner(s.getOwnerUserId())` → `writeToDir(ownerId, s.getName(), subpath, data)` → 成功記 log ＋ `syncGdrive(s, file)`；失敗只寫該列 `lastRunStatus`＋log ＋ `syncGdrive(s, null)`；**`finally` 一律設該列 `lastRunDate=today`、`lastRunAt=now` 並 save**。
- **同一 owner 出現多列時，每列各自呼叫 `exportAssetTransactionsForOwner(ownerId)`**——不得為了「省一次查詢」在迴圈外只 `enableFilter` 一次或把同 owner 的列合併處理；租戶隔離的正確性優先於效能，而且合併後單列失敗會連坐其他列。

**檔名：**

```java
/** 檔名含 ownerId，避免多使用者同 subpath 時互相覆蓋；name 非空時再加一段，讓同一人多筆排程可各留一份。 */
private Path writeToDir(Long ownerId, String name, String subpath, byte[] data) throws IOException {
    Path dir = resolveDir(normalizeSubpath(subpath));
    Files.createDirectories(dir);
    String date = LocalDate.now(TW_ZONE).format(FILE_DATE);
    String suffix = (name == null || name.isBlank()) ? "" : "_" + name.trim();
    Path file = dir.resolve("交易紀錄_" + ownerId + suffix + "_" + date + ".xlsx");
    // 與 subpath 同等級的執行時重驗：DB 值可能被繞過 API 以 psql 直改（既有 toResponse／
    // GdriveOutputSupport.syncQuietly 都是這個假設），name 進了檔名就等於進了路徑。
    if (!file.normalize().startsWith(dir)) {
        throw new IllegalArgumentException("排程名稱不合法，拒絕寫入：" + name);
    }
    Files.write(file, data);
    return file;
}
```

- **`name` 為空時檔名必須與 t238 逐字元相同**（`交易紀錄_{ownerId}_{yyyyMMdd}.xlsx`）。既有唯一一筆排程升級後 `name` 為 NULL，落點與檔名不變，餵給下游程式的輸入檔不會斷——這是本任務的相容性紅線。

### 255.6 Controller

`backend/src/main/java/com/steven/assets/controller/AssetTransactionExportController.java`（`@RestController @RequestMapping("/api/asset-transactions/export")`）：

- **移除** `GET /schedule`、`PUT /schedule`、`POST /run-now` 三支（單筆語意已不存在；與新端點並存＝同一份設定兩套語意，必然漂移）。
- 新增：
  - `GET /schedules` → `service.listForCurrentUser()`
  - `POST /schedules` → `service.createForCurrentUser(req)`
  - `PUT /schedules/{id}` → `service.updateForCurrentUser(id, req)`
  - `DELETE /schedules/{id}` → `service.deleteForCurrentUser(id)`
  - `POST /schedules/{id}/run-now` → `service.runNowForCurrentUser(id)`
- 路徑共存不變：`AssetTransactionController` 的 `GET /api/asset-transactions/export` 是瀏覽器下載端點，本 controller 一律帶子路徑，無 ambiguous mapping。

### 255.7 BFF passthrough

`bff/src/main/java/com/steven/assets/bff/transaction/TransactionBffController.java`：

- **移除** `GET /export/schedule`、`PUT /export/schedule`、`POST /export/run-now` 三支。
- 新增（全部 passthrough，`X-User-*` 由全域 WebClient filter 自動往下帶，此處不自行處理標頭）：
  - `GET /api/bff/transaction/export/schedules` → business `GET /api/asset-transactions/export/schedules`
  - `POST /api/bff/transaction/export/schedules` → business `POST /api/asset-transactions/export/schedules`
  - `PUT /api/bff/transaction/export/schedules/{id}` → business `PUT /api/asset-transactions/export/schedules/{id}`
  - `DELETE /api/bff/transaction/export/schedules/{id}` → business `DELETE /api/asset-transactions/export/schedules/{id}`
  - `POST /api/bff/transaction/export/schedules/{id}/run-now` → business `POST /api/asset-transactions/export/schedules/{id}/run-now`
- `GET /export/browse`、`GET /export/browse-gdrive` 不動。
- **DELETE 也要回 body**（後端回的是變更後完整清單），故回 `Mono<ResponseEntity<Map<String,Object>>>` 而非 `toBodilessEntity()`。

### 255.8 前端 API

`frontend/src/api/index.js` 的 `transaction` 命名空間：把 `getExportSchedule`／`updateExportSchedule`／`runExportNow` 換成——

```js
listExportSchedules:  () => api.get('/bff/transaction/export/schedules', { skipErrorToast: true }),
createExportSchedule: (data) => api.post('/bff/transaction/export/schedules', data, { skipErrorToast: true }),
updateExportSchedule: (id, data) => api.put(`/bff/transaction/export/schedules/${id}`, data, { skipErrorToast: true }),
deleteExportSchedule: (id) => api.delete(`/bff/transaction/export/schedules/${id}`, { skipErrorToast: true }),
runExportNow:         (id) => api.post(`/bff/transaction/export/schedules/${id}/run-now`, null, { timeout: 60000, skipErrorToast: true }),
```

`browseExportDir`／`browseGdriveExportDir` 不動。

### 255.9 前端排程卡改為多筆

`frontend/src/views/TransactionView.vue`「⏱️ 排程自動匯出」卡：

- 卡片 header 右側改為單一「新增排程」按鈕（`type="primary"`）。
- 卡片內容：`v-for="(s, idx) in schedules"` 逐筆渲染一個有框區塊，**每一區塊沿用目前那張表單的版面**（啟用開關／`el-time-picker`／輸出資料夾唯讀 input＋「選擇」／admin-only 的 Drive 開關＋Drive 資料夾＋「上次上傳」說明），另加：
  - 名稱 `el-input`（`maxlength="20"`、placeholder「（選填，會成為檔名的一段）」）。
  - 區塊右上：「儲存」「立即匯出」「刪除」（刪除用 `el-popconfirm` 二次確認）。三顆按鈕的 loading 狀態**逐筆各自持有**（`s._saving` / `s._running`），不得共用單一全域 flag，否則按其中一筆會讓每一筆都轉圈。
  - 區塊內顯示該筆的「上次執行：{lastRunAt} {lastRunStatus}」。
- 空清單時顯示 `el-empty` 或一行提示：「尚未建立排程，按右上『新增排程』開始。」
- 「新增排程」＝直接 `createExportSchedule({ enabled: false, runHour: 8, runMinute: 0, outputSubpath: 'input' })` 後重載清單（新列預設**停用**，使用者設定完再自行開啟；不做前端草稿列，避免 id 為 null 的分支狀態）。
- 目前的卡片層級 `const scheduleTime = ref('08:00')`（`TransactionView.vue:373`）**必須改為每列各持一份**（例如列物件上的 `_time`，格式 `'HH:mm'`），儲存時再拆回 `runHour`／`runMinute`；沿用單一 ref 會讓所有列共用同一個時間值。
- 資料夾選擇器 dialog 只有一個，需記住是為哪一筆而開：既有 `function openDirPicker(mode = 'local')`（`TransactionView.vue:747`）改為 `openDirPicker(rowIndex, mode = 'local')` 並存進 `dirPicker.rowIndex`（＋既有的 `mode: 'local' | 'gdrive'`），`confirmDirPick()` 寫回 `schedules[rowIndex]` 的對應欄位。`dirPickerPreview` 的基底沿用回應中的 `baseDir` / `gdriveRemote`（改為存在卡片層級狀態，不再放在單筆內）。
- 說明文字（卡片底部）更新為：
  - 「每筆排程於各自時間匯出交易紀錄為 `交易紀錄_{使用者ID}_{YYYYMMDD}.xlsx`；**填了名稱的排程**檔名為 `交易紀錄_{使用者ID}_{名稱}_{YYYYMMDD}.xlsx`。」
  - 「兩筆排程若指到**同一資料夾且同檔名**（都沒填名稱或名稱相同），後執行的會覆寫前一份。每一份都是**執行當下**的完整交易紀錄（涵蓋全部年度、不會缺年度），但**不是同一時點的快照**——兩次執行之間新增或修改的交易只會出現在後面那一份。要保留各時段各一份，請填不同名稱。」
  - 「『立即匯出』使用的是**已儲存**的設定；剛改過還沒按儲存的值不會生效。」
- 多 panel 載入維持 `Promise.allSettled([load(), loadSchedules()])` 並行。

### 255.10 排程列表頁

`bff/src/main/java/com/steven/assets/bff/schedulelist/SchedulePublicBffController.java`：

- **`@Scheduled` 方法數不變**（仍是同一支每分鐘 tick），故 `JOBS` **不新增也不刪除任何一筆**，business 維持 17、總數維持 46（實查：`grep -c "new ScheduledJobDto(" bff/src/main/java/com/steven/assets/bff/schedulelist/SchedulePublicBffController.java` ＝ 46），`spec/design.md` 的「共 46 筆」與該檔 `:13`（javadoc「business-services（17 個）與 external-materials-service（29 個）」）／`:52`（「全系統排程清單（46 筆）」）／`:199`（「回傳全系統排程清單（46 筆靜態資料）」）三處數字**一律不得改動**。
- 只把「交易紀錄匯出」那一筆的 description 從「每分鐘檢查各使用者的交易紀錄自動匯出設定…」改為「每分鐘檢查各使用者的**每一筆**交易紀錄自動匯出排程…」，避免文案與實作漂移。

### 255.11 測試

`backend/src/test/java/com/steven/assets/service/AssetTransactionExportScheduleServiceTest.java` 改寫（沿用既有 `@ExtendWith(MockitoExtension.class) @MockitoSettings(strictness = LENIENT)`、`@TempDir Path baseDir`、以及既有對 `RcloneClient`／`AppUserRepository`／`UserAdminService`／`GdriveSelfCheck` 的替身注入方式），至少涵蓋：

- **多筆各自執行**：同一 owner 兩列（07:30 到 `a`、22:00 到 `b`），`now` 在 08:00 時只有第一列產檔、第二列不跑；兩列的 `lastRunDate` 各自獨立。
- **單列失敗不連坐**：同 owner 兩列，第一列的 `exportAssetTransactionsForOwner` 擲例外 → 該列 `lastRunStatus` 以「失敗：」開頭且 `lastRunDate` 仍設為今日，第二列仍正常產檔。
- **租戶隔離**：背景走 `exportAssetTransactionsForOwner(ownerId)` 而非 HTTP 版 `exportAssetTransactions()`（`verify(...)` ＋ `never()`）；同 owner 兩列時該方法被呼叫**兩次**。
- **by-id 驗歸屬**：`updateForCurrentUser`／`deleteForCurrentUser`／`runNowForCurrentUser` 在 `findByIdAndOwnerUserId` 回 empty 時擲 `NoSuchElementException`；且**驗證實作沒有呼叫 `findById`**（`verify(settingRepo, never()).findById(any())`、`never()).deleteById(any())`）。
- **數量上限**：`countByOwnerUserId` 回 10 時 `createForCurrentUser` 擲 `IllegalArgumentException`，且未呼叫 `save`。
- **名稱驗證**：`"早班"`／`"morning-1"`／`"a b"` 通過；`"a/b"`、`".."`、`"a:b"`、含換行、21 字擲 `IllegalArgumentException`；`null` 與 `"   "`（純空白）正規化為 `null`；**`"x "` 通過並正規化為 `"x"`**（實作先 `trim()` 再驗，trailing space 不是錯誤、也不會被帶進檔名——斷言結果字串為 `"x"`，不要期待例外）。
- **檔名**：`name` 為 null → `交易紀錄_{ownerId}_{yyyyMMdd}.xlsx`（與 t238 相同）；`name="早班"` → `交易紀錄_{ownerId}_早班_{yyyyMMdd}.xlsx`。以 `@TempDir` 實檔驗證檔案存在。
- **既有回歸**：時分越界擲例外；`..`／絕對路徑子路徑被擋；空子路徑正規化為 `input`；`today == lastRunDate` 不重跑；停用列不跑；`now < 設定時分` 不跑；`runNowForCurrentUser` 不改 `lastRunDate`。
- Mockito 於 Java 25 環境需 `-DextraArgLine=-Dnet.bytebuddy.experimental=true`（**不可用 `-DargLine`**，那會覆蓋掉 pom 內的時區設定造成大量測試 error）；本機 JDK 21 則免。
- 若既有 `ExportScheduleGdriveTest`／其他測試引用了被移除的 `findByOwnerUserId` 或舊 DTO，一併修到編譯通過（不得為了讓測試過而把 production 端的舊方法留著）。

### 255.12 spec 回填

實作完成後回填本檔「完成報告」段。

## 驗證

```bash
cd /Users/steven/Project/asset-management/.claude/worktrees/multiple-transaction-schedules-de304a
bash scripts/spec-check.sh
git diff --check

JAVA_HOME=$(/usr/libexec/java_home -v 21) PATH="$JAVA_HOME/bin:$PATH" \
  /usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test
/Users/steven/.nvm/versions/node/v22.21.0/bin/node \
  /Users/steven/.nvm/versions/node/v22.21.0/bin/npm --prefix frontend run build

# 共用 stack：JVM service 一律 --no-cache（cached build 會產出不含本次變更的 stale jar）
cp /Users/steven/Project/asset-management/.env . 2>/dev/null || true
docker compose -p asset-management build --no-cache business-services bff
docker compose -p asset-management build frontend
docker compose -p asset-management up -d --no-deps --force-recreate business-services bff frontend
# 重建 business 會換 IP，BFF 握舊 IP 會 500 且 ≥3 分鐘不自癒 → 一律 restart bff
docker compose -p asset-management restart bff
curl -s http://localhost:8080/actuator/health

# migration 已套用、UNIQUE 已移除、name 欄已存在
docker exec asset-postgres psql -U assets -d assets -c '\d asset_transaction_export_schedule' | grep -E 'name|uq_at_export_schedule_owner|idx_at_export_schedule_owner'

# e2e（免 OAuth，在 business 容器內帶 X-User-* 模擬租戶）
docker exec asset-business-services sh -c "curl -s http://localhost:8080/api/asset-transactions/export/schedules \
  -H 'X-User-Id: 1' -H 'X-User-Role: USER' -H 'X-User-Status: ACTIVE'"
# 新增第二筆（帶名稱）
docker exec asset-business-services sh -c "curl -s -X POST http://localhost:8080/api/asset-transactions/export/schedules \
  -H 'Content-Type: application/json' -H 'X-User-Id: 1' -H 'X-User-Role: USER' -H 'X-User-Status: ACTIVE' \
  -d '{\"name\":\"晚班\",\"enabled\":true,\"runHour\":22,\"runMinute\":0,\"outputSubpath\":\"input\"}'"
# 對新增那筆 run-now（把 {id} 換成上一步回傳的 id），確認落點檔名含「晚班」
docker exec asset-business-services sh -c "curl -s -X POST http://localhost:8080/api/asset-transactions/export/schedules/{id}/run-now \
  -H 'X-User-Id: 1' -H 'X-User-Role: USER' -H 'X-User-Status: ACTIVE'"
ls -l /Users/steven/input/ | grep 交易紀錄
# 既有那一筆（實查：id=2, owner_user_id=1, enabled=t, 07:30, output_subpath='Project/SRPP/data/input'）
# 升級後 name 仍為 NULL，檔名必須仍是 交易紀錄_1_YYYYMMDD.xlsx（相容性紅線）
ls -l /Users/steven/Project/SRPP/data/input/ | grep 交易紀錄
# 租戶隔離：以另一個 user 對上面那個 id 操作，必須回 404
docker exec asset-business-services sh -c "curl -s -o /dev/null -w '%{http_code}\n' -X DELETE \
  http://localhost:8080/api/asset-transactions/export/schedules/{id} \
  -H 'X-User-Id: 2' -H 'X-User-Role: USER' -H 'X-User-Status: ACTIVE'"
# 排程列表頁筆數不得變動（仍為 46）
curl -s http://localhost:8080/api/bff/schedule-list | grep -o '"name"' | wc -l
```

前端（`http://localhost:8080` → 資產管理 → 交易紀錄）：確認排程卡可新增多筆、逐筆設定時間與資料夾、逐筆儲存／立即匯出／刪除；把其中一筆設為「當下＋1 分」並啟用，等到點後確認家目錄出現對應檔名，且**另一筆的 `lastRunAt` 不受影響**。升級前既有那一筆（`name` 為 NULL）的落點與檔名須與升級前完全相同。

## 完成報告

**完成日期：** 2026-07-29

### 實際改動檔案

| 檔案 | 內容 |
|------|------|
| `backend/.../db/changelog/changes/v1.80.0-asset-transaction-export-schedules-multi.sql` | 新增（DROP UNIQUE ＋ ADD name ＋ CREATE INDEX，皆冪等） |
| `backend/.../db/changelog/db.changelog-master.yaml` | 註冊 v1.80.0 |
| `backend/.../model/AssetTransactionExportSchedule.java` | 移除 `@Table` 的 UNIQUE、新增 `name`（VARCHAR 50） |
| `backend/.../repository/AssetTransactionExportScheduleRepository.java` | 移除 `findByOwnerUserId`；新增 `findByOwnerUserIdOrderByRunHourAscRunMinuteAscIdAsc`／`findByIdAndOwnerUserId`／`countByOwnerUserId` |
| `backend/.../dto/AssetTransactionExportDto.java` | `SettingResponse` → `SchedulesResponse` ＋ `Item`；`SettingRequest` 加 `name` |
| `backend/.../service/AssetTransactionExportScheduleService.java` | list／create／update(id)／delete(id)／runNow(id)；`applyRequest` 共用驗證；`normalizeName`＋`NAME_OK`；`writeToDir(ownerId, name, subpath, data)` ＋ 落點重驗 |
| `backend/.../controller/AssetTransactionExportController.java` | 三支單筆端點 → 五支多筆端點 |
| `bff/.../transaction/TransactionBffController.java` | 對應五支 passthrough（DELETE 改回 body） |
| `bff/.../schedulelist/SchedulePublicBffController.java` | 只改「交易紀錄匯出」description，**筆數維持 46** |
| `frontend/src/api/index.js` | `listExportSchedules`／`createExportSchedule`／`updateExportSchedule(id,…)`／`deleteExportSchedule`／`runExportNow(id)` |
| `frontend/src/views/TransactionView.vue` | 排程卡改為可新增／刪除的多筆列表；每列各自 `_time`／`_saving`／`_running`／`_deleting`；`openDirPicker(idx, mode)`；`baseDir`／`gdriveRemote` 提到卡片層級 |
| `backend/src/test/.../AssetTransactionExportScheduleServiceTest.java` | 改寫為 25 個測試 |

### 測試

- `mvn -f backend/pom.xml test` → **Tests run: 324, Failures: 0, Errors: 0**（其中本任務 25 個）。
- `npm --prefix frontend run build` → `✓ built in 4.28s`。
- 本地 JDK 21，未使用 byte-buddy 旗標。

### 部署與 e2e（實機，2026-07-29 13:16 起）

`docker compose -p asset-management build --no-cache business-services bff` ＋ `--no-cache frontend`，
`up -d --no-deps --force-recreate business-services bff frontend`，`restart bff`；三個容器 healthy，
`docker logs asset-bff --since 5m | grep -cE "Connection refused|500 Server Error"` → **0**。

- migration：`databasechangelog` 最新一筆＝`v1.80.0-asset-transaction-export-schedules-multi`；
  `\d asset_transaction_export_schedule` 顯示 `name character varying(50)`、
  `"idx_at_export_schedule_owner" btree (owner_user_id)`、**`uq_at_export_schedule_owner` 已消失**。
- **既有列原地保留**：id=2 / owner=1 / 07:30 / `Project/SRPP/data/input` / `name` NULL，
  `lastRunStatus` 仍是升級前那次的「成功：/home/steven/Project/SRPP/data/input/交易紀錄_1_20260729.xlsx」——
  **檔名逐字元不變**（host 端 `ls` 亦確認 `交易紀錄_1_20260729.xlsx` 未被改名）。
- 新增第二筆（`name=晚班`、22:00、`input`）→ 列表回兩筆，排序 07:30 在前。
- 對第二筆 run-now → `{"path":"/home/steven/input/交易紀錄_1_晚班_20260729.xlsx","sizeBytes":8226}`，
  host `/Users/steven/input/交易紀錄_1_晚班_20260729.xlsx`（8226 bytes）確實產生；第一筆的 SRPP 落點完全未動。
- **租戶隔離**：以 `X-User-Id: 2` 對該筆 DELETE → **404**、run-now → **404**、列表 → **0 筆**。
- **名稱驗證**：`name="a/b"` 的 PUT → **400**「排程名稱只能是中英數、底線、連字號與空白，且不超過 20 字」。
- 產物驗證（避免 stale image）：`asset-business-services` jar 內
  `AssetTransactionExportController.class` 含 `schedules`；`asset-bff` jar 含
  `/api/asset-transactions/export/schedules/{id}/run-now`；前端 bundle 含 `export/schedules`
  且**查無舊路徑** `transaction/export/schedule"`。
- e2e 用的第二筆排程（id=3）測完已 DELETE，DB 回到只剩既有那一筆；
  `/Users/steven/input/交易紀錄_1_晚班_20260729.xlsx` 這個測試檔留在原地未刪。
- 前端頁面本身需 Google 登入，未由實作者以瀏覽器驗證（不代登入），改以「bundle 含新路徑＋API e2e 全綠」佐證，
  視覺確認交由使用者。

### 與原計畫的偏差

1. **255.5 的 `create`／`update` 共用驗證抽成私有 `applyRequest(ownerId, s, req)`** 並回傳
   `selfCheckWarning`，而非在兩個方法各寫一次——與任務檔「不得各寫一份」的要求一致，只是落點寫成一支方法。
2. 前端 `saveSchedule`／`handleDeleteSchedule` 成功時整份取代 `schedules`，故不需在 `finally` 還原 `_saving`／
   `_deleting`；只有失敗路徑才還原。
3. 其餘一致，無其他偏差。
