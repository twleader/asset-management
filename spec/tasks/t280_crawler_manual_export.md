# [t280] 爬蟲資訊查詢頁補上手動匯出：「立即匯出」（只重產檔案）＋「立即抓取並匯出」（完整跑一輪），兩者都產 JSON ＋ Excel 兩份

**對應 Requirements:** Requirement 63（「爬蟲資訊查詢」頁補上手動匯出，兩顆按鈕分別為「只重產檔案」與「完整跑一輪」，兩者都同時產出 `.json` 與 `.xlsx` 兩份、都會在啟用時同步 Google Drive、都限 ADMIN）
**前置任務:** 無（t272 已把爬蟲的雙格式產出落地並在 main 上，本任務直接沿用其 `writePublicInfoXlsx` 與 `syncToGdrive` 兩份上傳，不再改動它們的行為）
**後續任務:** 無
**Liquibase changeset:** 無（本需求不新增／不修改任何資料表欄位，執行結果同步回傳前端、不落 DB）

---

## 背景

**現在的行為（缺口）：** `external-materials-service` 的 `NewsPoller` 只有兩個觸發途徑——

1. `crawler_schedule` 設定的時間點（`@Scheduled(cron = "0 * * * * *", zone = "Asia/Taipei")` 每分鐘 ticker，命中 `HH:mm` 即跑；seed 預設 08:20／11:30／18:00）；
2. 開機 `ApplicationReadyEvent` warmup。

沒有任何手動入口。**十個**有「輸出資料夾」設定的匯出頁裡（`grep -ranl "輸出資料夾" frontend/src/views/` 實測：`AssetHistoryView`／`CommodityPriceView`／`CrawlerDataView`／`ExchangeRateView`／`GdpTwseView`／`RealizedGainView`／`StockAlertView`／`TradingCalendarView`／`TradingRadarView`／`TransactionView`），**只有爬蟲資訊查詢頁完全沒有手動觸發**——其餘**八頁**有 `POST .../run-now`，交易日曆有 `POST /api/trading-calendar-export/run`。（**不要沿用 Requirement 51 的「九個…其中七頁」**：那組數字的母體是該需求自己界定的八頁推廣範圍、寫於警示觸發匯出頁〔Requirement 54〕落地之前，在該需求脈絡內正確，但不是全庫計數。）實際後果有二：（a）改完輸出資料夾或 Google Drive 設定後**無法立即驗證落點**，只能等下一個時間點或重啟容器靠 warmup——Requirement 50 上線時就是靠重啟容器才驗到；（b）臨時想要一份最新的公開資訊檔案時只能乾等。

**正確行為（本任務要做到的）：** 「爬蟲輸出檔案設定」卡加兩顆按鈕（限 ADMIN）：

| 按鈕 | 語意 | 走哪一段 | `trigger` 值 | 典型耗時 |
|---|---|---|---|---|
| **立即匯出** | 只重產檔案：**不抓取、不寫 `news_headline`**，由 DB 現有資料產出兩份檔案並（啟用時）同步 Drive | `exportPublicInfoJson(...)` | `manual-export` | < 1 秒（Drive 啟用時另加兩份上傳，各上限 45 秒） |
| **立即抓取並匯出** | 完整跑一輪：抓取 → upsert `news_headline` → 清理保留期外舊聞 → 產出兩份檔案 → Drive 同步 | `run(...)` | `manual` | 3～5 秒（最壞數分鐘） |

**端點命名鐵則：`fetch-and-` 前綴＝「會先重新抓」，未帶前綴＝「只重產檔案」，三層一致。** 全庫既有的八個 `POST .../run-now` 一律是「立即匯出到目錄、不重新抓資料」，故 ext 端那支完整輪**不得叫 `run-now`**——同一字串在 business 層與 ext 層反義時，接反是**靜默的**（兩支都產出同名的兩份檔，只差有沒有抓），編譯器與測試都攔不到，事後看 log 追查也會誤判。三層映射固定為：

| 語意 | 前端 → BFF | BFF → business | business → ext |
|---|---|---|---|
| 只重產檔案 | `POST /api/bff/crawler-data/export/run-now` | `POST /api/crawler-export-path/run-now?crawler=news-poller` | `POST /internal/news-poller/export-now` |
| 完整跑一輪 | `POST /api/bff/crawler-data/export/fetch-and-run-now` | `POST /api/crawler-export-path/fetch-and-run-now?crawler=news-poller` | `POST /internal/news-poller/fetch-and-export-now` |

**兩顆並存是使用者明示的決定，不得只做其中一顆。** 只做前者則「我要最新新聞」永遠得等排程；只做後者則單純驗證落點要付出一次完整抓取（十餘個來源序列抓取、各 15 秒 request timeout）的代價，而 `news_headline` 只有爬蟲會寫、不重抓則產出內容與上一輪完全相同。

**已經成立、本任務不得改動的事：** Requirement 55 / Task 272 已讓每輪爬取**同時產出** `public_info_<yyyy-MM-dd>.json` 與 `public_info_<yyyy-MM-dd>.xlsx`（主檔名相同、只差副檔名），Drive 啟用時兩份都上傳、狀態字串為 `xlsx …／json …`。**排程側的「兩種格式」已經成立**；本任務只新增手動入口，並讓手動入口共用同一段程式碼。

---

## 這些是現況事實（實作前不必再去查，但改動時必須守住）

### `external-materials-service` / `NewsPoller.java`

- `private final AtomicBoolean running = new AtomicBoolean(false);` —— warmup 與排程輪共用的唯一併發閘門。
- `private void runGuarded(String trigger)`：`if (!running.compareAndSet(false, true)) { log.info(...); return; }` → `try { run(trigger); } finally { running.set(false); }`。
- `private void run(String trigger)`：依序 `newsClient.fetchAll()`／`twseClient.fetchAll()`／`snapshotClient.fetchAll()`／`krIntradayClient.fetchAll()`／`maCrossClient.fetchAll()` 收集 `List<NewsRow>` → `stockFilter.retain(rows)` → 逐則 `source.upsertNews(...)` 累計 `ok`／`fail` → `source.deleteNewsOlderThan(...)` 得 `deleted` → `log.info` → **最後一行** `exportPublicInfoJson(trigger);`。
- `private void exportPublicInfoJson(String trigger)`：
  - `if (!exportEnabled) return;`（`@Value("${news-scraper.export-enabled:true}")`）
  - `LocalDate today = LocalDate.now(TW_ZONE)`；`Path writtenFile = null; Path xlsxFile = null;`
  - try 內：`resolveTradingCutoff(today)` → `source.loadTodayPublicInfoForExport(today, cutoff)` → `resolveExportDir()` → `Files.createDirectories(dir)` → 組 `Map<String,Object> payload`（`generatedAt`／`trigger`／`tradingDayCutoff`／`count`／`items`，**扁平結構、無 `metadata` 包裹層**）→ 同目錄 tmp ＋ `ATOMIC_MOVE` 寫 `public_info_<today>.json` → `writtenFile = file` → `xlsxFile = writePublicInfoXlsx(dir, today, payload, trigger)`。
  - catch：`log.warn("公開資訊輸出 JSON 失敗（{}）：{}", ...)`。
  - **try/catch 之外**：`syncToGdrive(writtenFile, xlsxFile, today, trigger);`
