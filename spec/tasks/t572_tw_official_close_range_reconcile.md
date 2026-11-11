# [t572] 台股官方收盤歷史區間對帳：把富邦投影日線換成可信官方收盤

**對應 Requirements:** Requirement 7（市場資料整合：台股正式收盤只認 TWSE／TPEx 官方指定交易日對帳與 FinMind 缺檔 fallback，且必須可稽核出處）
**前置任務:** Task 290（台股官方收盤對帳，已在 main）、Task 258（`/internal/repair/history`，已在 main）
**Liquibase changeset:** 無

## 背景

**現在的錯誤行為（2026-11-12 以資料庫 SELECT 與 SRPP 日報證實）：**

- `stock_price_history` 中台股持股（0050、0056、00713、00878、00919 等）在 2025-10-08～2026-08-06 約 200 個交易日的列 `close_source='FUBON_SDK'`，2026-05-01 另有 `close_source IS NULL` 一列；2026-08-07 起才由 Task 290 官方對帳逐日寫入 `close_source='TWSE_MI_INDEX'`。查詢：
  `select stock_code, coalesce(close_source,'<NULL>'), count(*), min(trading_date), max(trading_date) from stock_price_history where market='台股' and stock_code in ('0056','00878','00713','00919','0050') and trading_date>=current_date-400 group by 1,2;`
- `PriceQueryService.isTrustedClose(row)` 對台股只接受 `close_source ∈ {TWSE_MI_INDEX, TPEX_DAILY_CLOSE, FINMIND_TW_CLOSE}`，`FUBON_SDK` 與 NULL 都是 `trusted=false`。
- SRPP 每日報告的高股息補足閘門（business-services `SrppReportModuleCalculator.adjustedAvailable`）讀 `position_sessions=252` 個已完成交易日，碰到任何 `trusted=false` 列即丟 `Gap("PRICE_HISTORY_UNVERIFIED")`。2026-11-10、2026-11-11 日報中 0056／00713／00878／00919 的 `TOPUP_GATES` 全部 `UNAVAILABLE／PRICE_HISTORY_UNVERIFIED`；不修的話要到約 2027-08 才自然累積滿 252 個官方交易日，期間高股息補足永遠無法評估。
- 既有 `POST /internal/repair/history`（Task 258）以 `StockSourceQuery.upsertHistory` 覆寫，UPDATE 時把 `close_source` 設為 NULL，修完仍不可信，不能用來解決本問題。

**正確行為：** 提供一個手動維運入口，對指定歷史區間的每一個台股交易日，重用 Task 290 的官方對帳（TWSE `MI_INDEX?date=YYYYMMDD&type=ALLBUT0999`），把持股台股日線以 `upsertVerifiedHistory` 換成官方收盤並寫入 `close_source='TWSE_MI_INDEX'`。

**既有可重用程式（不得修改其行為）：**

- `ClosePersister.reconcileTwOfficialClose(LocalDate targetDate, boolean publishLatest)`（package-private）：`collectHeldStockCodes` 取台股集合並移除 `0000` → `twOfficialCloseClient.fetch(targetDate)` → 對每個代號，若官方列存在且 `row.tradingDate()==targetDate` 就 `source.upsertVerifiedHistory(code,"台股",targetDate,open,high,low,close,volume,row.source())`，`upsertVerifiedHistory` 回 true 且 `publishLatest` 為 true 才寫 Redis `cacheWriter.writeVerifiedClose`；最後以 `hasTrustedTwClose` 算 missing，回 `TwCloseReconciliation(expected, verified, missingCodes)`。
- `TwOfficialCloseClient.fetch(LocalDate)`：TWSE 與 TPEx 各自 try/catch，失敗只進 `sourceFailures`，不丟例外。TPEx OpenAPI 沒有日期參數、只提供當日，歷史日的上櫃代號一律查無。
- `ClosePersister` 欄位 `sleeper`（`interface`，可在測試替換為 no-op）與 `timeSource`（`Clock`），測試已在 `ClosePersisterTargetDateTest` 使用：`persister.timeSource = Clock.fixed(...)`、`persister.sleeper = ignored -> { };`。
- `MarketCalendar.isTwTradingDay(LocalDate)`：`!isWeekend && !isTwHoliday`。

## 要做什麼

