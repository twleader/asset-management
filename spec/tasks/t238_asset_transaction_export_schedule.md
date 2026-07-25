# [t238] 交易紀錄 Excel 每日排程自動匯出到指定目錄

**對應 Requirements:** Requirement 49（交易紀錄除手動下載外，可指定輸出資料夾並設定每日自動匯出時間，定期把交易紀錄留存到本機目錄）
**前置任務:** t237（交易紀錄 entity／CRUD／手動 Excel 匯出；本任務依賴其 `AssetTransaction` entity 與 `ExcelExportService.exportAssetTransactions()`）
**Liquibase changeset:** v1.73.0-asset-transaction-export-schedule.sql

## 背景

t237 已讓使用者在「交易紀錄」頁手動下載 Excel（瀏覽器下載）。本任務新增**每日排程自動匯出**：每位使用者可各自開啟排程、設定每日執行時間與輸出資料夾，系統於該時間把該使用者的交易紀錄產出 `.xlsx` 到指定目錄；並提供「立即匯出到目錄」供驗證。

整套形狀**完全比照 Requirement 39（已實現損益排程匯出，Task 196）**，不得自創機制。差異只在「匯出哪份活頁簿」（交易紀錄）與「新增一張獨立排程設定表」。

**背景排程的租戶隔離是本任務最關鍵、最易出錯的點：** `AssetTransaction` 帶 `@Filter(ownerFilter)`，但背景 `@Scheduled` 執行緒**沒有 HTTP request context**，`TenantFilterAspect` 不啟用 → `findAll()` 會讀到**所有使用者**的交易紀錄。若直接沿用 HTTP 版的 `exportAssetTransactions()` 產檔，會把**所有人的交易寫進每個人的檔案**（資料外洩）。故必須新增 `exportAssetTransactionsForOwner(Long ownerId)`，在該 session 手動 `enableFilter("ownerFilter")` 縮到該列 owner。

## 要做什麼

### 238.1 新增排程設定 entity `AssetTransactionExportSchedule`

`backend/src/main/java/com/steven/assets/model/AssetTransactionExportSchedule.java`（比照 `RealizedGainExportSchedule`）：

- `@Entity @Table(name = "asset_transaction_export_schedule", uniqueConstraints = @UniqueConstraint(name = "uq_at_export_schedule_owner", columnNames = {"owner_user_id"}))`。
- `@Filter(name = "ownerFilter", condition = "owner_user_id = :ownerId")`。
- Lombok `@Data @NoArgsConstructor @AllArgsConstructor @Builder`。
- 欄位（型別、預設值照此）：
  - `id` Long，`@Id @GeneratedValue(IDENTITY)`
  - `ownerUserId` Long，`@Column(name = "owner_user_id", nullable = false)`
  - `enabled` Boolean，`@Column(nullable = false) @Builder.Default = Boolean.FALSE`
  - `runHour` Integer，`@Column(name = "run_hour", nullable = false) @Builder.Default = 8`
  - `runMinute` Integer，`@Column(name = "run_minute", nullable = false) @Builder.Default = 0`
  - `outputSubpath` String，`@Column(name = "output_subpath", nullable = false, length = 255) @Builder.Default = "input"`
  - `lastRunDate` LocalDate，`@Column(name = "last_run_date")`
  - `lastRunAt` LocalDateTime，`@Column(name = "last_run_at")`
  - `lastRunStatus` String，`@Column(name = "last_run_status", length = 500)`
  - `updatedAt` LocalDateTime，`@Column(name = "updated_at")`

### 238.2 Liquibase changeset

`backend/src/main/resources/db/changelog/changes/v1.73.0-asset-transaction-export-schedule.sql`（`--liquibase formatted sql`，changeset id `steven:v1.73.0-asset-transaction-export-schedule`）：

```sql
CREATE TABLE IF NOT EXISTS asset_transaction_export_schedule (
    id              BIGSERIAL PRIMARY KEY,
    owner_user_id   BIGINT       NOT NULL,
    enabled         BOOLEAN      NOT NULL DEFAULT FALSE,
    run_hour        INT          NOT NULL DEFAULT 8,
    run_minute      INT          NOT NULL DEFAULT 0,
    output_subpath  VARCHAR(255) NOT NULL DEFAULT 'input',
    last_run_date   DATE,
    last_run_at     TIMESTAMP,
    last_run_status VARCHAR(500),
    updated_at      TIMESTAMP,
    CONSTRAINT uq_at_export_schedule_owner UNIQUE (owner_user_id),
    CONSTRAINT ck_at_export_schedule_hour CHECK (run_hour BETWEEN 0 AND 23),
    CONSTRAINT ck_at_export_schedule_minute CHECK (run_minute BETWEEN 0 AND 59)
);
```