- `private Path writePublicInfoXlsx(Path dir, LocalDate today, Map<String,Object> payload, String trigger)`：`xlsxWriter.build(payload)` → 同目錄 tmp ＋ `ATOMIC_MOVE` 寫 `public_info_<today>.xlsx` → 回落點；**整段 graceful，失敗只 `log.warn` 並回 `null`**。
- `void syncToGdrive(Path localFile, Path xlsxFile, LocalDate today, String trigger)`（package-private，供測試直接呼叫）：讀 `exportPathQuery.gdriveConfig(CRAWLER_KEY)`；`!cfg.enabled()` 直接 return；`localFile == null`／`!gdriveUploader.isAvailable()`／`isInvalidGdriveSubpath(cfg.subpath())` 三者任一成立即寫「跳過…」狀態並 return；否則上傳 json 那一份 → 再視 `xlsxFile` 是否為 null 上傳 xlsx（null 記「跳過：本輪未產生 Excel」）→ `recordGdriveStatusQuietly(halfOf(xlsxStatus, "xlsx ") + "／" + halfOf(jsonStatus, "json "))`。
- `PublicInfoXlsxWriter.build(Map<String,Object> payload)` 產出的工作表「公開資訊」頂端有 metadata 鍵值列，`META_KEYS = List.of("generatedAt", "trigger", "tradingDayCutoff", "count")`——**`trigger` 值本來就會被帶進 xlsx，不必為此另外改任何東西**。

### `external-materials-service` / `InternalPriceController.java`

- class-level `@RequestMapping("/internal")`，目前 **33** 支（`grep -c "@PostMapping\|@GetMapping"` 實測）。全庫 ext 只有這一支 controller。
- 相依皆為 `private final` 欄位 ＋ Lombok `@RequiredArgsConstructor`；19 個欄位中 11 個用簡單類名（有 import）、**8 個較新的用完整套件名內聯宣告**（例 `private final com.steven.assets.externalmaterials.service.EtfNavPoller etfNavPoller;`）。兩種寫法沿用任一種皆可。

### `backend` / `CrawlerExportPathController.java`、`CrawlerExportPathService.java`、`CrawlerExportPathDto.java`

- Controller：`@RestController @RequestMapping("/api/crawler-export-path") @RequiredArgsConstructor`，注入 `CrawlerExportPathService service` 與 `CurrentUserContext currentUser`；已有 `@GetMapping get(...)` 與 `@PutMapping update(...)`，後者以 `if (!currentUser.isAdmin()) throw new AdminRequiredException();` 縱深防禦。兩者的 `crawler` 參數皆為 `@RequestParam(name = "crawler", defaultValue = CrawlerSchedule.CRAWLER_NEWS_POLLER) String crawler`。
- Service：建構子目前為 `CrawlerExportPathService(CrawlerExportSettingRepository repo, @Value("${EXPORT_OUTPUT_DIR:/home/steven}") String baseDir, GdriveOutputSupport gdrive)`。`update(...)` 標了 `@Transactional`。
- DTO：`public class CrawlerExportPathDto` 內含 `public record Response(...)` 與 `public record Request(String outputSubpath, Boolean gdriveEnabled, String gdriveSubpath)`。
- **既有測試 `backend/src/test/java/com/steven/assets/service/CrawlerGdriveOutputTest.java` 直接 `new CrawlerExportPathService(repo, "/home/steven", gdrive)`**——改建構子簽章一定會編譯失敗，必須同步更新。

### `backend` 端呼叫 ext 的既有慣例

`@Value("${external-materials.base-url:http://external-materials-service:8080}")`，全 backend 共 **6** 處（`FundNavController`／`DividendHistoryService`／`HistoricalDataService`／`MacroHistoryService`／`MarketDataService`／`PriceQueryService`）。**`FundNavController` 把 WebClient 建在 controller 裡，那是唯一的既有反例，不要照抄**——`spec/steering/structure.md` 的架構鐵則是 controller 純委派，另五處都在 service。

### `bff` / `SecurityConfig.java`、`CrawlerDataBffController.java`

- `SecurityConfig` 已有 `.pathMatchers(HttpMethod.PUT, "/api/bff/crawler-data/schedule")` 與 `.pathMatchers(HttpMethod.PUT, "/api/bff/crawler-data/export-path")` 兩條 `.hasAuthority(AuthConstants.AUTHORITY_ADMIN)`；**未列出的路徑落到 `anyExchange().authenticated()`**。
- `CrawlerDataBffController` 目前**只有 `GET`／`PUT`、沒有任何 `POST`**；`private static final String NEWS_POLLER = "news-poller";`；回應型別一律 `Mono<ResponseEntity<Map<String,Object>>>`（`MAP` 常數）或 `List<Map<String,Object>>`（`LIST_MAP`）。查詢與排程的 `GET` 有 `.onErrorReturn(...)` 降級，`export-path` 兩支刻意沒有。

### `bff` / `SchedulePublicBffController.java`

`JOBS` 共 **46** 筆（既有測試 `SchedulePublicBffControllerTest` 斷言此數）。其中「財經新聞抓取」那筆的 `description` 現值為：

```
"抓取財經新聞＋公開資訊快照；執行時間改由 DB 驅動，可於「爬蟲資訊查詢」頁增減多個時間點（Requirement 38）。"
+ "每輪輸出公開資訊，同時產出 JSON 與 Excel 兩份（主檔名相同）至本機設定資料夾，並於已啟用時同步上傳一份副本至 Google Drive"
+ "（Requirement 50；本機一律照寫，Drive 為附加副本、失敗不影響本機檔與入庫）"
```

**既有測試斷言「所有含 `Excel` 的 `description` 都必須含 `同時產出 JSON 與 Excel 兩份`」**，改寫時該子字串必須留著。

### `frontend`

- `frontend/src/api/index.js` 的 `bffApi.crawlerData` 目前有 `query`／`getSchedule`／`saveSchedule`／`getExportPath`／`saveExportPath`／`browseExportDir`／`browseGdriveExportDir`。其他頁的 run-now 慣例是 `api.post(path, null, { timeout: 60000, skipErrorToast: true })`。
- `frontend/src/views/CrawlerDataView.vue` 的「爬蟲輸出檔案設定」卡：`.path-row`（輸出資料夾 ＋ 選擇 ＋ 儲存設定）→ `.path-row.gdrive-row`（Drive 開關 ＋ 路徑 ＋ 選擇）→ 兩段 `.path-hint`。頁面已有 `auth.isAdmin`、`fetchData()`、`fetchExportPath()`、`apiErrorMessage`。

---

## 要做什麼

### 280.1 ext：`NewsPoller` 開兩個手動入口，共用既有那一段

