# [t283] 交易雷達頁首「匯出 Excel」在下載之外，同時落一份 JSON ＋ Excel 到伺服器輸出目錄

**對應 Requirements:** Requirement 48（今日交易雷達結果快照落地 Redis 與 Excel 區間匯出；本次追加「頁首匯出按鈕同時寫兩份到伺服器目錄」）＋ Requirement 55（所有自動匯出一律同時產出 JSON 與 Excel 兩份，主檔名相同；本次沿用其「一次查詢 → 一份 doc → 兩種格式」的硬約束）
**前置任務:** 無（t269–t272 已把雙格式與 `DualFormatExportWriter` 落地並在 main 上；t280 已為爬蟲頁補手動匯出；t282 已修九頁 run-now 的提示文案。本任務是同一類缺口的第三處，直接沿用既有元件，不改動它們）
**Liquibase changeset:** 無（零 DB schema 變更）

## 背景

### 使用者回報與實測

使用者 2026-08-02 回報「交易雷達也一樣，json 只匯到 google drive 沒有匯到 data/input」，隨後補充「我才匯出的」。逐項查證後定位到：**排程那條路沒壞，壞的是頁首那顆按鈕**。

實測（2026-08-02 17:50 那輪排程之後）三個位置**都有** `交易雷達_1_20260802.json`（284,296 bytes）與 `.xlsx`（36,988 bytes）：`/Users/steven/Project/SRPP/data/input`、`/Users/steven/Project/srpp-data`、Google Drive `投資理財/資產管理`。狀態欄亦記兩份皆成功。

真正的缺口是本頁的**兩個手動入口行為不一致**：

| 入口 | 前端位置 | 走的後端路徑 | 產出 |
|---|---|---|---|
| 排程時間點（該 owner 目前設為 09:10／11:45；時點存於 `trading_radar_export_time`，可於頁面調整）與排程卡的**「立即匯出到目錄」** | `TradingRadarView.vue:360` `runExportNow` → `POST /export-schedule/run-now` | `TradingRadarExportScheduleService.writeDailyExport` → `DualFormatExportWriter` | **xlsx ＋ json 兩份**，寫輸出目錄 ＋（啟用時）Drive ✓ |
| 頁首的**「匯出 Excel」** | `TradingRadarView.vue:9` `openExport` → `:958` `onExport` → `bffApi.tradingRadar.exportExcel(start, end)` | `GET /api/trading-radar/export` → `TradingRadarExportService.export(from, to)` | **只有單一 xlsx** 的 byte[] 供瀏覽器下載；**不產 json、不寫任何目錄、不碰 Drive** ✗ |

使用者按了頁首那顆之後在 `data/input` 找不到 json，就是這個缺口。這與 t280（爬蟲頁補手動匯出）、t282（九頁 run-now 提示說謊）是**同一類缺口的第三處**。

### 本任務推翻了先前兩處的明文約束（README 鐵則要求寫明）

- `spec/tasks/t231_trading_radar_scheduled_export.md:61` 的約束總則：「**不得取代或改動 t230 既有的手動瀏覽器下載**（端點 `GET /api/trading-radar/export?from&to` 與前端「匯出 Excel」鈕維持原樣）。」
- `spec/tasks/t260_radar_recompute_before_export.md:108`：「**不改手動匯出**（前端「匯出 Excel」對話框、`GET /api/trading-radar/export`）。」

**推翻的部分**：端點 method 由 `GET` 改為 `POST`，且該按鈕新增「同時落一份 json ＋ xlsx 到伺服器目錄」的副作用。
**保留的部分**：**下載體驗完全不變**（仍是 `showSaveFilePicker` 自選位置、仍只下載一個 `.xlsx`、檔名格式不變、按取消仍不顯示訊息）。
**為什麼推翻**：t231／t260 寫下該約束時，Requirement 55 的雙格式尚未存在（t269–t272 是之後才落地）；當時「手動下載只給 xlsx」不是缺口。R55 落地後，本頁兩個手動入口的產出才變成不一致，而使用者明確要求補上。

### 使用者已拍板的作法（三選一中的第 3 案）

已明示選擇「**兩者都要**」：**下載仍給 xlsx**（既有 `showSaveFilePicker` 自選位置的體驗完全不變），**另外**同時寫一份 `.json` ＋ `.xlsx` 到該 owner 設定的伺服器輸出目錄。

已被排除的兩案（不得回頭實作）：
- 「下載兩個檔／打包 zip」——連續兩次 save picker 或 zip 都改變既有下載體驗。
- 「改成只寫目錄、不下載」——使用者仍要當場拿到檔。

### 現況重點（改動前請先確認這些仍屬實）