- [x] 572.1 在 `external-materials-service/src/main/java/com/steven/assets/externalmaterials/service/ClosePersister.java` 新增 public record `TwCloseRangeReconciliation(LocalDate from, LocalDate to, int tradingDays, int daysWithTrusted, int rowsTrusted, Map<LocalDate, List<String>> missingByDate, boolean interrupted)`。`daysWithTrusted`／`rowsTrusted` 的定義是「對帳後該日具可信來源（`hasTrustedTwClose`）的代號」，等於既有 `TwCloseReconciliation.verified()` 的加總，**不是本次實際寫入列數**；重跑時數值不變，compact constructor 以 `Collections.unmodifiableMap(new LinkedHashMap<>(...))` 防禦性複製，map 內 list 以 `List.copyOf`。
- [x] 572.2 同檔新增 `public TwCloseRangeReconciliation reconcileTwOfficialCloseRange(LocalDate from, LocalDate to)`：
  - `from`、`to` 任一為 null、`from.isAfter(to)` → `IllegalArgumentException`。
  - 台北今日 `LocalDate.now(timeSource.withZone(MarketClock.TW_ZONE))`（`timeSource` 為 `Clock`，`MarketClock.TW_ZONE` 既有）；`!to.isBefore(today)` → `IllegalArgumentException`（今日列獨佔給排程對帳）。
  - `ChronoUnit.DAYS.between(from, to) >= 400` → `IllegalArgumentException`（即含頭尾最多 400 個日曆日）。
  - 由 `from` 到 `to`（含）逐日：`calendar.isTwTradingDay(d)` 為 false 就略過、不計入 `tradingDays`、不呼叫官方來源。交易日：若不是本次第一個交易日，先呼叫 `sleeper.sleep(3_000L)`（既有介面 `interface Sleeper { void sleep(long millis) throws InterruptedException; }`，單位毫秒）；若丟 `InterruptedException`：呼叫 `Thread.currentThread().interrupt()` 還原中斷旗標、記 `log.warn`、**立即停止**後續日期並回傳已處理部分的結果且 `interrupted=true`，不得吞掉後繼續；再呼叫 `reconcileTwOfficialClose(d, false)`。**必須傳 false**，不得寫 Redis 或 publish。
  - 單日呼叫丟出任何 `RuntimeException`（實務上只剩 DB 例外：`TwOfficialCloseClient.fetch` 對 TWSE／TPEx 失敗都已自行 try/catch、只回空批次與 `sourceFailures`，此時表現為該日全部代號缺檔）：記 `log.warn`，該日以 `List.of("ERROR: " + 簡短訊息)` 放入 `missingByDate`，繼續下一日，不中斷整批。
  - 單日成功：`verified > 0` 則 `daysWithTrusted++`；`rowsTrusted += verified`；`missingCodes` 非空則以排序後清單放入 `missingByDate`。
  - 結束記一行 `log.info` 摘要並回傳 record。
  - 已知可接受成本：`fetch` 每日都會整包下載 TPEx 當日資料，對歷史日無用（上櫃代號歷史日一律查無、維持原列）；本任務不改 client。`isTwTradingDay` 依年度官方假日表（失敗退 DGPA，兩者皆失敗時當作交易日），誤判只會多一次抓取與一筆全代號缺檔，不會寫錯資料。
  - 不得新增 `@Scheduled`、不得修改 `reconcileTwOfficialClose`、`TwOfficialCloseClient`、`StockSourceQuery`、`isTrustedClose` 可信來源集合或任何既有排程時點。
- [x] 572.3 在 `external-materials-service/src/main/java/com/steven/assets/externalmaterials/controller/InternalPriceController.java` 新增：
  ```java
  /** 台股官方收盤歷史區間對帳（Task 572）：把持股日線換成 TWSE MI_INDEX 官方收盤與來源。維護用，同步執行。 */
  @PostMapping("/close/reconcile-tw-range")
  public ClosePersister.TwCloseRangeReconciliation reconcileTwRange(
          @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
          @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to)
  ```
  方法內以 try/catch 委派 `closePersister.reconcileTwOfficialCloseRange(from, to)`，把 `IllegalArgumentException` 轉成 `throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage())`（同檔 `InternalPriceController.java` 約第 90 行已有同樣寫法）。**禁止**新增 controller 層級 `@ExceptionHandler` 或全域 `@ControllerAdvice`：同 controller 的 `/repair/history` 依賴 IAE→500 的既有行為，不得被改變。不新增 business-services／BFF proxy、前端按鈕或 9090 路由。