- [ ] 280.1 新增 **public record `ManualRunResult`**（巢狀於 `NewsPoller`），欄位順序固定為：
      `String status, String mode, String jsonPath, Long jsonSizeBytes, String xlsxPath, Long xlsxSizeBytes,
      Integer upserted, Integer failed, Integer exported, String jsonGdrivePath, String xlsxGdrivePath,
      String gdriveStatus, String message`。
  - `status` ∈ `OK`（跑完了，**且本機 JSON 那一份確實寫成功**）／`FAILED`（跑了，但本機 JSON 寫檔失敗）／
    `BUSY`（`running` 已被持有、本次未啟動）／`DISABLED`（功能被關閉，兩顆看的開關不同，見 280.1.5／280.1.6）。
    **`RUNNING`／`ERROR` 由 business 端合成，ext 不產生這兩個值。**
  - **`FAILED` 不可省略。** 本機寫檔失敗是 `NewsPoller` 既有的 graceful 行為（`exportPublicInfoJson` 的
    catch 只 `log.warn`、不擲例外），若不另立狀態，「輸出子路徑不可寫／磁碟滿」時會回 `OK`、前端顯示綠色成功，
    落點欄卻是 `null`——而那正是這兩顆按鈕**最主要的使用情境**（驗證落點）會踩到的失敗。
    **判準必須是結構化的列舉**（見 280.1.1.1），語意為「`jsonPath == null` **且非
    `export-enabled=false` 早退**」；**不得比對訊息字串，也不得只看 `jsonPath == null`**——
    那會把 `DISABLED` 併吃進 `FAILED`（兩者的 `ExportOutcome` 形狀完全相同）。
  - `mode` ∈ `FETCH_AND_EXPORT`／`EXPORT_ONLY`——回應要能自證是哪一顆按鈕的結果。
  - `upserted`／`failed` **只有 `FETCH_AND_EXPORT` 有值**，`EXPORT_ONLY` 一律 `null`（不是 `0`——`0` 會被讀成
    「抓了但一筆都沒進」，與「根本沒抓」是不同的事）。
  - **雙路徑雙大小是硬要求**：`jsonPath`／`jsonSizeBytes`／`xlsxPath`／`xlsxSizeBytes` 四欄不得合併成一組。
    run-now 存在的理由就是驗證落點，只回一個路徑等於少驗一半（Requirement 63 明列）。
- [ ] 280.1.1 新增兩個 package-private record：
      `record ExportOutcome(ExportStatus outcome, String jsonPath, Long jsonSizeBytes, String xlsxPath,
      Long xlsxSizeBytes, Integer exported, String jsonGdrivePath, String xlsxGdrivePath,
      String gdriveStatus, String message)`
      與 `record GdriveOutcome(String jsonPath, String xlsxPath, String status)`。
      其餘欄位為 `null`＝該步驟沒做或失敗，**皆為既有的 graceful 行為，不是新語意**。
- [ ] 280.1.1.1 **`ExportOutcome` 的第一欄必須是結構化的分類，不得靠訊息字串分流。** 新增
      package-private `enum ExportStatus { OK, FAILED, DISABLED }`（或等價的具名欄位）。
      理由是可證的：「`export-enabled=false` 早退」與「寫檔失敗」產生的 `ExportOutcome` **形狀完全相同**
      （`jsonPath == null` ＋ 一段中文 `message`），靠比對訊息子字串分流會在任一方訊息措辭改動時靜默錯判——
      而這兩者在前端一個是灰色提示、一個是紅色錯誤。
- [ ] 280.1.2 `exportPublicInfoJson(String trigger)` 的回傳型別由 `void` 改為 `ExportOutcome`。
      **既有的每一個分支、順序與 log 一字不動**，只是把已經算出來的值多帶一份出去：
  - `!exportEnabled` 早退時回 `outcome = DISABLED` ＋
    `message = "公開資訊輸出已停用（news-scraper.export-enabled=false）"`，其餘欄位 `null`。
  - try 內成功後補 `Files.size(file)` 與 `items.size()`（**`Files.size` 必須包在既有 try 內**——它會擲
    `IOException`，逸出到方法外會把一次「檔案其實已寫成功」的匯出記成失敗）。
  - catch 內把訊息存進區域變數 `failure`，**`log.warn` 那一行不動**。
  - `outcome` 依 `writtenFile` 決定：非 null → `OK`；null（且非 `DISABLED` 早退）→ `FAILED`。
  - 末尾 `syncToGdrive(...)` 的回傳併入 `ExportOutcome` 後回傳。
- [ ] 280.1.3 `syncToGdrive(Path localFile, Path xlsxFile, LocalDate today, String trigger)` 的回傳型別由
      `void` 改為 `GdriveOutcome`，**簽章其餘部分與方法內每一個判斷、每一則 log、每一次
      `recordGdriveStatusQuietly(...)` 一字不動**。三條「絕不」保證（不 rollback 本機檔、不讓
      `news_headline` 入庫失敗、不擲例外中斷排程）必須原封不動——**回傳值只是把既有的狀態字串多帶一份出去**。
  - `cfg` 讀取失敗、`!cfg.enabled()` 兩個早退分支回 `new GdriveOutcome(null, null, null)`。
  - `skip != null` 分支回 `new GdriveOutcome(null, null, skip)`。
  - 正常分支回 `new GdriveOutcome(jsonDest, xlsxDestOrNull, <合併後的狀態字串>)`。
  - 外層 catch 分支回 `new GdriveOutcome(null, null, "失敗：" + e.getMessage())`。
- [ ] 280.1.4 `run(String trigger)` 的回傳型別由 `void` 改為 `ManualRunResult`（`mode = FETCH_AND_EXPORT`，
      帶上 `ok`／`fail` 與 `exportPublicInfoJson` 的結果）。**`runGuarded` 忽略回傳值**——排程輪與 warmup
      的行為必須與本任務之前**完全相同**。
- [ ] 280.1.5 新增 `public ManualRunResult fetchAndExportNow()`（完整跑一輪，供 ext 端點 `fetch-and-export-now`）。
      **方法名必須與端點名同義，不得叫 `runNow()`**——business 端那支叫 `runNow(...)` 的是「只重產檔案」
      （對應 `POST /api/crawler-export-path/run-now`），兩層同名反義正是上面「端點命名鐵則」要防的事，
      而 280.2 那兩行 controller 對映**沒有任何測試覆蓋**（280.6.1 驗的是 `exportNow()` 本身不抓取，
      不是路徑對映），寫反不會被任何斷言抓到。`run` 字樣在 ext 只留給內部的 `run(trigger)`：
  - `if (!enabled)` → 回 `status=DISABLED`、`mode=FETCH_AND_EXPORT`、
    `message="爬蟲已停用（news-scraper.enabled=false），未執行"`。
  - `if (!running.compareAndSet(false, true))` → `log.info("本地新聞抓取（manual）略過：上一輪尚未結束")`
    ＋ 回 `status=BUSY`、`message="上一輪抓取尚未結束，本次未啟動；請稍候再試"`。
  - 否則 `try { return run("manual"); } finally { running.set(false); }`。