- `backend/src/main/java/com/steven/assets/controller/TradingRadarController.java`
  - `:70` `@GetMapping("/export")`，簽章 `public ResponseEntity<ByteArrayResource> export(@RequestParam String from, @RequestParam String to) throws IOException`。
  - 內容：`byte[] data = exportService.export(from, to);` → 檔名 `"交易雷達_" + compact(from) + "_" + compact(to) + ".xlsx"` → `ContentDisposition.attachment().filename(filename, UTF_8)` → content-type `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet` → `contentLength(data.length)` → `body(new ByteArrayResource(data))`。
  - `:87` private static `compact(String iso)`：`iso.replaceAll("[^0-9]", "")` 後取前 12 碼。
  - 同檔 `:127` 已有 `@PostMapping("/export-schedule/run-now")` → `exportScheduleService.runNow()`。
  - 已注入 `TradingRadarExportService exportService`（`:44`）與 `TradingRadarExportScheduleService exportScheduleService`（`:45`）。
- `backend/src/main/java/com/steven/assets/service/TradingRadarExportService.java`
  - `export(String from, String to)`：`parseEpoch` 兩個參數（格式錯或 `from > to` 擲 `IllegalArgumentException` → `GlobalExceptionHandler` 轉 400）→ 取 `currentUserContext.getEffectiveUserId()`（**可能為 null**，此時用空 `SnapshotRange` 而非查 store）→ `excelDocRenderer.render(radarDoc(range, from, to))`。
  - `public ExportDoc radarDoc(long ownerId, long fromEpoch, long toEpoch)`：`radarDoc(store.range(...), isoLocal(from), isoLocal(to))`。
  - private `parseEpoch(String)`／`isoLocal(long)`／`radarDoc(SnapshotRange, String, String)` 皆存在。
  - 已注入 `TradingRadarSnapshotStore store`、`CurrentUserContext currentUserContext`、`ExcelDocRenderer excelDocRenderer`。**未**注入 `JsonDocRenderer` 與 `DualFormatExportWriter`。
- `backend/src/main/java/com/steven/assets/service/export/DualFormatExportWriter.java`
  - `public DualResult write(Long ownerUserId, Path dir, String baseName, byte[] jsonBytes, byte[] xlsxBytes, boolean gdriveEnabled, String gdriveSubpath) throws IOException`
  - `record DualResult(Path jsonFile, Path xlsxFile, String localStatus, String gdriveStatus, String xlsxGdrivePath, String jsonGdrivePath)`
  - 行為：`validateBaseName` → `Files.createDirectories(dir)` → 各自 tmp ＋ `ATOMIC_MOVE` 寫 `.xlsx`／`.json`；**bytes 為 null 代表該份 render 失敗、跳過該份照寫另一份、不擲例外**；只有 `dir` 建不出來或 `baseName` 不合法才擲。**兩份都成功才上傳 Drive**（`gdriveEnabled && xlsxFile != null && jsonFile != null`）。**本任務不改這支檔。**
- `backend/src/main/java/com/steven/assets/service/TradingRadarExportScheduleService.java`
  - private `resolveDir(String subpath)`、private `currentSubpath(long ownerId)`（讀 `trading_radar_export_setting.output_subpath`，缺列時回 `TradingRadarExportSetting.DEFAULT_SUBPATH`）、`FILE_DATE`（`yyyyMMdd`）、`TW_ZONE`。
  - `writeDailyExport(...)` 內組 `baseName = "交易雷達_" + ownerId + "_" + today.format(FILE_DATE)`，並以 `cfg.isGdriveEnabled()`／`cfg.getGdriveSubpath()` 餵 `dualWriter.write(...)`。
  - private `recordStatus(long, String)`（`:439`，寫 `lastRunAt`／`lastRunStatus`）、`applyGdriveStatus(long, DualFormatExportWriter.DualResult)`（`:473`）與 `syncGdrive(long, Path, String)`（`:487`）——**後兩支才是寫 `gdriveLastRunAt`／`gdriveLastStatus` 的地方**。⚠️ **全樹沒有 `recordGdriveStatus` 這個方法**（`grep -ran` 零命中於 backend），別照那個名字去找。
- `bff/src/main/java/com/steven/assets/bff/tradingradar/TradingRadarBffRoutes.java`
  - 單一 route `trading-radar-route`，predicate 為 `.path("/api/bff/trading-radar", "/api/bff/trading-radar/**")` ＋ `rewritePath`，**未限定 HTTP method** → `POST` 自動穿透，**本任務不改這支檔**。
- `frontend/src/views/TradingRadarView.vue`
  - `:9` `<el-button :icon="Download" @click="openExport">匯出 Excel</el-button>`
  - `:958` `onExport()`：取 `exportDialog.range` 的 `[start, end]` → `bffApi.tradingRadar.exportExcel(start, end)` 得 blob → 檔名 `交易雷達_{start 數字前12碼}_{end 數字前12碼}.xlsx` → `saveBlob(blob, filename)`；`saved` 為真才 `ElMessage.success('匯出完成')` 並關對話框（`saveBlob` 回 false ＝使用者按取消，**不顯示成功訊息**，此行為必須保留）。
  - `saveBlob` 優先 `window.showSaveFilePicker`，`AbortError` 回 false，不支援時退回一般下載。