- `CREATE TABLE IF NOT EXISTS`（冪等）。在 `db.changelog-master.yaml` v1.72.0 之後新增 include（`relativeToChangelogFile: false`）。
- **建檔前先跑 `bash scripts/spec-check.sh` 確認 v1.73.0 未被佔號**；若被佔，改下一版號並同步 changeset id／檔名／master include。
- **不得**改 changeset id 後又對已跑過的環境重跑（Liquibase 認新 id＝新 migration 會重跑）——本表為全新表、無此風險，但版號避讓改名時務必用 `IF NOT EXISTS` 保持冪等。

### 238.3 排程 Service

`backend/src/main/java/com/steven/assets/service/AssetTransactionExportScheduleService.java`（**逐段比照 `RealizedGainExportScheduleService`**，只換匯出來源與檔名前綴）：

- 建構子注入：`AssetTransactionExportScheduleRepository`、`ExcelExportService`、`ObjectProvider<CurrentUserContext>`、`@Value("${EXPORT_OUTPUT_DIR:/home/steven}") String baseDir`。
- 常數：`TW_ZONE = ZoneId.of("Asia/Taipei")`、`FILE_DATE = yyyyMMdd`、`TS_FMT = yyyy-MM-dd HH:mm:ss`；`AtomicBoolean ticking`。
- **HTTP（owner-scoped）**：
  - `getForCurrentUser()`：`findByOwnerUserId(ownerId)`，無則回預設值（不寫 DB）。
  - `updateForCurrentUser(SettingRequest)`：驗證 `runHour` 0..23、`runMinute` 0..59（越界擲 `IllegalArgumentException`）；`normalizeSubpath`（空→`input`）；`resolveDir(subpath)` 驗不跳脫基底；upsert 存檔。
  - `runNowForCurrentUser()`：`byte[] data = excelExportService.exportAssetTransactions();`（HTTP 情境，aspect 自動 owner-scope）→ `writeToDir(ownerId, subpath, data)` → 更新 `lastRunAt`/`lastRunStatus`（成功「成功：/path」、失敗「失敗：訊息」）→ **不動 `lastRunDate`（不影響當日排程 guard）**；回 `{path, sizeBytes}`。
- **背景排程**：
  - `@Scheduled(cron = "0 * * * * *", zone = "Asia/Taipei") tick()`：`ticking.compareAndSet` 防重入，呼叫 `runDueExports()`。
  - `@EventListener(ApplicationReadyEvent.class) selfHealOnStartup()`：呼叫 `runDueExports()`（開機補跑當日已到點未執行者）。
  - `runDueExports()`：`settingRepo.findAll()`（背景讀全部 owner 列），對每列 `enabled` 且 `today != lastRunDate` 且 `now >= LocalTime(runHour, runMinute)`（**非分鐘精確相等**）者呼叫 `runScheduled(s, today)`。
  - `runScheduled(s, today)`：`byte[] data = excelExportService.exportAssetTransactionsForOwner(s.getOwnerUserId());` → `writeToDir` → 成功記 log、失敗只記 `lastRunStatus`＋log（**單一 owner 失敗不影響其他 owner**）；**finally 一律設 `lastRunDate = today`、`lastRunAt = now`** 並存檔（成功或失敗都設當日 guard，避免命中分鐘後每 poll 重試整天）。
- **輔助**：
  - `requireOwnerId()`：`currentUserProvider.getObject()`，`!hasUser()` 擲 `UnauthenticatedException`（既有類，非 `IllegalStateException`），回 `getEffectiveUserId()`。
  - `normalizeSubpath(String)`：null/空 → `"input"`，否則 `trim()`。
  - `resolveDir(String subpath)`：`base = Path.of(baseDir).toAbsolutePath().normalize()`；`target = base.resolve(subpath).normalize()`；`if (!target.startsWith(base)) throw IllegalArgumentException`；回 target。
  - `writeToDir(Long ownerId, String subpath, byte[] data)`：`Files.createDirectories(dir)`；檔名 `"交易紀錄_" + ownerId + "_" + today.format(FILE_DATE) + ".xlsx"`；`Files.write(file, data)`；回 file path。（含 ownerId 避免多使用者同 subpath 同名互相覆蓋；同一使用者同日覆寫。）
  - `toResponse(...)`：組 `SettingResponse`（含 `baseDir` 供 UI 顯示落點）。

### 238.4 匯出來源（`ExcelExportService` 背景版）

在 `ExcelExportService` 新增（比照既有 `exportRealizedGainsForOwner`）：