- [ ] 280.1.6 新增 `public ManualRunResult exportNow()`（只重產檔案，供 ext 端點 `export-now`）：
  - **不看 `enabled`**——那是「要不要自動抓取」的開關，與「重產檔案」無關；照「兩顆都看兩個開關」實作
    會讓「爬蟲整體停用但仍想重產檔案」的情境被錯誤擋掉。
  - **`DISABLED` 一律由 `exportPublicInfoJson` 回傳的 `ExportOutcome.outcome` 決定**（`exportEnabled`
    的判斷已在該方法的第一行、單一來源），**不得在 `exportNow()` 內比對訊息字串、也不得複製一份
    `if (!exportEnabled)` 判斷**。
  - **同樣要取得 `running`**（取不到回 `BUSY`）。理由：它與排程輪寫的是**同一組檔名**，
    共用同一個閘門才不會出現「排程輪寫到一半、手動輪同時覆寫」的交錯情境。
  - 取得後 `try { ExportOutcome o = exportPublicInfoJson("manual-export"); … } finally { running.set(false); }`，
    把 `o.outcome()` 映成 `status`（`OK`／`FAILED`／`DISABLED`），組成 `mode=EXPORT_ONLY`、
    `upserted=null`、`failed=null` 的 `ManualRunResult`。
  - **絕不呼叫任何 fetch client、絕不呼叫 `source.upsertNews`／`source.deleteNewsOlderThan`。**
- [ ] 280.1.6.1 **`run(...)` 組 `ManualRunResult` 時同樣要把 `ExportOutcome.outcome` 映進 `status`**，
      不得一律填 `OK`——抓取成功但檔案寫不出去時，那一輪對使用者而言就是失敗的。
      **`enabled=true` 但 `export-enabled=false` 這一格因此有明確答案**：抓取與 upsert 照跑完
      （**不得跳過**），產檔那一步早退 → `outcome=DISABLED` → `status=DISABLED`，
      且 `upserted`／`failed` **有值**、`jsonPath`／`xlsxPath` 為 `null`，
      `message` 明示「抓取已完成 N 則，公開資訊輸出已停用（`news-scraper.export-enabled=false`）故未產檔」。
      **不得回 `OK`**（什麼檔都沒產生卻綠色成功），也**不得回 `FAILED`**（沒有東西失敗，是被設定關掉）。
- [ ] 280.1.7 **`fetchAndExportNow()` 與 `exportNow()` 各自做 `compareAndSet`，不得改寫 `runGuarded`**：後者是 warmup 與
      排程輪的入口，它「取不到旗標就只 log 並 return」的行為必須原封不動；手動輪則需要把「沒跑」這件事
      **回報給使用者**。三者共用同一個 `running` 欄位即達成互斥。
- [ ] 280.1.8 class javadoc 補一段：手動觸發是第三、四個入口，走同一段 `run()`／`exportPublicInfoJson()`，
      只有 `trigger` 標籤不同（新增 `manual`／`manual-export`）。

### 280.2 ext：`InternalPriceController` 開兩支端點

- [ ] 280.2 注入 `NewsPoller`，新增兩支（**命名必須照這裡寫的**，理由見上方「端點命名鐵則」）：
  - `@PostMapping("/news-poller/export-now")` → `newsPoller.exportNow()`（只重產檔案）
  - `@PostMapping("/news-poller/fetch-and-export-now")` → `newsPoller.fetchAndExportNow()`（完整跑一輪）
  **ext 端不得出現名為 `run-now` 的端點，也不得出現名為 `runNow()` 的公開方法。**
- [ ] 280.2.1 **兩支都不設自己的逾時上限**，javadoc 寫明理由：上游放棄等待不影響這一輪跑完與寫檔，
      逾時語意由 business 端負責（50 秒 → `RUNNING`）。**ext 端不得為配合上游而中斷抓取、縮短各來源
      request timeout 或縮短 Drive 上傳的 45 秒上限**——那會讓手動輪抓得比排程輪少、或讓 Drive 同步
      在手動路徑上比排程路徑更容易失敗。
- [ ] 280.2.2 controller 只做委派，**不得在 controller 內組裝任何回應欄位或做判斷**。

### 280.3 business：DTO ＋ service proxy ＋ controller

- [ ] 280.3 `CrawlerExportPathDto` 新增 `public record RunNowResponse(...)`，**欄位名稱與順序與 ext 的
      `ManualRunResult` 逐字相同**（`status, mode, jsonPath, jsonSizeBytes, xlsxPath, xlsxSizeBytes,
      upserted, failed, exported, jsonGdrivePath, xlsxGdrivePath, gdriveStatus, message`）——
      business 只是 proxy，欄位一對一才不會在這一層靜默吃掉欄位。
      javadoc 須列出 `status` 的**六**個值（`OK`／`FAILED`／`BUSY`／`RUNNING`／`DISABLED`／`ERROR`）與各自語意，
      並寫明「`RUNNING` **不是失敗**」以及「`FAILED`（跑到了、檔案沒寫成）與 `ERROR`（根本沒跑到 ext）是不同的事」。
- [ ] 280.3.1 `CrawlerExportPathService` 建構子加**兩個**參數：
      `@Value("${external-materials.base-url:http://external-materials-service:8080}") String externalUrl`、
      `@Value("${crawler.run-now.timeout-seconds:50}") long runNowTimeoutSeconds`，
      並於建構子內以 `WebClient.builder().baseUrl(externalUrl).build()` 建立 client 存進 `private final` 欄位。
      **不要注入 `WebClient.Builder`**——backend 現有 6 個呼叫 ext 的地方**全部**是靜態
      `WebClient.builder()`，全樹零處注入 `WebClient.Builder`（唯一一處在 bff 的 `WebClientConfig`）；
      注入版可以運作，但那是第 7 種寫法。
      **等待上限必須可由設定覆寫**，否則 280.6.7 的逾時測試得真的等 50 秒。
- [ ] 280.3.2 新增 `public RunNowResponse runNow(String crawlerKey)`（→ ext `/internal/news-poller/export-now`）
      與 `public RunNowResponse fetchAndRunNow(String crawlerKey)`（→ ext `/internal/news-poller/fetch-and-export-now`），
      兩者共用一支 private 方法，只差 ext 路徑與逾時訊息中的動作名稱。
- [ ] 280.3.2.1 **`crawlerKey` 不是 `CrawlerSchedule.CRAWLER_NEWS_POLLER` 時一律擲
      `IllegalArgumentException`（→ 400），不得靜默轉打 news-poller。** 這兩支的 proxy 目標是
      news-poller 專屬的 `/internal/news-poller/*`，參數形狀雖比照既有 GET／PUT，但那兩支會拿
      `crawlerKey` 去查 DB 列、本項不會——不驗就等於 `?crawler=whatever` 也會觸發爬蟲。
- [ ] 280.3.3 **逾時分支必須寫在 reactive chain 內**：
      `.timeout(Duration.ofSeconds(runNowTimeoutSeconds)).onErrorResume(TimeoutException.class, e -> Mono.just(running))`。
      理由：`Mono.timeout(Duration)` 送出的是 checked 的 `java.util.concurrent.TimeoutException`，而 `block()`
      會把它包成 `RuntimeException`——外層寫 `catch (TimeoutException)` 是**編譯錯誤**，寫 `catch (Exception)`
      則會**把逾時誤判為失敗**。逾時回 `status=RUNNING`、`message` 說明「這一輪會跑完並照常寫檔，請稍後重新整理」。