- `frontend/src/api/index.js` 的 `tradingRadar.exportExcel(from, to)` 走 `api.get('/bff/trading-radar/export', { params: { from, to }, responseType: 'blob' })`。
- `frontend/src/utils/dualExportMessage.js`（t282 新增）：九頁 run-now 的雙落點提示共用工具。**本任務的訊息語意不同**（下載 ＋ 落檔，且落檔可能 skipped），故**不強制沿用**；若沿用需確認其參數形狀相容，不得為此改動它而影響其他九頁。

## 要做什麼

> **落點的選擇（審查後改過一次，勿照舊版實作）**：本任務的新方法放在 **`TradingRadarExportScheduleService`**，**不是**放在 `TradingRadarExportService`。
> 理由：`TradingRadarExportScheduleService:74` 已注入 `TradingRadarExportService`，反向注入回去就是 `@RequiredArgsConstructor` 的**建構子循環依賴**，Spring Boot 3.4.4 預設直接 `BeanCurrentlyInCreationException` 啟動失敗。
> ⚠️ **把 `resolveDir`／`currentSubpath` 改成 package-private 並不能繞開**——那兩支是**實例方法**（用 `:82 baseDir`、`:73 settingRepo` 兩個實例欄位），呼叫端仍得持有該服務的實例、仍得注入、仍是同一個循環。「改可見性」是「注入」的子集，不是替代方案。
> 反之 `TradingRadarExportScheduleService` 已握有本任務需要的**全部**依賴（`settingRepo:73`／`exportService:74`／`excelDocRenderer:79`／`jsonDocRenderer:80`／`dualWriter:81`／`baseDir:82`），**零新依賴、零循環**；而 `TradingRadarController` 早已同時注入兩支 service（`:44`／`:45`），改個呼叫對象即可。
> 破環前例：`GdriveSelfCheck.java:24-34`（「本元件刻意是葉節點……反向注入它就形成建構子循環依賴」）、`TradingRadarNotificationService.java:47` 的 `ObjectProvider<...> self`。

### A. 後端 `TradingRadarExportService`（只抽出 doc、不加任何依賴）

- [x] 283.1 把既有 `export(String from, String to)`（`:38-51`）**拆成兩段**，新增 public 方法：

  ```java
  /**
   * 頁首手動匯出用的 doc（Task 283 抽出）：只查一次 Redis，供呼叫端 render 成下載用 xlsx 與落檔用的兩份。
   * owner 為 null 時用空 SnapshotRange——直接委派 radarDoc(long,...) 會在 Long→long 拆箱時 NPE → 500，
   * 違反 Requirement 48「零快照仍回含表頭合法檔、不得回 5xx」。
   */
  public record ManualDoc(ExportDoc doc, int snapshotCount) {}

  public ManualDoc manualDoc(String from, String to)
  ```

  ⚠️ **回傳必須同時帶快照筆數**，否則 283.4 的零快照 guard 無處可取：`ExportDoc`（`ExportDoc.java:30`）只有 `title`／`sheets`，不帶筆數；要判「零快照」只能鑽進 `sheets().get(0).blocks()` 找 `Table` 數 `rows()`。而在 `exportAndWriteManual` 內另呼叫一次 `snapshotStore.range(...)` 做 guard **就是查兩次 Redis**，正好違反本任務三處引用的 Requirement 55 硬約束（`requirements.md:1858`）。`ManualDoc` 是 `TradingRadarExportService` 內的巢狀 record，**仍是零新依賴、建構子不動**。
  ⚠️ **`snapshotCount` 必須取 `range.snapshots().size()`**，實作寫死為 `return new ManualDoc(radarDoc(range, from, to), range.snapshots().size());`。**不是 `range.indexCount()`**——`SnapshotRange`（`TradingRadarSnapshotStore.java:68`）是 `(List<JsonNode> snapshots, int indexCount, int missingCount)`，`indexCount` 是「索引預期筆數」；索引還在但 value 全被 TTL／LRU 逐出時 `indexCount > 0` 而 `snapshots` 為空，取錯就會用只有表頭的檔覆寫好檔，正是 283.4 全段要防的情境。既有排程 guard（`:404`）用的也是 `.snapshots().isEmpty()`。

  內容即現行 `export(...)` 去掉最後一行 render 的部分（`parseEpoch` 兩參數 ＋ `from > to` 檢查 ＋ `getEffectiveUserId()` ＋ null-owner 空 range 分支 ＋ `radarDoc(range, from, to)`）。
  ⚠️ **本服務的建構子一個依賴都不准加**——`TradingRadarDualFormatTest.java:49` 是 `new TradingRadarExportService(store, currentUserContext, new ExcelDocRenderer())`，加依賴即編譯失敗；`TradingRadarExportServiceTest` 的 `@InjectMocks` 對未宣告 `@Mock` 的欄位會塞 null，該檔 `:38-44` 明文寫「下方斷言一字不得改」。