```java
@Transactional(readOnly = true)
public byte[] exportAssetTransactionsForOwner(Long ownerId) throws IOException {
    entityManager.unwrap(Session.class)
            .enableFilter("ownerFilter")
            .setParameter("ownerId", ownerId);
    return buildAssetTransactionsWorkbook();   // 與 HTTP 版共用 t237 建立的同一支
}
```

- 與 t237 的 `exportAssetTransactions()` 共用同一個 `buildAssetTransactionsWorkbook()`，確保三入口（下載／run-now／排程）內容一致。

### 238.5 DTO

`backend/src/main/java/com/steven/assets/dto/AssetTransactionExportDto.java`（比照 `RealizedGainExportDto`）：

- `SettingResponse`（`@Builder`）：`enabled`、`runHour`、`runMinute`、`outputSubpath`、`lastRunAt`（`yyyy-MM-dd HH:mm:ss`，無則 null）、`lastRunStatus`、`baseDir`。
- `SettingRequest`：`enabled`、`runHour`、`runMinute`、`outputSubpath`。
- `RunNowResponse`（`@Builder`）：`path`、`sizeBytes`。
- 不定義 Browse／DirEntry——目錄瀏覽沿用既有 `GET /api/export-schedule/browse`。

### 238.6 Controller

`backend/src/main/java/com/steven/assets/controller/AssetTransactionExportController.java`，`@RestController @RequestMapping("/api/asset-transactions/export")`（比照 `RealizedGainExportController`）：

- `GET /schedule` → `service.getForCurrentUser()`。
- `PUT /schedule` → `service.updateForCurrentUser(req)`。
- `POST /run-now` → `service.runNowForCurrentUser()`。
- **路徑共存**：t237 的 `AssetTransactionController` 有 `GET /export`（＝ `/api/asset-transactions/export`）；本 controller 掛同前綴但一律帶子路徑（`/schedule`、`/run-now`），故無 ambiguous mapping。

### 238.7 BFF passthrough

`bff/.../transaction/TransactionBffController.java`（t237 已建）新增：

- `GET /api/bff/transaction/export/schedule` → business `GET /api/asset-transactions/export/schedule`
- `PUT /api/bff/transaction/export/schedule` → business `PUT /api/asset-transactions/export/schedule`
- `POST /api/bff/transaction/export/run-now` → business `POST /api/asset-transactions/export/run-now`
- `GET /api/bff/transaction/export/browse?subpath=` → business `GET /api/export-schedule/browse?subpath=`（**沿用 Requirement 34 既有端點，不在 business 新增第二支 browse**；URI template 展開，`subpath` 需 URL-encode）。
- 排程相關呼叫一律帶 `X-User-*` 讓 business owner-scope。

### 238.8 排程列表頁登錄（不可漏）

`bff/src/main/java/com/steven/assets/bff/schedulelist/SchedulePublicBffController.java` 的 `JOBS` 清單，在 business-services 區塊新增一筆（比照既有「已實現損益匯出」那筆）：

```java
new ScheduledJobDto(BUSINESS, "交易紀錄匯出", "每日匯出排程檢查",
        "每分鐘檢查各使用者的交易紀錄自動匯出設定，命中執行時間即產出 Excel 到指定目錄（Requirement 49）",
        "每分鐘", "0 * * * * *", TPE),
```

- **計數同步（勿硬抄舊數字，一律 `grep -c "new ScheduledJobDto(" ` 逐檔重新核對）**：加入本筆後，business 由現況 **16 → 17**、全清單由現況 **45 → 46**（external 維持 29）。注意現有程式碼與文件有**既存的計數漂移**須一併校正：`SchedulePublicBffController.java` 的 `// ===== business-services（16）=====` 區塊註解改 17，`:52`／`:194` 兩處「44 筆」實際已是 45、須改 46，`spec/design.md` line 115「共 45 筆 ＝ business 16 ＋ external 29」須改 46 ＝ 17 ＋ 29。（現況 45＝16＋29 已經過 grep 核實：交易雷達匯出於前一任務已入 JOBS，使 business 成 16、總數成 45，但當時漏改上述總數行，故本任務不可沿用「44／15」的舊字面。）**漏登錄或計數漂移是排程列表頁的固定缺陷類（Task 188／195／197／228 反覆修正此類）。**

### 238.9 前端排程卡

`frontend/src/views/TransactionView.vue`（t237 已建）新增「排程自動匯出」設定卡（比照 `RealizedGainView.vue` 的排程卡）：