- [ ] 280.3.4 chain 之外的 `catch (Exception e)` 回 `status=ERROR`（只剩連線不通、5xx 等真正的失敗）。
- [ ] 280.3.5 **`runNow`／`fetchAndRunNow` 兩支刻意不加 `@Transactional`**：不能把數十秒的 HTTP 呼叫包進資料庫交易。
      在方法 javadoc 寫明此決定。
- [ ] 280.3.6 `CrawlerExportPathController` 新增兩支 `@PostMapping`：`/run-now` 與 `/fetch-and-run-now`，
      `crawler` 參數形狀比照既有兩支，**各自先 `if (!currentUser.isAdmin()) throw new AdminRequiredException();`**
      再委派 service。controller 內不得有其他邏輯。
- [ ] 280.3.7 **同步修正既有測試 `backend/src/test/java/com/steven/assets/service/CrawlerGdriveOutputTest.java`**
      的 `new CrawlerExportPathService(repo, "/home/steven", gdrive)` 呼叫（`:52`，全樹唯一的建構點；
      建構子簽章已變，不改編譯不過）。補入的兩個參數對該測試不會被用到，給預設 URL 與一個小數字即可。

### 280.4 bff：兩支 passthrough ＋ 授權 ＋ 排程列表文案

- [ ] 280.4 `CrawlerDataBffController` 新增兩支 `@PostMapping`：
      `/export/run-now` → business `POST /api/crawler-export-path/run-now?crawler=news-poller`；
      `/export/fetch-and-run-now` → business `POST /api/crawler-export-path/fetch-and-run-now?crawler=news-poller`。
- [ ] 280.4.1 **回應維持 `Mono<ResponseEntity<Map<String,Object>>>` 直通、不建 BFF 端 DTO 鏡像類**——
      鏡像類會多一處必須同步的欄位清單，漏一個欄位就在這一層被靜默吃掉（Task 245 已踩過同一個坑）。
- [ ] 280.4.2 **兩支都不做 `onErrorReturn` 降級**（與同檔的查詢／排程 `GET` 不同、與 `export-path` 一致）：
      手動觸發的失敗與「仍在背景執行」都必須讓使用者看見，降級成空物件會讓人誤以為成功。
- [ ] 280.4.3 `SecurityConfig` 為兩支 `POST` **各加一條** `.pathMatchers(HttpMethod.POST, "…").hasAuthority(AuthConstants.AUTHORITY_ADMIN)`，
      放在既有兩條 crawler-data `PUT` 規則之後。**漏加就會落到 `anyExchange().authenticated()`**（一般使用者
      也能觸發寫檔與 Drive 上傳）。
- [ ] 280.4.4 `SchedulePublicBffController` 的「財經新聞抓取」那筆 `description` 末尾補一句：
      `。亦可於「爬蟲資訊查詢」頁按「立即匯出」（只重產檔案）或「立即抓取並匯出」（完整跑一輪）手動觸發（Requirement 63）`。
      **`JOBS` 不新增也不移除項目（維持 46 筆）**——本任務不新增任何 `@Scheduled`。
      **改寫後該條仍須含 `同時產出 JSON 與 Excel 兩份` 這個確切子字串**，否則會打破 Requirement 55 已落地的
      `allMatch` 斷言（`bff/src/test/java/com/steven/assets/bff/schedulelist/SchedulePublicBffControllerTest.java`）。

### 280.5 前端

- [ ] 280.5 `frontend/src/api/index.js` 的 `bffApi.crawlerData` 新增兩支：
      `runExportNow: () => api.post('/bff/crawler-data/export/run-now', null, { timeout: 70000, skipErrorToast: true })`
      與 `fetchAndRunExportNow: () => api.post('/bff/crawler-data/export/fetch-and-run-now', null, { timeout: 70000, skipErrorToast: true })`。
      **`timeout` 取 70000 而非其他頁 run-now 慣用的 60000**：nginx `/api/` 的 `proxy_read_timeout` 為 60s、
      business 端 50s 就會回 `RUNNING`，把 axios 設在 nginx 之後才不會兩邊同時到期而分不清是誰斷的。
      `skipErrorToast`：`BUSY`／`RUNNING` 都不是失敗，訊息由本頁自己呈現。
- [ ] 280.5.1 `CrawlerDataView.vue`「爬蟲輸出檔案設定」卡在 Drive 那一列**之後**新增一列動作按鈕
      （`v-if="auth.isAdmin"`）：「立即匯出」與「立即抓取並匯出」，各有自己的 loading 旗標，
      **一顆在跑時另一顆停用**（避免使用者同時按兩顆而必然拿到一個 `BUSY`）。
- [ ] 280.5.2 兩顆的結果處理共用一支 handler。**任何分支都不得把 `null` 印進訊息**——這是本項的總則，
      下面每一條都是它的展開：
  - `OK` **且 `jsonPath` 與 `xlsxPath` 皆非 null** → `ElMessage.success`，訊息**同時列出兩個落點**與輸出筆數；
    完整跑一輪另列 upsert 筆數。
  - `OK` **但 `xlsxPath` 為 null** → **`ElMessage.warning`**，只列 JSON 那一個落點，並註明
    「Excel 這一份本輪未產出（不影響 SRPP 讀的 JSON）」。**這一格是必然會發生的**：280.6.3 明文要求
    「xlsx 產檔失敗時 JSON 仍寫出」，而 `status` 的判準只看 JSON（280.1.2），故 `OK ＋ xlsxPath=null`
    是被測試強制存在的狀態。照「OK 就印兩個落點」實作會印出「…／null」。
  - `OK` **但 `gdriveStatus` 非 null 且「**包含**」「失敗」或「跳過」** → 一律降為 **`ElMessage.warning`**
    並附上該字串。理由：`OK` 只代表本機那一份寫成功（280.6.4 明文要求 Drive 上傳失敗仍回 `OK`），
    若不呈現，Drive 上傳失敗在畫面上與完全成功**無法區分**。
    **判準必須是「包含」而不是「開頭」**——`NewsPoller.syncToGdrive` 的正常分支字串一律經
    `halfOf(xlsxStatus, "xlsx ") + "／" + halfOf(jsonStatus, "json ")` 組成，故
    **「xlsx 上傳失敗、json 成功」時字串是 `xlsx 失敗：…／json 成功：…`，並不以「失敗」開頭**，
    而此時 `xlsxPath` 又是非 null（本機那份有產出、只是沒上傳成功），也不會落進上一條的
    `xlsxPath === null` 分支——用「開頭」當判準，**最常見的那種部分失敗會顯示綠色成功**。
    只有「整批跳過」與外層 catch 兩個分支才是以「跳過」／「失敗」開頭，而那兩種本來就不是這條要抓的情境。
    全成功字串為 `xlsx 成功：<路徑>（N bytes）／json 成功：…`，不含這兩個詞，故「包含」不會誤判。
  - `BUSY`／`RUNNING`／`DISABLED` → **`ElMessage.warning`（不是 `error`）** ＋ 顯示後端給的中文 `message`。
    理由：這三種都不是「操作失敗」（沒啟動／還在背景跑／功能被關），紅色錯誤會讓使用者以為需要補救。
  - `FAILED`／`ERROR` → **`ElMessage.error`** ＋ 後端 `message`，且**不得列出落點**（那時落點是 `null`）。
  - HTTP 層真的擲例外時才 `ElMessage.error('… ' + apiErrorMessage(e))`。