- [x] 283.2 **移除 `export(String from, String to)`**（本任務後無 production 呼叫端）。
  ⚠️ **任務檔初版給的 grep 是錯的**（`exportService.export(` 只命中 controller 那一處，測試裡的變數名是 `service`）。正確掃描：`grep -ran "\.export(" backend/src`，實際共 **5 處測試呼叫端**必須一併改為 `new ExcelDocRenderer().render(service.manualDoc(a, b).doc())`（⚠️ **別漏 `.doc()`**——`manualDoc` 回的是 `ManualDoc` 不是 `ExportDoc`，`ExcelDocRenderer.render` 只吃後者）；其中 `TradingRadarExportServiceTest:105`／`:111` 是 `assertThatThrownBy`，只要改成 `service.manualDoc(a, b)`、不必 render——`TradingRadarExportServiceTest.java:73`／`:95`／`:105`／`:111`、`TradingRadarDualFormatTest.java:114`。**只改呼叫寫法，斷言一字不動。**

### B. 後端 `TradingRadarExportScheduleService`（新方法放這裡）

- [x] 283.3 新增 public record 與 public 方法：

  ```java
  /**
   * 頁首「匯出 Excel」的結果（Task 283）。
   * @param xlsx       下載用 byte[]，永不為 null（render 失敗直接向外擲 → 5xx）
   * @param dirOutcome "ok"／"failed"／"skipped"，直接作為 X-Dir-Export 標頭值
   */
  public record ManualExportResult(byte[] xlsx, String dirOutcome) {}

  public ManualExportResult exportAndWriteManual(String from, String to) throws IOException
  ```

  - `md = exportService.manualDoc(from, to)` —— **只呼叫一次**，`md.doc()` 供 render、`md.snapshotCount()` 供 283.4 的 guard，同一份 doc 同時 render 出下載用 xlsx 與落檔用的 json／xlsx。⚠️ **Requirement 55 硬約束**：本匯出點吃 Redis 即時快照，查兩次會產出對不起來的兩份檔，**不得**拆成兩支端點或查兩次。
  - 下載用 xlsx render 失敗 → **照常向外擲**（使用者拿不到檔就該回 5xx）。json render 失敗 → 只記 `log.warn`、`jsonBytes` 傳 `null` 給 writer（`DualFormatExportWriter:113-118` 已支援 null＝跳過該份、照寫另一份）。
  - `baseName = "交易雷達_" + ownerId + "_" + <to 解析出的 LocalDate>.format(FILE_DATE)`。⚠️ **日期取 `to`，不是牆鐘今日**。
  - ⚠️ **`to` 的日期解析必須排在 `manualDoc(...)` 回傳之後**：格式與 `from > to` 已由它內部的 private `parseEpoch`（`TradingRadarExportService.java:85`）驗過並轉成 `IllegalArgumentException` → `GlobalExceptionHandler:38` 回 **400**。提前自己 `LocalDateTime.parse(to)` 會擲 `DateTimeParseException` → 落到 `:76` 的 `@ExceptionHandler(Exception.class)` → **500**，回歸掉 `requirements.md:1503`「格式錯誤回 400」。
  - **owner 取得**：用 `currentUserProvider.getObject().getEffectiveUserId()` 取**可為 null 的 `Long`**。⚠️ **不得使用同檔既有的 `requireOwnerId()`（`:505-511`）**——它在 `!ctx.hasUser()` 時擲 `IllegalArgumentException` → `GlobalExceptionHandler` 轉 **400**，而本端點必須回「200 ＋ `X-Dir-Export: skipped` ＋ 含表頭的合法 xlsx」。該類其餘 5 個公開方法（`:121`／`:133`／`:178`／`:192`／`:222`）全用 `requireOwnerId()`，照慣例伸手就會讓本端點回 400。⚠️ **`TradingRadarDualFormatTest:110-118` 覆蓋的是 `TradingRadarExportService.export(...)`（由 `:49` 的三參數建構子建立），完全不經過 `exportAndWriteManual`，誤用 `requireOwnerId()` 時它照樣綠燈**——本路徑的 null-owner 分支只有 283.9 新增的那條測試守得住。
  - 目錄與 Drive 設定沿用同檔既有的 `resolveDir(currentSubpath(ownerId))` 與 `settingRepo.findByOwnerUserId(ownerId)` 的 `isGdriveEnabled()`／`getGdriveSubpath()`（同一個類別內，直接呼叫 private 方法即可）。
  - ⚠️ **落檔全程包 try/catch**，任何失敗只記 log 並回 `"failed"`，**不得讓下載失敗**。
  - ⚠️ **不得寫 `trading_radar_export_setting` 的四個狀態欄**（`last_run_at`／`last_run_status`／`gdrive_last_run_at`／`gdrive_last_status`）：那四欄的語意是「**排程**（含 run-now）最後一次的結果」，手動下載寫進去會讓排程卡顯示一個不是排程產生的落點，使用者無法分辨排程有沒有正常跑。故**不得呼叫** `recordStatus(...)`（`:439`）／`applyGdriveStatus(...)`（`:473`）／`syncGdrive(...)`（`:487`）**三支**。⚠️ `writeDailyExport` 回傳的正是 `DualResult`、接上 `applyGdriveStatus(...)` 太自然，這是最容易誤犯的一處。