- `el-switch` 啟用開關 ＋ `el-time-picker`（時:分）＋ 輸出資料夾 `el-input`（唯讀顯示）＋「選擇」鈕開 `el-tree` 懶載入資料夾選擇 dialog（`load` 呼叫 `GET /api/bff/transaction/export/browse`，逐層展開取相對子路徑）＋「儲存設定」＋「立即匯出到目錄」鈕 ＋ 上次執行時間／結果顯示（顯示 `baseDir` 幫助理解落點）。
- `frontend/src/api/index.js` 的 `transaction` 命名空間新增：`getExportSchedule()`、`updateExportSchedule(payload)`、`runExportNow()`、`browseDir(subpath)`。排程相關方法帶 `skipErrorToast: true`（前端自處理訊息，比照既有 realizedGain 排程方法）。
- 多 panel 載入（明細 ＋ 排程設定）一律 `Promise.allSettled` 並行。

### 238.10 測試

新增排程 service 測試（比照既有排程匯出 service 的測試風格與命名慣例，與實作同一支任務交付）至少覆蓋：

- `updateForCurrentUser`：時分越界（hour=24／minute=60）擲例外；`..` 或絕對路徑子路徑被 `resolveDir` 擋下；空子路徑正規化為 `input`。
- `runDueExports`：`enabled` 且 `now >= 設定時分` 且今日未跑 → 產檔並設 `lastRunDate=today`；`today == lastRunDate` 不重跑；停用列不跑；`now < 設定時分` 不跑。
- **租戶隔離**：背景走 `exportAssetTransactionsForOwner(ownerId)`（驗證有呼叫該 owner 版本，而非 HTTP 版 `exportAssetTransactions()`）。
- 單一 owner 匯出擲例外時，`lastRunStatus` 記「失敗：…」、仍設當日 guard，且**不影響**同批其他 owner 繼續產檔。
- `runNowForCurrentUser` 不改 `lastRunDate`。
- Mockito 於 Java 25 環境需 `-DargLine="-Dnet.bytebuddy.experimental=true"`（本機 JDK 21 則免）。

## 驗證

```bash
cd /Users/steven/Project/asset-management/.claude/worktrees/sad-shamir-9f7960
bash scripts/spec-check.sh
git diff --check

JAVA_HOME=$(/usr/libexec/java_home -v 21) PATH="$JAVA_HOME/bin:$PATH" \
  /usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test
/Users/steven/.nvm/versions/node/v22.21.0/bin/node \
  /Users/steven/.nvm/versions/node/v22.21.0/bin/npm --prefix frontend run build

# 共用 stack：從 main 的 worktree 重建，避免被其他 session 洗掉；JVM service 一律 --no-cache
cp /Users/steven/Project/asset-management/.env . 2>/dev/null || true
docker compose -p asset-management build --no-cache business-services bff frontend
docker compose -p asset-management up -d --no-deps --force-recreate business-services bff frontend
# 重建 business 換 IP，BFF 握舊 IP 會 500 且不自癒 → 一律 restart bff
docker compose -p asset-management restart bff
curl -s http://localhost:8080/actuator/health

# e2e（免 OAuth）：先建一筆交易，設排程時間為「當下＋1 分」，等到點確認落檔
docker exec asset-business-services sh -c \
  "curl -s -X PUT http://localhost:8080/api/asset-transactions/export/schedule \
     -H 'Content-Type: application/json' \
     -H 'X-User-Id: 1' -H 'X-User-Role: USER' -H 'X-User-Status: ACTIVE' \
     -d '{\"enabled\":true,\"runHour\":8,\"runMinute\":0,\"outputSubpath\":\"input\"}'"
# 立即匯出驗證路徑（不動當日 guard）
docker exec asset-business-services sh -c \
  "curl -s -X POST http://localhost:8080/api/asset-transactions/export/run-now \
     -H 'X-User-Id: 1' -H 'X-User-Role: USER' -H 'X-User-Status: ACTIVE'"
# 確認主機家目錄對應路徑產生 交易紀錄_1_<YYYYMMDD>.xlsx
ls -l /Users/steven/input/交易紀錄_1_$(date +%Y%m%d).xlsx
# 排程列表頁應出現「交易紀錄匯出」
curl -s http://localhost:8080/api/bff/schedule-list | grep -o '交易紀錄匯出'
```

部署後於前端「交易紀錄」頁排程卡：開啟排程、選資料夾、設時間、按「立即匯出到目錄」確認回傳落點路徑正確；把排程時間設為當下＋1 分，等到點後確認家目錄出現 `交易紀錄_{id}_{日期}.xlsx`，內容與手動下載一致（同一活頁簿、涵蓋全部年度）。另以第二個 `X-User-Id` 建交易並啟用排程，確認各自檔案只含自己的交易（背景 `enableFilter` 隔離生效）。

## 完成報告

（實作者做完後回填：實際改了哪些檔、測試與 e2e 輸出、排程實測落檔證據、租戶隔離驗證、與原計畫的偏差及原因。）