- [x] 572.4 在 `external-materials-service/src/test/java/com/steven/assets/externalmaterials/service/` 新增一支以 `ClosePersister` 開頭命名的 Mockito 單元測試類（比照 `ClosePersisterTargetDateTest` 建構方式，`timeSource` 固定為 `2026-11-12T02:00:00Z`、`sleeper` 記錄呼叫次數與 millis 參數）：
  1. 區間 2026-11-06（五）～2026-11-10（二）：週末兩天不呼叫 `official.fetch`；三個交易日各呼叫一次 `fetch`；`tradingDays==3`；sleeper 恰被呼叫 2 次且每次參數 `3000L`。
  2. 每次寫入都以 `StockSourceQuery.TWSE_MI_INDEX` 呼叫 `upsertVerifiedHistory`，且 **`cache.writeVerifiedClose` 從未被呼叫**（`verifyNoInteractions(cache)` 或 `never()`）。
  3. 某日 `official.fetch` 丟 `RuntimeException` → 該日進 `missingByDate` 且後續交易日仍被處理。
  4. 某日官方批次缺某代號（`hasTrustedTwClose` 回 false）→ `missingByDate` 該日含該代號；`rowsTrusted`、`daysWithTrusted` 計數正確；`0000` 不出現在任何計數或缺檔中。
  5. 參數驗證：`from > to`、`to == 台北今日(2026-11-12)`、`to` 在未來、跨度 400 日曆日（如 2025-10-01～2026-11-05 為 400）都丟 `IllegalArgumentException`，且不呼叫 `official.fetch`；跨度 399 允許。
  6. sleeper 第二次呼叫丟 `InterruptedException` → 回傳 `interrupted==true`、只處理了第一個交易日、執行緒中斷旗標為 true（測試結束前以 `Thread.interrupted()` 清除）。
- [x] 572.5 比照既有 `InternalPriceController` 的 POJO 測試寫法（直接 new controller、mock 依賴；同目錄已有此類測試，external-materials-service 沒有對它的 `@WebMvcTest`），新增案例：正常參數委派給 `closePersister.reconcileTwOfficialCloseRange` 並原樣回傳；persister 丟 `IllegalArgumentException` 時 controller 丟 `ResponseStatusException` 且狀態為 400。
- [ ] 572.6 收尾後（非本任務程式的一部分，但為驗收條件）：run-stack 重建並 recreate `external-materials-service`，按月分段呼叫（每段約 20 個交易日；external-materials-service 只在容器內開 8080、未對主機開 port，一律用 docker exec）：
  `docker exec asset-external-materials-service curl -s --max-time 3600 -X POST "http://localhost:8080/internal/close/reconcile-tw-range?from=2025-10-01&to=2025-10-31"`
  依序每月一段直到 `from=2026-08-01&to=2026-08-06`，逐段保存回應 JSON；若某段 curl 逾時斷線（伺服器端仍會完成），以下列 SELECT 為準並在完成報告註記。各段 `tradingDays` 須與 TWSE 當月實際交易日數相符；若 2025 年整月平日都被當成交易日，代表年度假日表 authority 失敗，須在完成報告記錄。再以上述 SELECT 重查：2025-11-12 起（252 個交易日內）0050／0056／00713／00878／00919 的 `close_source` 全部屬於可信集合，**交易日列**的 `FUBON_SDK` 與 NULL 歸零，或只剩 TWSE 也查無的日期（須在回應 `missingByDate` 可見）。休市日殘列（如 2026-05-01 勞動節那列 `close_source IS NULL`、成交量異常小、收盤同前日）不在 `isTwTradingDay` 範圍、不會被覆寫，也不影響 SRPP（其只走 `completedSessions`）；驗收 SELECT 須排除非交易日，是否刪除該殘列另開任務。

## 驗證

```bash
bash scripts/spec-check.sh
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f external-materials-service/pom.xml -DextraArgLine=-Dnet.bytebuddy.experimental=true '-Dtest=ClosePersister*' test
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f external-materials-service/pom.xml -DextraArgLine=-Dnet.bytebuddy.experimental=true test
# run-stack：從與 main 一致的 .env 重建並 recreate external-materials-service，健康後執行 572.6 的 curl 與 SELECT。
```

## 完成報告

2026-11-12 feature 驗收：
- 改動：`ClosePersister.java` 新增 `TwCloseRangeReconciliation` 與 `reconcileTwOfficialCloseRange`；`InternalPriceController.java` 新增 `POST /internal/close/reconcile-tw-range`（端點內 IAE→400）；新增 service 區間對帳單元測試 4 例與 controller POJO 測試 2 例。未改 `reconcileTwOfficialClose`、`TwOfficialCloseClient`、`StockSourceQuery`、可信來源集合與任何排程。
- spec 審查：三輪獨立子代理審查，第一輪 1 critical／5 major、第二輪 1 major 均已修，第三輪無 critical／major。
- 驗證：`spec-check` BLOCK 0；`mvn -f external-materials-service/pom.xml test` 全量 877 例 0 失敗。
- 572.6 正式回補與 SELECT 驗收於部署後執行，結果補記於下。