- [x] 283.4 **查得零快照時只回下載、一律不落檔**，`dirOutcome` 回 `"skipped"`。
  ⚠️ 這條直接引用同檔 `:393-398` 的既有 javadoc 原文：「**當日查無快照時回 `null` 且不寫檔**……若照寫會在使用者目錄留下只有表頭的無用檔，並蓋掉同名前一版。」手動路徑若沒有同一道 guard，使用者挑到沒有快照的區間（上線前的日期、週末）按下按鈕，就會用**只有表頭的空檔覆寫掉該日排程產出的好檔**，而且 Drive 也跟著同步上去——那是資料損失，不是踩坑。
  判定順序：`ownerId == null` → `"skipped"`；`md.snapshotCount() == 0` → `"skipped"`（⚠️ **不得**在 `exportAndWriteManual` 內另呼叫 `snapshotStore.range(...)`——那是第二次查 Redis）；writer 回傳且 `jsonFile != null && xlsxFile != null` → `"ok"`；其餘（擲例外或任一份為 null）→ `"failed"`。
  ⚠️ **`"skipped"` 不含「未設定輸出目錄」**：`currentSubpath()`（`:428-433`）在無設定列時 `orElse(TradingRadarExportSetting.DEFAULT_SUBPATH)`，而 `DEFAULT_SUBPATH = "input"`（`model/TradingRadarExportSetting.java:36`）——**「未設定目錄」在本系統裡不是一個狀態**，排程照樣寫進 `{EXPORT_OUTPUT_DIR}/input`。若把它做成 skipped，同一位沒有設定列的使用者會變成「排程有落檔、手動說沒設定目錄」，正是本任務要修的那種不一致的新一版。

### C. 後端 controller

- [x] 283.5 `TradingRadarController` 的 `@GetMapping("/export")`（`:70`）**改為 `@PostMapping("/export")`**，改呼叫 `exportScheduleService.exportAndWriteManual(from, to)`：
  - query 參數 `from`／`to`、下載檔名（`交易雷達_{compact(from)}_{compact(to)}.xlsx`，`compact` 在 `:87`）、content-type、`contentLength`、`ContentDisposition` **一律不變**。
  - 新增回應標頭 `X-Dir-Export`，值取 `result.dirOutcome()`。⚠️ 必須是 ASCII（`ok`／`failed`／`skipped`）——標頭放中文路徑需額外編碼，落點資訊前端已可由設定卡得知。
  - **不保留 GET 版本**：本端點自本任務起有副作用（寫檔、上傳 Drive），不得掛在 `GET`；唯一呼叫端是本頁前端（經 BFF passthrough）。
  - BFF `TradingRadarBffRoutes`（`:19-24`）只有 path predicate、無 method predicate，POST 自動穿透；`bff/.../config/SecurityConfig.java:118` 已 `.csrf(disable)`，GET→POST 不會踩 CSRF。**兩支檔都不改。**
  - **一併移除 `:44` 的 `private final TradingRadarExportService exportService;`**（`:73` 是全 controller 唯一用它的地方，改掉後即成死欄位；`@RequiredArgsConstructor` 會自動少一個建構子參數，import 一併清掉）。
  - Controller **只做委派**，不得注入 Repository、不得在內部做路徑解析或落檔（架構鐵則）。

### D. 前端

- [x] 283.6 `frontend/src/api/index.js` 的 response interceptor 目前是 `res => res.data`（`:12-13`），**api 方法拿到的就只有 blob、拿不到標頭**。加一個**向後相容的 per-call 旗標分支**（與同檔既有的 `skipAuthRedirect`（`:19`）／`skipErrorToast` 慣例同型）：

  ```js
  res => (res.config?.rawResponse ? res : res.data),
  ```

  ⚠️ **只有本任務的呼叫端傳 `rawResponse: true`**，其餘所有呼叫端行為完全不變。**不得改動 interceptor 的預設行為**。
- [x] 283.7 `tradingRadar.exportExcel(from, to)`（`:291-292`）由 `api.get` 改為：
  ```js
  exportExcel: (from, to) =>
    api.post('/bff/trading-radar/export', null, { params: { from, to }, responseType: 'blob', rawResponse: true }),
  ```
  ⚠️ axios 的 `post` 簽章是 `(url, data, config)`——**`params` 必須放第三個引數**，放進第二個會變成 request body 而 query 參數消失（後端 `@RequestParam` 缺參數 → 400）。