- [ ] 280.5.3 兩顆結束後都呼叫 `fetchData()` 與 `fetchExportPath()` 刷新當日資料表格與設定卡
      （後者含「上次上傳」狀態）。**兩支的失敗都要 `.catch(() => {})` 吞掉**，不得覆蓋掉操作結果訊息。
- [ ] 280.5.4 卡片內補一段說明文字（`.path-hint`）講清楚兩顆的差別：「立即匯出」只重產檔案、不重新抓取，
      內容與上一輪相同、適合驗證落點；「立即抓取並匯出」完整跑一輪、會有最新新聞。兩者都會產出
      `.json` 與 `.xlsx` **兩份**、同日覆寫當天那一份，Drive 已啟用時兩份都會上傳。上一輪還在跑時會提示
      「尚未結束」並略過，不會同時跑兩輪。

### 280.6 測試

- [ ] 280.6 **ext：`running` 已被持有時兩顆都不啟動第二輪。** 先讓 `running` 被佔住（以另一條執行緒進入
      `fetchAndExportNow()` 並卡在抓取替身裡，或直接以既有的 package-private 入口佈局），再分別呼叫 `fetchAndExportNow()`／
      `exportNow()`，斷言 (1) 回 `status=BUSY`、(2) **所有 fetch client 替身零互動**、
      (3) **輸出目錄未產生任何檔案**。
      **只斷言回傳 `BUSY` 是假綠燈**——在「根本沒有互斥」的實作下也會偶然通過。
- [ ] 280.6.1 **ext：「只重產檔案」真的不抓取。** 呼叫 `exportNow()`，斷言
      `verify(newsClient, never()).fetchAll()`（`twseClient`／`snapshotClient`／`krIntradayClient`／
      `maCrossClient` 同）、`verify(source, never()).upsertNews(...)`、
      `verify(source, never()).deleteNewsOlderThan(any())`，
      **而 `.json` 與 `.xlsx` 兩份檔案仍照常產出**。這是兩顆按鈕語意差異的唯一探針。
- [ ] 280.6.2 **ext：trigger 標籤。** `fetchAndExportNow()` 產出的 JSON 頂層 `trigger` 為 `manual`、`exportNow()` 為
      `manual-export`；**且同一個值出現在 xlsx「公開資訊」工作表的 metadata 區**（`PublicInfoXlsxWriter`
      的 `META_KEYS` 已含 `trigger`，這條驗的是它確實被帶過去）。
- [ ] 280.6.3 **ext：雙格式與順序守門在手動路徑成立。**
  - 兩份都產出，且**去掉副檔名後的主檔名逐字元相同**；
  - xlsx 產檔失敗（讓 `PublicInfoXlsxWriter.build` 擲例外）時 **JSON 仍寫出、仍上傳 Drive**，
    `ManualRunResult.xlsxPath` 為 `null` 而 `jsonPath` 有值，方法未擲例外；
  - JSON 寫失敗時 **xlsx 不寫**（Requirement 55 的唯一具名例外）。
- [ ] 280.6.4 **ext：Drive 兩份。** 以 `GdriveUploader` 替身斷言手動路徑上傳**兩次**、`subpath` 相同、
      檔名分別為 `public_info_<日期>.json` 與 `public_info_<日期>.xlsx`；狀態字串符合既有
      `xlsx …／json …` 契約。並斷言 Requirement 50 的三條「絕不」保證不因手動途徑而改變：
      上傳擲例外時 (1) 本機兩份檔仍在、(2) 方法未擲例外、(3) `ManualRunResult` 仍回 `status=OK`
      （本機成功、Drive 失敗是**可分辨的正常狀態**，不得記成整體失敗）。
- [ ] 280.6.4.1 **ext：「xlsx 上傳失敗、json 上傳成功」時 `gdriveStatus` 為 `xlsx 失敗：…／json 成功：…`。**
      斷言該字串**不以「失敗」開頭**但**包含**「失敗」——這是 280.5.2 那條「判準必須是包含而非開頭」的探針，
      沒有它，前端把最常見的部分失敗顯示成綠色成功不會被任何測試抓到。
- [ ] 280.6.5 **ext：既有排程／warmup 路徑零回歸。** 既有的
      `external-materials-service/src/test/java/com/steven/assets/externalmaterials/service/NewsPollerGdriveSyncTest.java`
      直接呼叫 package-private 的 `syncToGdrive(...)`（**11 個呼叫點**，皆為 statement 或
      `assertThatCode(() -> poller.syncToGdrive(...))`；另有 1 處 javadoc `{@link}` 不受影響）。
      **把回傳型別由 `void` 改為 `GdriveOutcome` 不會讓該檔編譯失敗**——Java 允許丟棄回傳值，
      而 expression lambda 的 body 是 method invocation（statement expression）故仍 void-compatible
      （JLS 15.27.2）；測試與 `NewsPoller` 同套件，`GdriveOutcome` 即使是 package-private 也存取得到。
      **所以不要「因為必須改」而去動它。** 在該檔補驗 `GdriveOutcome` 的狀態字串屬**選作**；
      若要補，一律**只加不減**（既有斷言一字不減）。實作後仍須跑一次 ext 全測試確認零回歸。
- [ ] 280.6.5.1 **ext：本機寫檔失敗回 `FAILED` 而非 `OK`。** 讓輸出目錄不可寫（或讓寫檔擲 `IOException`），
      斷言兩顆按鈕都回 `status=FAILED`、`jsonPath` 為 `null`、方法未擲例外、且**前端不會拿到 `OK`**。
      **這是 280.1／280.1.1.1 的唯一探針**——沒有它，一次什麼檔都沒產生的匯出會顯示綠色成功。
      另補一條：`news-scraper.export-enabled=false` 時 `exportNow()` 回 `DISABLED`（**不是 `FAILED`**），
      驗證兩者確實由結構化欄位分流而非訊息字串。
- [ ] 280.6.6 **backend：非 ADMIN 呼叫回 403。** 兩支各一條（`CurrentUserContext.isAdmin()` 回 false 時
      擲 `AdminRequiredException`），並斷言**此時完全沒有呼叫 ext**（權限檢查必須在 proxy 之前）。
      另補一條：`crawler` 參數非 `news-poller` 時擲 `IllegalArgumentException`（→ 400）且**沒有呼叫 ext**
      （280.3.2.1 的探針）。