- [x] 283.8 `frontend/src/views/TradingRadarView.vue` 的 `onExport()`（**`:958`**，不是初版寫的 `:558`）改為取 `res.data` 當 blob、`res.headers['x-dir-export']` 當狀態（⚠️ axios 的 header key 一律小寫），下載成功（`saveBlob` 回 true）後依狀態顯示：
  - `ok` → `ElMessage.success('匯出完成，並已同時寫入伺服器輸出目錄（JSON ＋ Excel）')`
  - `failed` → `ElMessage.warning('已下載 Excel；寫入伺服器輸出目錄失敗，請查後端 log')`
  - `skipped` → `ElMessage.success('匯出完成（該區間查無快照，未落檔）')`
  - ⚠️ **使用者按 save picker 的取消時（`saveBlob` 回 false）一律不顯示任何訊息**（既有行為，`:969-972`，不得改）。
  - **同步改匯出對話框的兩個 `el-form-item`（`:537-553`）**：「存檔位置」現在只講「另存新檔／瀏覽器預設下載資料夾」，本任務後這顆按鈕**另會在伺服器輸出目錄寫兩份、且 Drive 啟用時上傳雲端**——未揭露的雲端上傳是新的副作用。須補述「另會在伺服器輸出目錄產生同名 `.json` ＋ `.xlsx`（落點見下方「匯出輸出檔案設定」卡；Drive 啟用時一併上傳）」。「匯出內容」那格講的是下載檔本身、仍為真，可不改。
  - ⚠️ **落檔失敗不得顯示成功**——t282 修的正是「檔案兩份都在、UI 說謊」，本次不得製造同一類謊言的反向版本。

### E. 測試

- [x] 283.9 在 `backend/src/test/java/com/steven/assets/service/` 新增一支測試類（檔名由實作者決定並在完成報告記錄；**刻意不在此綁死**——`spec-check.sh` 的 B5 會把「spec 提到但全樹不存在的測試類」判為 BLOCK）。沿用同目錄慣例（`@ExtendWith(MockitoExtension.class)` ＋ `@Mock` ＋ AssertJ ＋繁體中文方法名）。
  ⚠️ **本測試不能用 `@InjectMocks`**：`TradingRadarExportScheduleService` 是**顯式 13 參數建構子**（`:89-115`，順序 `timeRepo, settingRepo, exportService, snapshotStore, currentUserProvider, gdrive, excelDocRenderer, jsonDocRenderer, dualWriter, baseDir, radarService, priceQueryService, marketDataService`），其中 `baseDir` 是 `String`（`:98` 的 `@Value`）、`exportService` 又必須是**真實實例**。`@InjectMocks` 會把 `baseDir` 塞 null → `resolveDir`（`:519`）第一行 `Path.of(baseDir)` NPE → 被落檔 try/catch 吞成 `"failed"`，`ok` 那條會以看不出原因的方式紅燈。故直接呼叫 13 參數建構子：`baseDir` 傳 `@TempDir Path` 的字串，`currentUserProvider` 用 `@Mock ObjectProvider<CurrentUserContext>` ＋ `when(...getObject()).thenReturn(ctxMock)`，`ctxMock` 與真實 `TradingRadarExportService` 共用同一個。
  涵蓋：
  - **只查一次 Redis（Requirement 55 的機械判準，必測）**：`verify(storeMock, times(1)).range(anyLong(), anyLong(), anyLong())`。
    ⚠️ **接線方式必須照這樣寫，否則是 false green**：`TradingRadarExportScheduleService` 的欄位叫 `snapshotStore`（`:75`）、`TradingRadarExportService` 的叫 `store`（`TradingRadarExportService.java:32`）——若把 `exportService` 也 mock 掉，正確實作會讓 `snapshotStore` 得到 **0 次**（紅燈），而「多查一次做 guard」的錯誤實作反而剛好 1 次（綠燈），等於**獎勵違反 R55 的寫法**。故本測試必須注入**真實**的 `new TradingRadarExportService(storeMock, ctxMock, new ExcelDocRenderer())`，並把**同一個** `storeMock` 當作排程服務的 `snapshotStore` 傳入。
    ⚠️ **不可寫成「斷言 `radarDoc(...)` 恰好呼叫一次」**：`TradingRadarExportService` 有**兩個** `radarDoc` 多載（`:59` public、`:73` private），本路徑走的是 **private 那支**（為了保住 null-owner 分支），Mockito verify 不到 private 方法。`store.range` 才是「查一次」的真正落點。
  - **落檔失敗仍回 xlsx**：讓 `dualWriter.write(...)` 擲 `IOException`，斷言不擲例外、`xlsx()` 非空、`dirOutcome()` 為 `"failed"`。
  - **零快照 → `"skipped"` 且 writer 零呼叫**（283.4 的資料損失防護，必測）。
  - **落檔檔名取 `to` 的日期**：`from=2026-07-20T00:00:00`、`to=2026-07-25T23:59:59` → 傳給 writer 的 `baseName` 為 `交易雷達_{ownerId}_20260725`（**不是**牆鐘今日）。
  - **四個狀態欄未被寫入**：斷言 `settingRepo.save(...)` 於本路徑**零呼叫**。
  - **`ownerId == null` → `skipped`**：`ctxMock.getEffectiveUserId()` 回 null，斷言 `dirOutcome()` 為 `"skipped"`、`xlsx()` 非空且可用 POI 開出三分頁、`dualWriter` **零呼叫**。這條是 `requirements.md` 驗證 (c) 的落腳點，**沒有它就沒有任何測試守住 null-owner 分支**。
  - **json render 失敗不影響下載**：`jsonDocRenderer.render` 擲例外 → 仍回 xlsx、`dirOutcome()` 為 `"failed"`、writer 收到的 `jsonBytes` 為 `null`。

### 明確不做的事

- [x] 283.10 **不動 `DualFormatExportWriter`**（本機兩份都成功才上傳 Drive 的既有規則不變）、**不動排程與 run-now 的既有行為**（只在 `TradingRadarExportScheduleService` 新增方法）、**不動 BFF 與 `SecurityConfig`**。
- [x] 283.11 **不動 `trading_radar_export_setting` 的 schema 與四個狀態欄的語意**；零 Liquibase changeset。
- [x] 283.12 **不新增 `@Scheduled`**，故 `SchedulePublicBffController.JOBS`（`:100-101`）不新增項目；既有「交易雷達匯出」那筆 description 描述的是排程觸發，本任務只改手動入口，該段文字仍為真、**不需改寫**。
- [x] 283.13 **不改 `frontend/src/utils/dualExportMessage.js`**（t282 建立、九頁 run-now 共用，該檔沒有 skipped 概念）；本任務的訊息另寫在本頁。

## 驗證

### 1. 後端測試

```bash
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test -DextraArgLine=-Dnet.bytebuddy.experimental=true
```

（**不要**用 `-DargLine`，會覆蓋 pom 的時區設定導致大量測試 error。）

### 2. 前端建置

本 worktree 沒有 `node_modules`（在主 clone 且被 gitignore）：

```bash
ln -s /Users/steven/Project/asset-management/frontend/node_modules frontend/node_modules
```

```bash
/Users/steven/.nvm/versions/node/v22.21.0/bin/node /Users/steven/.nvm/versions/node/v22.21.0/bin/npm --prefix frontend run build
```

```bash
rm frontend/node_modules
```

### 3. 部署（本專案沒有 dev server，「改好」＝ image rebuild ＋ container recreate）

⚠️ 依共用 stack 規則，**merge 進 main 後從 main 的 worktree 重建**，且一律加 `-p asset-management`（否則會建到 `asset-management-main-*` 這個沒人用的 tag）。frontend 普通 build 會命中 layer cache 沒重跑 vite，同樣要 `--no-cache`：

```bash
docker compose -p asset-management build --no-cache business-services frontend
```

```bash
docker compose -p asset-management up -d --no-deps --force-recreate business-services frontend && docker compose -p asset-management restart bff
```

⚠️ recreate `business-services` 會換 IP，BFF 握舊 IP 會回 500 且約 3 分鐘不自癒（Docker DNS TTL 600s），故一併 `restart bff`。

stale 判準：

```bash
docker exec asset-frontend sh -c "grep -o '該區間查無快照，未落檔' -m1 /usr/share/nginx/html/assets/*.js"
```

有輸出即為新 bundle。

### 4. 端點自測（免 OAuth，在 business 容器內直接打）

⚠️ **必須用 `POST`**——本任務把 `GET` 改掉了，用 `GET` 打會得到 405，那不是 bug。

```bash
docker exec asset-business-services sh -c 'curl -s -D - -X POST "http://localhost:8080/api/trading-radar/export?from=2026-08-02T00:00:00&to=2026-08-02T23:59:59" -H "X-User-Id: 1" -H "X-User-Role: ADMIN" -H "X-User-Status: ACTIVE" -o /tmp/dl.xlsx' | grep -iE "HTTP/|X-Dir-Export|content-length"
```

預期 `200`、`X-Dir-Export: ok`。接著確認目錄多了兩份且 mtime 相同：

```bash
docker exec asset-business-services sh -c 'ls -l --time-style=+%H:%M:%S /home/steven/Project/SRPP/data/input/ | grep 交易雷達_1_20260802'
```

### 5. 畫面實測

`http://localhost/` → 交易雷達頁首「匯出 Excel」→ 選區間 → 匯出。確認：