- [ ] 280.6.7 **backend：逾時回 `RUNNING` 而非 `ERROR`。** 兩條：
  - **逾時**：`runNowTimeoutSeconds` 傳 1，`externalUrl` 指向一個**只 bind、不 accept 的
    `new ServerSocket(0)`**（TCP 三次握手由核心完成，client 送出請求後永遠等不到回應），
    斷言 `status=RUNNING`、`message` 含「背景」字樣。
  - **連線失敗**：`externalUrl` 指向一個**未監聽**的埠，斷言 `status=ERROR`。
  **這兩條是 280.3.3「逾時分支必須寫在 chain 內」的唯一探針**——寫成 `catch (Exception)` 的壞實作
  會讓第一條變成 `ERROR`。
- [ ] 280.6.7.1 **上面那個機制是刻意指定的，不要換成 HTTP stub。** 全樹**沒有任何 HTTP stub 基礎建設**
      （三個 `pom.xml` 皆無 MockWebServer／WireMock；backend 36 支 service 測試零支碰 `WebClient`），
      而 280.3.1 改用靜態 `WebClient.builder()` 之後也沒有 `WebClient.Builder` 可注入替身。
      裸 `ServerSocket` 零依賴、且精確重現「連得上但不回應」＝逾時的真實情境。
      若實作時仍覺得需要注入接縫，**允許**在 `CrawlerExportPathService` 另開一個
      **package-private 測試用建構子多載**接受已建好的 `WebClient`（正式建構子維持靜態
      `WebClient.builder()`、不破壞既有 6 處慣例）——**但不得把正式建構子改成注入 `WebClient.Builder`**。
- [ ] 280.6.8 **測試一律以替身注入、不實際連網、不碰真 DB**（沿用本專案既有慣例）。
      **新測試類的識別字刻意不寫進本任務檔**——`scripts/spec-check.sh` 的 B5 會把「spec 提到但全樹
      不存在的 `Test` 結尾類名」判為 BLOCK，而全新元件必然先有 spec 後有測試檔。上面點名的
      `CrawlerGdriveOutputTest`／`NewsPollerGdriveSyncTest`／`SchedulePublicBffControllerTest` 三支
      都是**既有檔案**，不受此限。

---

## 驗證

三個 Maven 專案的測試（`-DextraArgLine` **不可寫成 `-DargLine`**——後者會覆蓋掉時區設定而讓上百個測試變成 error，錯誤訊息還會偽裝成 byte-buddy 問題）：

```bash
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f external-materials-service/pom.xml test -DextraArgLine=-Dnet.bytebuddy.experimental=true
```

```bash
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test -DextraArgLine=-Dnet.bytebuddy.experimental=true
```

```bash
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f bff/pom.xml test -DextraArgLine=-Dnet.bytebuddy.experimental=true
```

前端建置：

```bash
cd frontend && /Users/steven/.nvm/versions/node/v22.21.0/bin/node ./node_modules/.bin/vite build
```

排程列表頁文案未打破 Requirement 55 的判準（應為 **0**）：

```bash
grep -E 'Excel|輸出公開資訊 JSON' bff/src/main/java/com/steven/assets/bff/schedulelist/SchedulePublicBffController.java | grep -vc '同時產出 JSON 與 Excel 兩份'
```

ext 端點總數應為 **35**：

```bash
grep -c "@PostMapping\|@GetMapping" external-materials-service/src/main/java/com/steven/assets/externalmaterials/controller/InternalPriceController.java
```

未新增任何 `@Scheduled`（應為 **0**）與任何 changeset（應為 **0**）：

```bash
git diff origin/main --unified=0 -- backend bff external-materials-service | grep -c '^+.*@Scheduled'
```

```bash
git diff --name-only origin/main -- backend/src/main/resources/db/changelog | wc -l
```

> changelog 的實際位置是 `backend/src/main/resources/db/changelog/`，**不是** repo 根目錄的 `db/`
> （根目錄的 `db/` 只有 `schema.sql`）。寫成 `-- db/changelog` 會因為 pathspec 匹配不到任何檔而**恆為 0**，
> 看起來永遠通過、實際什麼都沒檢查。

建置與部署（四個服務都動到；**JVM 服務一律 `--no-cache`**，cached build 可能不含你的變更而前端卻有，症狀是 gateway 404）：

```bash
cp /Users/steven/Project/asset-management-main/.env .env
```

```bash
docker compose -p asset-management build --no-cache business-services external-materials-service bff frontend
```

```bash
docker compose -p asset-management up -d --no-deps --force-recreate business-services external-materials-service bff frontend
```

```bash
docker compose -p asset-management restart bff
```

> **最後那行 `restart bff` 不可省略**：recreate `business-services` 會換 IP，BFF 握著舊 IP 會回 500 且
> **≥3 分鐘不自癒**（Docker DNS TTL 600s）；business log 會很乾淨，錯只出現在 bff log 的 Connection refused。

端到端實測——「只重產檔案」（秒回，且 `news_headline` 筆數不應改變）：

```bash
docker exec asset-business-services curl -s -X POST -H 'X-User-Id: 1' -H 'X-User-Role: ADMIN' -H 'X-User-Status: ACTIVE' 'http://localhost:8080/api/crawler-export-path/run-now?crawler=news-poller'
```

端到端實測——「完整跑一輪」：

```bash
docker exec asset-business-services curl -s -X POST -H 'X-User-Id: 1' -H 'X-User-Role: ADMIN' -H 'X-User-Status: ACTIVE' 'http://localhost:8080/api/crawler-export-path/fetch-and-run-now?crawler=news-poller'
```

ext 兩支端點確實以 `export-now`／`fetch-and-export-now` 命名，且**沒有**名為 `run-now` 的（第一行應為 2、第二行應為 0）：

```bash
grep -c 'news-poller/export-now\|news-poller/fetch-and-export-now' external-materials-service/src/main/java/com/steven/assets/externalmaterials/controller/InternalPriceController.java
```

```bash
grep -c 'news-poller/run-now' external-materials-service/src/main/java/com/steven/assets/externalmaterials/controller/InternalPriceController.java
```

兩份檔案確實成對落地（應看到同主檔名的 `.json` 與 `.xlsx`，且無 `.tmp` 殘留）。**落點目錄一律由設定即時查出、不得寫死**——`Project/SRPP/data/input` 只是 Liquibase seed 值，`output_subpath` 是使用者可於頁面修改的 DB 欄位（t272 的實測落點就已是 `/home/steven/input/`），寫死會讓 `ls` 空手而回、被誤讀成「功能沒作用」：

```bash
DIR=$(docker exec asset-business-services curl -s -H 'X-User-Id: 1' -H 'X-User-Role: ADMIN' -H 'X-User-Status: ACTIVE' 'http://localhost:8080/api/crawler-export-path?crawler=news-poller' | sed -n 's/.*"absolutePath":"\([^"]*\)".*/\1/p'); echo "落點：$DIR"; docker exec asset-external-materials-service sh -c "ls -l '$DIR' | grep public_info"
```

`trigger` 標籤確實寫進檔案（應印出 `manual` 或 `manual-export`）：