- 瀏覽器仍跳出「另存新檔」、存下的是 `.xlsx`（既有體驗不變）。
- 訊息為「匯出完成，並已同時寫入伺服器輸出目錄（JSON ＋ Excel）」。
- `data/input` 同時出現 `交易雷達_{id}_{日期}.json` 與 `.xlsx`，**兩份 mtime 相同**。
- Drive 啟用時兩份也都上傳（`rclone lsl "GDriveOutput:<子路徑>/"`）。
- 按 save picker 的**取消** → **不顯示任何訊息**（既有行為）。
- 排程卡的 `last_run_at`／`last_run_status` **未被本次手動匯出改動**（仍停在最後一次排程／run-now 的時間）。

## 完成報告

### 實際改動

| 檔案 | 內容 |
|---|---|
| `backend/.../service/TradingRadarExportService.java` | `export(String,String)` 拆為 public `manualDoc(from,to)` 回新的巢狀 record `ManualDoc(ExportDoc doc, int snapshotCount)`（`snapshotCount` 取 `range.snapshots().size()`）；**建構子零新依賴** |
| `backend/.../service/TradingRadarExportScheduleService.java` | 新增 record `ManualExportResult(byte[] xlsx, String dirOutcome)` 與 `exportAndWriteManual(from,to)`：一次 `manualDoc` → render 下載 xlsx → guard（null owner／零快照 → `skipped`）→ render json（失敗傳 null）→ `dualWriter.write(...)`，全程 try/catch 不影響下載；不呼叫 `recordStatus`／`applyGdriveStatus`／`syncGdrive` |
| `backend/.../controller/TradingRadarController.java` | `@GetMapping("/export")` → `@PostMapping`，改呼叫 `exportScheduleService.exportAndWriteManual(...)`，加回應標頭 `X-Dir-Export`；移除已成死欄位的 `TradingRadarExportService` 注入與 import |
| `frontend/src/api/index.js` | interceptor 加 per-call 旗標 `res => (res.config?.rawResponse ? res : res.data)`；`tradingRadar.exportExcel` 改 `api.post(url, null, {params, responseType:'blob', rawResponse:true})` |
| `frontend/src/views/TradingRadarView.vue` | `onExport` 改收完整 response、依 `x-dir-export` 顯示三種訊息（`failed` 走 warning）；匯出對話框「存檔位置」補述伺服器目錄與 Drive 兩個落點 |
| `backend/src/test/.../TradingRadarExportServiceTest.java`／`export/TradingRadarDualFormatTest.java` | 5 處呼叫端由 `service.export(a,b)` 改為 `render(service.manualDoc(a,b).doc())`（`assertThatThrownBy` 兩處只改方法名）；**斷言一字未動** |
| `backend/src/test/.../RadarManualExportDualFormatTest.java` | 新增，8 個 `@Test` |

### 驗證輸出

- `mvn -f backend/pom.xml test`：**Tests run: 533, Failures: 0, Errors: 0 — BUILD SUCCESS**（改動前 525，新增 8）。
- 前端 `npm run build`：`✓ built in 4.38s`。
- `spec-check.sh`：BLOCK 0 / CHECK 0。對抗式 spec 審查 **3 輪**（2＋3＋0 critical、5＋7＋4 major 全數處理）。
- `arch-auditor`：critical 0／major 0／minor 1（新增的 record javadoc 插到 `runNow()` 的 javadoc 之後、把它變成孤兒），已修。
  - 該 auditor 另實測確認：全路徑只有一次 `store.range`（`exportAndWriteManual` 內零呼叫）、guard 在 `dualWriter.write` 之前、`settingRepo.save` 於本路徑零呼叫、`rawResponse` 全前端只有三行且未影響其餘 30+ 呼叫端、BFF route 無 method predicate 故 POST 自動穿透。

### 與原計畫的偏差

1. **測試檔名**：任務檔刻意不綁死（避免 `spec-check.sh` 的 B5 誤判），實際命名為 `RadarManualExportDualFormatTest`，放在 `backend/src/test/java/com/steven/assets/service/`（而非 `service/export/`）——受測類別 `TradingRadarExportScheduleService` 在 `service` 套件，且測試需存取同套件的型別。
2. **多加一條測試**（原計畫六條，實際八條）：另加「`ownerId == null` → skipped」（第 3 輪 M3 要求）與「格式錯誤／`from > to` 仍擲 `IllegalArgumentException`（→400）」，後者釘住「`to` 的日期解析必須排在 `manualDoc` 之後」這條約束。
3. **json render 失敗的測試用匿名子類覆寫 `JsonDocRenderer.render`**，而非 mock——該欄位在建構子注入且測試需要真實 `ExcelDocRenderer`，混用較亂。

### 尚未執行

- **部署與實機驗證**（驗證段第 3–5 步）：image rebuild ＋ container recreate、`POST` 端點自測（⚠️ 用 `GET` 打會得到 405，那不是 bug）、畫面實測（下載體驗不變、三種訊息、`data/input` 同時出現兩份且 mtime 相同、Drive 兩份、按取消不顯示訊息、排程狀態欄未被改動）——待使用者指示。依共用 stack 規則，merge 進 main 後應從 main 的 worktree 重建。