```bash
DIR=$(docker exec asset-business-services curl -s -H 'X-User-Id: 1' -H 'X-User-Role: ADMIN' -H 'X-User-Status: ACTIVE' 'http://localhost:8080/api/crawler-export-path?crawler=news-poller' | sed -n 's/.*"absolutePath":"\([^"]*\)".*/\1/p'); docker exec asset-external-materials-service sh -c "grep -o '\"trigger\" *: *\"[a-z-]*\"' '$DIR/public_info_$(date +%F).json'"
```

非 ADMIN 應被擋——**兩層要分開驗，這支打的是 business 的縱深防禦**（`CrawlerExportPathController` 內的 `currentUser.isAdmin()`，與 BFF `SecurityConfig` 無關）：

```bash
docker exec asset-business-services curl -s -o /dev/null -w '%{http_code}\n' -X POST -H 'X-User-Id: 2' -H 'X-User-Role: USER' -H 'X-User-Status: ACTIVE' 'http://localhost:8080/api/crawler-export-path/run-now?crawler=news-poller'
```

BFF 那一層（`SecurityConfig` 的 `hasAuthority(ADMIN)`）需另外驗——BFF 走 OAuth session、容器內 curl 帶不出已登入身分，故改為**靜態確認兩條規則都在**（應為 2）：

```bash
grep -c 'pathMatchers(HttpMethod.POST, "/api/bff/crawler-data/export/' bff/src/main/java/com/steven/assets/bff/config/SecurityConfig.java
```

---

## 完成報告

**狀態：已完成並部署驗證。** 兩顆按鈕端到端可用，兩份檔案與兩份 Drive 副本皆實際落地。

| 檔案 | 改動 |
|---|---|
| ext `NewsPoller.java` | 新增 `ManualRunResult`／`ExportOutcome`／`GdriveOutcome` 三個 record ＋ `ExportStatus` 列舉；新增 `exportNow()`／`fetchAndExportNow()` 兩個公開入口（各自 `compareAndSet`，`runGuarded` 未動）；`run`／`exportPublicInfoJson`／`syncToGdrive` 只改回傳型別，既有分支、順序、log、`recordGdriveStatusQuietly` 一字未動；新增 `sizeOrNull` 工具 |
| ext `InternalPriceController.java` | 新增 `POST /internal/news-poller/export-now` 與 `/fetch-and-export-now`（33 → 35 支），純委派 |
| backend `CrawlerExportPathDto.java` | 新增 `RunNowResponse`（13 欄，與 ext `ManualRunResult` 逐字同名同序） |
| backend `CrawlerExportPathService.java` | 建構子加 `externalUrl`／`runNowTimeoutSeconds` 兩參數（靜態 `WebClient.builder()`）；新增 `runNow`／`fetchAndRunNow` 與共用的 `proxyManualRun`（chain 內 `onErrorResume(TimeoutException)` → `RUNNING`；crawlerKey 驗證 → 400；無 `@Transactional`） |
| backend `CrawlerExportPathController.java` | 新增兩支 `@PostMapping`，各自 `isAdmin()` 縱深防禦後委派 |
| bff `CrawlerDataBffController.java` | 新增兩支 `@PostMapping` passthrough（本檔首次出現 `POST`），`Map` 直通、不做 `onErrorReturn` |
| bff `SecurityConfig.java` | 兩支 `POST` 各加一條 `hasAuthority(ADMIN)` |
| bff `SchedulePublicBffController.java` | 「財經新聞抓取」description 補手動觸發說明；`JOBS` 仍 46 筆 |
| frontend `api/index.js`、`CrawlerDataView.vue` | 兩支 API（timeout 70000）＋設定卡底部動作列兩顆按鈕＋說明文字＋結果分類呈現 |
| 新測試 ×2 | ext 手動匯出 15 條、backend proxy 5 條 |
| 既有測試 ×1 | `CrawlerGdriveOutputTest` 同步新建構子簽章（`NewsPollerGdriveSyncTest` **未動**——回傳型別改變不影響既有的丟棄式呼叫） |

**驗證輸出：**

- 單元測試：ext **174**／backend **497**／bff **21**，全部 0 failures 0 errors；`vite build` 通過。
- 機械檢查：排程列表頁未帶新措辭條數 **0**；ext 端點 **35** 支、`export-now`＋`fetch-and-export-now` **2** 支、`news-poller/run-now` 殘留 **0**；新增 `@Scheduled` **0**；changeset 變更 **0**；BFF `SecurityConfig` 兩條 POST 規則 **2**。
- `arch-auditor`：**0 critical、0 major、0 minor**。
- 端到端（四個服務 `--no-cache` 重建 ＋ `--force-recreate` ＋ `restart bff`）：
  - 「立即匯出」回 `OK`／`EXPORT_ONLY`，`jsonSizeBytes=94388`、`xlsxSizeBytes=35764`、`exported=268`、**`upserted`／`failed` 為 null**（確實沒抓），Drive 兩份皆成功，約 6.9 秒（幾乎全花在兩次 rclone 上傳）。
  - 「立即抓取並匯出」回 `OK`／`FETCH_AND_EXPORT`，`upserted=330`／`failed=0`，Drive 兩份皆成功，約 25 秒。
  - 落點 `/home/steven/Project/SRPP/data/input/` 有同主檔名的 `.json` ＋ `.xlsx`、**無 `.tmp` 殘留**；檔內 `"trigger" : "manual"`。
  - 非 ADMIN → **403**；`?crawler=whatever` → **400**；運行中 jar 含兩支新端點；前端 bundle 含 `crawler-data/export/fetch-and-run-now`。
  - **互斥閘門實機驗到**：ext 剛 recreate、warmup 那一輪還在跑時按下按鈕，回 `BUSY` 且 log 記下「公開資訊輸出（manual-export）略過：上一輪尚未結束」。

**與原計畫的偏差及原因：**

1. **`Files.size(...)` 改走 `sizeOrNull` 包起來**（`arch-auditor` 查證後修正）。原本直接把兩行 `Files.size` 插進 `exportPublicInfoJson` 既有的 try 內，若 JSON 那次取值擲 `IOException`，**`writePublicInfoXlsx` 整段會被跳過**——JSON 已寫成功卻不產 xlsx，破壞 Requirement 55 的雙格式保證；xlsx 那次擲出則會讓一次其實全部成功的匯出被記成「本機寫檔失敗」。取檔案大小只是回報用的附加資訊，不該改變控制流。
2. **按鈕位置放在設定卡底部（以分隔線與設定區隔開），而非緊接在 Drive 那一列之下**。原構想的位置會把 Drive 開關與它的說明文字拆開。
3. **`NewsPollerGdriveSyncTest` 未修改**——任務檔 280.6.5 已先行更正過這個判斷（回傳型別由 `void` 改為 `GdriveOutcome` 不會讓既有的丟棄式呼叫編譯失敗），實跑確認該檔零回歸。
4. **docker build 中途卡在拉 `node:22-alpine`**（約 20 分鐘無進展，非本次程式碼問題）。先 `docker pull node:22-alpine` 補齊 base image 後單獨重建 frontend 即通過。
