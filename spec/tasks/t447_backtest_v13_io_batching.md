# [t447] V13 回測逐日迴圈的隱性 I/O 批次化（Treasury／FX 假日曆／stockStyleIncomeThreshold 去重複）

**對應 Requirements:** Requirement 162（V13 回測 `appendV13Code()` per-day 迴圈裡三個隱性
I/O 依賴的請求生命週期批次化：Treasury rate observation、FX 假日曆、stockStyleIncomeThreshold
重複呼叫；明確不含逐股迴圈平行化）
**前置任務:** t446（Requirement 161／交易雷達買進閘門否決條件可校準化）——**已於
2026-09-19 merge 進 main**（merge commit `4cf82803`），本任務全部 `BacktestService.java`
行號已依此 merge 後的最新內容重新核對。本任務與 t446 大幅修改 `BacktestService.java` 的
鄰近程式碼，故仍列為前置任務（開工基準必須是含 t446 變更的 main，不得基於舊版）。
**Liquibase changeset:** 無

## 背景

`BacktestService.appendV13Code()`（`backend/src/main/java/com/steven/assets/service/BacktestService.java:1076`
一帶，行號依 2026-09-19 merge commit `4cf82803`（含 Task 446 變更）後的最新內容核對）的
per-day 迴圈（`for (int t = WARMUP; t < bars.size(); t++)`，約 `:1188-1317`）裡，
除了既有的三個純函式（`RadarInputAssembler.assemble()`、`TradingRadarRuleEngine.evaluateStock/evaluateBaseline()`、
`RadarBacktestExecution.execute()`）之外，藏著三個非顯而易見的重複資料存取：

1. `stockStyleIncomeThreshold()`（`BacktestService.java:696-700`）在迴圈外 `:1125` 算過一次
   供 `profile` 使用，迴圈內 `:1243` 用完全相同的輸入又算一次供 `evidenceProfile` 使用——
   純粹重複，JPA `StockStyleRepository.findByCode` 完全無快取。
2. `resolveV13TreasuryRateObservation()`（4-引數純函式版本 `BacktestService.java:3212-3247`）
   的 cache 版本（5-引數，`:3254-3270`）以 `TreasuryObservationKey(decisionInstant, tenor)`
   為 key，但 `decisionInstant` 逐日不同，快取只在同一天的多個候選之間有效，跨日完全無效——
   對債券分類標的（`profile.bond()==true`），每個交易日都會對
   `JdbcTreasuryYieldBatchRepository.findSelected(Instant)`（`JdbcTreasuryYieldBatchRepository.java:134-156`，
   此檔 Task 446 未觸及、行號不變）觸發一次真實 SQL 查詢，外加
   `TreasuryYieldService.selectedRateBatch()` 內 `latestCompletedUsSession()`／`sessionLag()`
   （`TreasuryYieldService.java:170-200`，此檔同樣未受 Task 446 影響）逐日最多 370 次的
   本地日曆游走。
3. `fxAt()`（`BacktestService.java:4266-4277`，兩個 overload）對任何 `underlyingCurrency`
   非 null 且非 TWD 的標的（美股一律強制 USD，見
   `TradingRadarAssetProfileResolver.java:227-229`，此檔未受 Task 446 影響），
   在迴圈內 `:1207-1208` 逐日呼叫，其內部快取（`FxSeries.resolved()`，以 `signalDate` 為
   key）因每個 `signalDate`在同一次迴圈中只出現一次而形同虛設。呼叫鏈為
   `resolveFxFromRows()`（`TradingRadarMarketContextService.java:422-428`）→
   `fxTargetDate()`（`:460-473`，`MAX_CALENDAR_LOOKBACK=20` 天回溯、逐候選日呼叫、
   同年度內無去重）→ `MarketDataService.isTwTradingDayKnown()`（`MarketDataService.java:463-472`）
   → `getTwHolidays()`（`:309-328`）對非當年度**無條件**呼叫 `fetchTwHolidaysFromExt()`
   （`:360-373`，`priceServiceClient.get()...block()` 真實跨容器阻塞式 HTTP 呼叫）。

**Docker 環境實測結果**（對 `POST /internal/backtest/rules` 用相同請求形狀
`{"horizons":[5],"calibrationRatio":0.70,"walkForwardFolds":5}`、只換 `codes`／`markets`／
`from`／`to`，涵蓋各標的完整歷史範圍）：

| 標的 | 型態 | underlyingCurrency | 觸發 fxAt？ | 觸發 Treasury？ | 評估天數 | 耗時 |
|---|---|---|---|---|---|---|
| 2330 | 股票 | TWD | 否 | 否 | 2135 | 34.21s |
| AMD | 股票 | USD | 是 | 否 | ~2200 | 33.69s |
| 0050 | 股票 ETF | TWD | 否 | 否 | 2130 | 33.54s |
| 00679B | 債券 ETF | USD | 是 | 是 | 2053 | 45.59s |

**結論（決定本任務的修正優先順序與誠實表述）**：`fxAt()`／假日曆 HTTP 呼叫鏈經程式碼
追蹤完全屬實，但在單檔股票全歷史規模的實測中沒有可觀測的耗時差異（AMD 反而略快於
2330）——本項修正的正當性是「修正 `InternalBacktestController` 文件自己宣稱的
『唯讀、不觸發外部請求』不變量」，**不是**實測到的效能提升，完成報告不得誇大。
`resolveV13TreasuryRateObservation()` 的效能影響經實測證實：00679B（45.59s）比同有
ETF 溢折價運算但非債券的 0050（33.54s）多耗約 36%（`(45.59-33.54)/33.54`，約
11-12 秒／2053 天），排除了 ETF 運算這個混淆變因，可乾淨歸因到 Treasury 查詢
本身——**這項修正有實測數據支持**。
`stockStyleIncomeThreshold()` 重複呼叫的成本在 baseline 雜訊下量不出獨立訊號，是零風險
免費清理，不是效能亮點。**另外必須誠實記錄：光是單一標的全歷史 × 完整 V13 候選網格
就要 ~34 秒，換算 69 檔全市場規模（歷史任務 t316 曾經歷、從未在 10 分鐘視窗內成功
完成過的規模）單純序列外推 ≈ 39 分鐘，代表就算本任務三項修正全部完成，全市場規模的
walk-forward 大機率依然無法在 10 分鐘視窗內完成**；真正的量體瓶頸是逐日 × 候選網格的
純運算量，不是任何單一 I/O 呼叫。是否需要平行化或其他方式縮減運算量，不在本任務範圍，
須另開獨立 Requirement／Task。

**既有單元測試測不到這三個問題的原因**：`BacktestServiceTest.java` 全部 7 處
`new BacktestService(...)` 呼叫點都把 `stockStyleThresholdProvider`／`treasuryYieldService`
傳 null（觸發各自的早退分支，完全不執行本任務要修正的路徑）、`marketContextService` 是
`@Mock`（`fxAt()` 呼叫不會真的觸發 `MarketDataService`）。三者都必須在 Docker 環境對
真實 Spring bean 跑診斷才會踩到。

## 要做什麼

- [ ] 447.1 **stockStyleIncomeThreshold 去重複。** 在 `BacktestService.appendV13Code()`
  裡，把 `:1125` 呼叫 `stockStyleIncomeThreshold()` 的回傳值存進一個區域變數
  （例如 `BigDecimal incomeThreshold`），`:1238-1243` 建構 `evidenceProfile` 時直接
  傳入這個區域變數，刪除該處重複呼叫 `stockStyleIncomeThreshold()` 的程式碼。
  `stockStyleIncomeThreshold()` 方法本身、其他呼叫端不得修改。此變更只能是「同一值
  不重算」，`evidenceProfile` 的任何欄位值不得因此改變。

- [ ] 447.2 **FX 假日曆：新增請求生命週期年度快取，不得使用 `isTwTradingDayCachedOnly`。**
  `MarketDataService` 既有的 `isTwTradingDayCachedOnly(LocalDate)`
  （`MarketDataService.java:480-490`）**不適用於本場景**：它只查
  `twHolidayCurrentYearCache`，該欄位依 `getTwHolidays()` 既有邏輯（`if (year != currentYear)
  return fetchTwHolidaysFromExt(year);`）結構上只存放當年度資料，非當年度永遠回傳
  `Optional.empty()`。回測歷史資料絕大多數是非當年度，若誤用這條路徑，等同讓幾乎全部
  歷史外幣標的的 FX 證據 fail closed 為不可得——這是會被本任務 447.6 的端到端比對偵測到
  的真實行為變更，**禁止採用**。正確做法：
  - 在 `MarketDataService` 新增 overload
    `public Map<String, String> getTwHolidays(int year, Map<Integer, Map<String, String>> requestScopedCache)`：
    若 `requestScopedCache != null` 且已有該年度 entry（`requestScopedCache.containsKey(year)`）
    直接回傳該 entry 的值（即使是空 map，代表這個請求已經問過這個年度、答案就是不可得）；
    否則呼叫既有 `getTwHolidays(year)`（該方法內部的當年度 TTL 快取邏輯與非當年度直接
    proxy 邏輯完全不變），把結果寫入 `requestScopedCache.put(year, result)` 後回傳。
    既有 `getTwHolidays(int year)`（無快取版本）維持不變、簽章與行為都不變，所有既有
    呼叫端不受影響。
  - 在 `MarketDataService` 新增 overload
    `public Optional<Boolean> isTwTradingDayKnown(LocalDate date, Map<Integer, Map<String, String>> requestScopedCache)`：
    邏輯與既有 `isTwTradingDayKnown(LocalDate)`（`:463-472`）逐行相同，只把內部呼叫
    `getTwHolidays(date.getYear())` 換成 `getTwHolidays(date.getYear(), requestScopedCache)`。
  - 在 `TradingRadarMarketContextService` 新增 overload
    `public LocalDate fxTargetDate(Instant decisionInstant, Map<Integer, Map<String, String>> requestScopedCache)`：
    邏輯與既有 `fxTargetDate(Instant)`（`:460-473`）逐行相同，只把迴圈內
    `marketDataService.isTwTradingDayKnown(candidate)` 換成
    `marketDataService.isTwTradingDayKnown(candidate, requestScopedCache)`。
  - 在 `TradingRadarMarketContextService` 新增 overload
    `public FxContext resolveFxFromRows(String currency, Instant decisionInstant, List<ExchangeRateHistory> suppliedRows, Map<Integer, Map<String, String>> requestScopedCache)`：
    邏輯與既有 `resolveFxFromRows(String, Instant, List)`（`:422-428`）逐行相同，只把
    `fxTargetDate(decisionInstant)` 換成 `fxTargetDate(decisionInstant, requestScopedCache)`。
  - 既有 `resolveFx`／`resolveFxBatch`／`resolveFxBatchCachedOnly`／`fxSeries`／
    `isTwTradingDay`／`isTwTradingDayCachedOnly`／`isTradingDayKnown`／`isTradingDayCachedOnly`
    一律不修改簽章與行為，本任務只新增 overload。
  - 在 `BacktestService.buildV13Report()`（涵蓋該次請求全部標的的範圍，而非
    `appendV13Code()` 單一標的範圍——台股假日曆是全域資料不因標的而異，放在請求層級
    能讓多標的請求間也共用）建立一次
    `Map<Integer, Map<String, String>> twHolidayRequestCache = new HashMap<>();`，
    以方法引數傳入每次 `appendV13Code()` 呼叫；`appendV13Code()` 簽章新增這個引數。
    在 `BacktestService` 新增一個帶 `requestScopedCache` 的
    `fxAt(String currency, FxSeries series, LocalDate signalDate, String market, Map<Integer, Map<String, String>> requestScopedCache)`
    overload，取代 `appendV13Code()` 內 `:1207-1208` 原本呼叫既有 4 引數
    `fxAt(currency, fxSeries, signalDate, market)` 的地方；既有 3 引數與 4 引數
    `fxAt`（`BacktestService.java:4266-4277`）簽章與行為都不變，`FxSeries`／
    `series.resolved()` 既有的 per-signalDate 快取結構也不變，新 overload 只是
    在呼叫 `resolveFxFromRows` 時多帶入 `requestScopedCache`。

- [ ] 447.3 **Treasury rate observation：新增不做 curve_date 去重複的批次查詢，在記憶體
  內重現既有選批次邏輯。** **重要澄清（本任務原草稿曾規劃複用既有
  `findCompleteSeriesThrough`，經 spec-review 發現不適用，特此排除，不得採用）**：
  `JdbcTreasuryYieldBatchRepository.findCompleteSeriesThrough(Instant)`
  （`JdbcTreasuryYieldBatchRepository.java:162-199`）的 SQL 用
  `ROW_NUMBER() OVER (PARTITION BY b.curve_date ORDER BY provider CASE, available_at DESC,
  fetched_at DESC, id DESC) ... WHERE rn=1`，對單一 `decisionInstant` 已經把每個
  `curve_date` 的多筆 revision（`persist()`，`:47-49`，允許同一 `curve_date` 因補件／
  修正而有多筆不同 `available_at` 的紀錄，`available_at` 強制改為本次 `fetched_at`）
  提前 collapse 成一筆。若把這個「已去重複」的結果拿來對**多個不同的 decisionInstant**
  各自做「篩出 `available_at<=decisionInstant`」，較早的 decisionInstant 會因為它原本
  該看到的舊 revision 已經被 collapse 掉、只剩下更新的 revision（其 `available_at`
  晚於該 decisionInstant）而被錯誤地跳過或退回更舊的 `curve_date`——這與既有
  `findSelected(decisionInstant)` 逐日查詢的行為不等價，直接違反本任務「production
  與既有回測數值不變」的核心承諾，**禁止採用**。正確做法：
  - 在 `TreasuryYieldBatchRepository` 介面與 `JdbcTreasuryYieldBatchRepository`
    （`JdbcTreasuryYieldBatchRepository.java`）新增
    `List<TreasuryYieldDto.StoredBatch> findAllRevisionsThrough(Instant decisionInstant)`：
    SQL 與既有 `findCompleteSeriesThrough`（`:162-199`）共用完全相同的
    `WHERE b.complete = TRUE AND b.available_at <= :decisionInstant AND 4 = (SELECT
    COUNT(*) FROM treasury_yield_daily checked WHERE checked.batch_id=b.id AND
    checked.tenor IN ('M3','Y5','Y10','Y30') AND checked.yield_percent >= 0 AND
    checked.yield_percent <= 100)` 篩選條件（此段條件必須逐字複製既有 SQL，不得
    自行改寫，避免與 `findSelected`／`findCompleteSeriesThrough` 的 completeness
    定義產生分歧），**但不做** `ROW_NUMBER()`／`PARTITION BY curve_date`／`rn=1` 的
    collapse，直接 JOIN `treasury_yield_daily` 回傳全部符合篩選條件的批次（含每個
    tenor 的明細列，比照 `readBatches()`（`:215-229`）既有的 row-to-record 組裝方式
    收斂成 `List<StoredBatch>`），`ORDER BY curve_date ASC, id ASC` 即可（不需要
    `rn` 過濾）。
  - 在 `TreasuryYieldService` 新增
    `public List<TreasuryYieldDto.StoredBatch> preloadRevisionsThrough(Instant latestDecisionInstant)`：
    直接委派 `repository.findAllRevisionsThrough(latestDecisionInstant)`。
  - 在 `TreasuryYieldService` 新增
    `public Optional<TreasuryYieldDto.RateContext> resolveRateContextFromSeries(List<TreasuryYieldDto.StoredBatch> series, Instant decisionInstant, String tenor)`：
    因為 `series` 裡每一列都已經通過與 `findSelected` 完全相同的 completeness／
    tenor 有效性 SQL 篩選（新方法沿用同一段 SQL 子查詢，不需要在 Java 端重新驗證
    4 個合法 tenor），只需在**記憶體內**：(1) 篩出 `!b.availableAt().isAfter(decisionInstant)`；
    (2) 用完全鏡射既有 SQL `ORDER BY curve_date DESC, provider CASE (US_TREASURY
    優先於 YAHOO_PROXY), available_at DESC, fetched_at DESC, id DESC LIMIT 1` 的
    比較器取最大值，即
    `Comparator.comparing(StoredBatch::curveDate).thenComparing(b -> "US_TREASURY".equals(b.provider())?1:0).thenComparing(StoredBatch::availableAt).thenComparing(StoredBatch::fetchedAt).thenComparing(StoredBatch::batchId)`
    後對篩選後的串流取 `max(...)`（provider 的比較鍵刻意讓 `US_TREASURY` 對應較大值
    以便用 `max` 語意直接表達「優先」，不要誤用預設遞增排序）；(3) 找到批次後對
    指定 `tenor` 呼叫與既有 `rateContext(...)`（`TreasuryYieldService.java:150-159`）
    相同的組裝邏輯（含 `staleReason`／`lagDays`，這兩者透過既有
    `latestCompletedUsSession()`／`sessionLag()`（`:170-200`）計算、依賴
    `sessionCalendar` 而非資料庫，維持逐次呼叫不變、不在本次批次化範圍內）。
    **此方法對任一 `(series, decisionInstant, tenor)` 組合的輸出，必須與既有「先
    `repository.findSelected(decisionInstant)` 取得同一個 batch id、再呼叫
    `rateContext`」的結果逐位元相同**——這是本任務最容易出錯、也是 447.5／447.6
    的核心不變量，測試 fixture 必須包含「同一 `curve_date` 存在兩筆不同
    `available_at`（revision）」的情境，不能只測跨 `curve_date` 的邊界。
  - 既有 `resolveRateContext(Instant, String)`、`resolveRateContexts(Instant, Set<String>)`、
    `findCompleteSeriesThrough`、`findSelected` 維持不變、不刪除、簽章不變，供既有
    （非回測）呼叫端繼續使用；本項修正不使用 `findCompleteSeriesThrough`。
  - 在 `BacktestService.buildV13Report()`（涵蓋該次請求全部標的，理由與 447.2 的
    `twHolidayRequestCache` 相同——Treasury curve 同樣是全域資料不因標的而異，放在
    請求層級可讓多檔債券標的共用同一份 `preloadRevisionsThrough` 結果，避免
    FULL_MARKET 規模下每檔各自重複查詢）呼叫一次
    `treasuryYieldService.preloadRevisionsThrough(Instant.now())`（**上界用目前
    時刻，不必逐 code 掃描找出「全部標的中最大的 decisionInstant」**——回測的
    decisionInstant 必然不晚於目前時刻，用 `Instant.now()`〔或既有若已注入
    `Clock` 則用 `clock.instant()`，兩者擇一但同一次請求內保持一致〕保證不會
    低估上界、且零額外查詢成本）；**呼叫前需與既有 4-引數
    `resolveV13TreasuryRateObservation`（`:3224-3227` 一帶）一致地檢查
    `treasuryYieldService != null`**，僅在（該次請求存在至少一個
    `profile.bond()==true` 的標的）且（`treasuryYieldService` 非 null）時才呼叫，
    避免無債券標的的請求觸發此查詢、也避免 `treasuryYieldService` 未注入（現行
    `BacktestServiceTest.java` 全部 7 處建構子皆是如此）時拋出 NPE。取得的
    `List<TreasuryYieldDto.StoredBatch>` 傳入每次 `appendV13Code()` 呼叫（
    `appendV13Code()` 簽章新增這個引數）。
  - 修改 `BacktestService.resolveV13TreasuryRateObservation(...)` 的 5-引數 cache 版本
    （`BacktestService.java:3254-3270`）：`appendV13Code()` 迴圈外（`:1184-1185`
    一帶，現有 `treasuryObservationCache` 宣告處附近）若 `profile.bond()==true`，
    `:3268-3269` 的 `cache.computeIfAbsent(key, ignored ->
    resolveV13TreasuryRateObservation(profile, decisionInstant, parameters,
    bondYieldBeta))` 改為呼叫新增的
    `treasuryYieldService.resolveRateContextFromSeries(series, decisionInstant, tenor)`
    取代內部對 `treasuryYieldService.resolveRateContext(decisionInstant, tenor)`
    的呼叫（即 `resolveV13TreasuryRateObservation` 4-引數版本 `:3212-3247` 的呼叫
    邏輯要改用 series 版本）；`profile.bond()==false` 時維持既有立即
    `notApplicable()` 早退，不受影響、不新增查詢，該標的也不需要收到
    `treasurySeries` 引數（或收到但不使用皆可，由實作者依既有程式風格決定）。

- [ ] 447.4 **本任務明確不做的事。** 不修改 `TreasuryYieldService.resolveRateContext`／
  `resolveRateContexts`（既有單次版本）的簽章或既有呼叫端。不修改
  `BondYieldBetaResolver`／`resolveV13BondYieldBetaBatch`（已經是批次化的既有實作，
  不在本次範圍）。不修改 `stockStyleIncomeThreshold()`、`AssetClassifier`、
  `TradingRadarAssetProfileResolver.resolve()` 的分類邏輯本身。不新增、修改任何 REST
  endpoint、BFF route、前端頁面、排程或 DB migration／Liquibase changeset。不涉及、
  不新增、不包裝任何券商下單／改單／撤單 API。**不修改、不重新評估 `appendV13Code()`／
  `buildV13Report()` 的逐股迴圈結構或執行方式（不做平行化）**。Production 唯一路徑
  （非回測、非 `/internal/backtest/*`）的所有既有呼叫端與其輸出、`RULE_VERSION`、
  `EVIDENCE_GATE_V1` 一律不變。

- [ ] 447.5 **單元測試。**
  - `stockStyleIncomeThreshold` 去重複：既有測試若有斷言 `StockStyleRepository.findByCode`
    呼叫次數則更新為減半；若無此類斷言，新增一個以 mock repository 驗證同一標的同一次
    `appendV13Code` 執行中 `findByCode` 只被呼叫一次（而非兩次）的測試。
  - FX 快取：對 `resolveFxFromRows(..., requestScopedCache)` 用同一年度、多個不同
    `decisionInstant` 呼叫，斷言：(a) 帶快取版本與既有無快取版本在相同輸入下回傳
    完全相同的 `FxContext`（`percentile`／`asOfDate` 兩欄逐位元相同，`FxContext`
    record 只有這兩個欄位，不存在 `value`）；(b) 用計數包裝或 spy 驗證同一年度在
    同一個 `requestScopedCache` 生命週期內，底層 `fetchTwHolidaysFromExt` 語意上
    只被觸發一次（可透過注入一個會計數的假 `MarketDataService` 依賴或等價手段
    驗證，不得只靠「程式碼看起來對」跳過此斷言）。
  - Treasury 批次：對含多個 curve batch 的 fixture，比較 `resolveRateContextFromSeries`
    （消費 `findAllRevisionsThrough` 風格、未去重複的 `List<StoredBatch>`）與既有
    「逐日呼叫 `resolveRateContext`」在同一組 `decisionInstant` 序列上的輸出，斷言
    每一天的 `RateContext`（`batchId`／`tenor`／`value`／`curveDate`／`provider`／
    `staleReason`／`lagDays`——欄位名稱為 `value` 不是 `yieldPercent`）逐位元相同。
    必須覆蓋四個情境：**同一 `curve_date` 存在兩筆不同 `available_at` 的 revision
    （決定性測項：構造一筆 YAHOO_PROXY 於 `T1` 建立、之後同一 `curve_date` 被
    US_TREASURY 於 `T2>T1` 補件的 fixture，斷言 `decisionInstant∈[T1,T2)` 時選中
    `T1` 那筆、`decisionInstant>=T2` 時選中 `T2` 那筆——這正是本任務排除
    `findCompleteSeriesThrough` 的原因，若實作誤用該方法或誤將
    `findAllRevisionsThrough` 寫成有 collapse 效果，這個測項會先紅燈）**；
    decisionInstant 恰好落在兩個不同 `curve_date` 交界前後；`available_at` 晚於
    部分 decisionInstant（該天應選到更舊的 `curve_date`）；含至少一個
    incomplete batch 或 tenor 數值不合法（0~100 之外）的列（`findAllRevisionsThrough`
    的 SQL 篩選應已排除，測試只需確認回傳結果確實不含它，不需要在 Java 端另外
    驗證 tenor 合法性）。

- [ ] 447.6 **同輸入同輸出的端到端驗證（Docker，真實 Spring bean）。** 這是本任務
  「不改變任何 production 判斷結果」承諾的唯一直接證據來源，不得以「單元測試全綠」
  代替——`BacktestServiceTest.java` 目前多數建構子把 `stockStyleThresholdProvider`／
  `treasuryYieldService` 傳 null、`marketContextService` 是 mock，測不到本任務改動的
  真實路徑（見上方「背景」段）。步驟：
  1. 在本任務**變更前**的 commit，對 Docker 容器內 `/internal/backtest/rules` 分別送出
     00679B（`{"codes":["00679B"],"from":"2017-01-17","to":"2026-09-18","horizons":[5],
     "markets":["台股"],"calibrationRatio":0.70,"walkForwardFolds":5}`）與 AMD
     （`{"codes":["AMD"],"from":"2016-09-19","to":"2026-09-18","horizons":[5],
     "markets":["美股"],"calibrationRatio":0.70,"walkForwardFolds":5}`）各一次，
     完整保存回應 JSON。
  2. 完成本任務全部程式碼修改、`docker compose -p asset-management build --no-cache
     business-services` 重建、`up -d --no-deps --force-recreate business-services`
     後 `restart bff`。
  3. 用相同兩組請求再各送一次，完整保存回應 JSON。
  4. 對前後兩次各自存下的完整回應 JSON 檔案做整體 diff（例如 `diff before.json
     after.json`，或等價的結構化 JSON 比對；`BacktestDto.Response`／`V13Report`
     並沒有逐日的 `score`／`action`／`shortAction`／`swingAction`／`timingState`
     子結構可供單獨挑欄位比對——這些欄位只存在於 `BacktestService` 內部私有的
     `Obs` record，不會序列化進 API 回應——故直接對整包 JSON 做逐位元 diff，
     而不是挑特定欄位），**diff 結果必須為空**。任何差異都代表本任務的批次化
     不是純效能重構，必須先修到完全一致才能進入下一步。
  5. 記錄修正後與修正前的耗時差異寫入完成報告（可用
     `docker exec asset-business-services sh -c '{ time curl ... ; } 2>&1'` 量測），
     如實記錄各項修正各自的耗時貢獻，不得誇大 FX 修正的效能效益。

## 驗證

```bash
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml \
  -DextraArgLine=-Dnet.bytebuddy.experimental=true test
docker compose -p asset-management build --no-cache business-services
docker compose -p asset-management up -d --no-deps --force-recreate business-services
docker compose -p asset-management restart bff
curl -s http://localhost:8080/actuator/health
```

本任務不涉及 `backend/src/main/resources/db/changelog/**`，不需要重產
`db/schema.sql`，也不需要跑 `scripts/tests/schema-sql-drift-test.sh`。

447.6 的端到端比對指令（範例，實際請求見上方「要做什麼」447.6 步驟 1）：

```bash
docker exec asset-business-services sh -c '
curl -s -X POST http://localhost:8080/internal/backtest/rules \
  -H "Content-Type: application/json" \
  -d "{\"codes\":[\"00679B\"],\"from\":\"2017-01-17\",\"to\":\"2026-09-18\",\"horizons\":[5],\"markets\":[\"台股\"],\"calibrationRatio\":0.70,\"walkForwardFolds\":5}" \
  -o /tmp/before_00679b.json
'
```

## 完成報告

### 改動檔案清單

實作（主程式碼，6 檔）：
- `backend/src/main/java/com/steven/assets/service/BacktestService.java`——447.1（`incomeThreshold`
  區域變數去重複）、447.2（新增 `fxAt(..., requestScopedCache)` overload、`buildV13Report()` 建立
  `twHolidayRequestCache`）、447.3（新增 `resolveV13TreasuryRateObservationFromSeries(...)`、
  `resolveV13TreasuryRateObservation` 5→6 引數新增 `series`、新增 `requestHasBondCode(...)` 輔助
  方法、`buildV13Report()` 建立 `treasurySeries`）、`appendV13Code()` 簽章新增
  `twHolidayRequestCache`／`treasurySeries` 兩個引數。
- `backend/src/main/java/com/steven/assets/service/MarketDataService.java`——新增
  `getTwHolidays(int, Map)`、`isTwTradingDayKnown(LocalDate, Map)` 兩個 overload；既有
  1-引數版本與 `isTwTradingDayCachedOnly` 完全未動。
- `backend/src/main/java/com/steven/assets/service/TradingRadarMarketContextService.java`——新增
  `fxTargetDate(Instant, Map)`、`resolveFxFromRows(String, Instant, List, Map)` 兩個 overload；
  既有無快取版本、`resolveFx`／`resolveFxBatch`／`resolveFxBatchCachedOnly`／`fxSeries` 完全未動。
- `backend/src/main/java/com/steven/assets/service/TreasuryYieldService.java`——新增
  `preloadRevisionsThrough(Instant)`、`resolveRateContextFromSeries(List, Instant, String)`；
  將 `selectedRateBatch(Instant)` 內部邏輯抽出為共用 private 方法 `toSelectedRateBatch(StoredBatch,
  Instant)`（純提取，行為逐行相同），新增 `selectFromSeries(...)` 供 series 版本重用同一份
  staleness／lagDays 計算。`resolveRateContext`／`resolveRateContexts` 簽章與行為未動。
- `backend/src/main/java/com/steven/assets/service/TreasuryYieldBatchRepository.java`——介面新增
  `findAllRevisionsThrough(Instant)`。
- `backend/src/main/java/com/steven/assets/repository/JdbcTreasuryYieldBatchRepository.java`——實作
  `findAllRevisionsThrough`：與 `findSelected` 完全相同的 WHERE 子句，但不做
  `findCompleteSeriesThrough` 的 `ROW_NUMBER() PARTITION BY curve_date` collapse。

測試（447.5，5 檔）：
- `backend/src/test/java/com/steven/assets/service/BacktestServiceTest.java`——新增
  `stockStyleIncomeThresholdIsResolvedOncePerAppendV13CodeExecution`。
- `backend/src/test/java/com/steven/assets/service/TradingRadarMarketContextServiceTest.java`——新增
  `resolveFxFromRowsWithRequestScopedCacheMatchesUncachedResultAcrossMultipleDecisionInstants`、
  `fxTargetDateWithRequestScopedCacheCallsKnownOverloadNotCachedOnly`。
- `backend/src/test/java/com/steven/assets/service/MarketDataServiceTwHolidayProxyTest.java`——新增
  `requestScopedCacheCallsHolidayProxyOnceRegardlessOfRepeatedYearLookups`（真實 HTTP 計數器，非僅
  程式碼審閱）。
- `backend/src/test/java/com/steven/assets/service/TreasuryYieldServiceTest.java`——新增 3 個測試涵蓋
  447.5 要求的四個情境：`resolveRateContextFromSeries同一curveDate的較新revision補件前後選中不同批次`
  （決定性測項）、`resolveRateContextFromSeries在新curveDate可得前退回較舊curveDate`（涵蓋 curve_date
  交界與 available_at 晚於部分 decisionInstant 兩情境）、
  `resolveRateContextFromSeries不會選中incomplete或缺tenor的列`。
- `backend/src/test/java/com/steven/assets/repository/JdbcTreasuryYieldBatchRepositoryTest.java`——新增
  `allRevisionsSql與findSelected共用WhereClause但不做RowNumberCollapse`（額外補強，447.5 未明文要求，
  但與既有 `selectionSql`／`betaSeriesSql` 兩個 SQL 斷言測試同構，在 447.6 的 Docker 重建前先攔一次
  SQL 文字層級的錯誤）。

### 447.5 單元測試結果

`mvn -f backend/pom.xml -DextraArgLine=-Dnet.bytebuddy.experimental=true test`（**全專案**，非只跑
本任務相關類別）：**2255 個測試、0 failures、0 errors、0 skipped**（`grep -L "Failures: 0, Errors: 0"
backend/target/surefire-reports/*.txt` 無輸出，確認每一份 surefire report 都是全綠）。本任務新增／
修改的測試逐檔確認：`BacktestServiceTest`（37，含新增 1 個）、`TreasuryYieldServiceTest`（12，含新增
3 個）、`JdbcTreasuryYieldBatchRepositoryTest`（7，含新增 1 個）、`MarketDataServiceTwHolidayProxyTest`
（3，含新增 1 個）、`TradingRadarMarketContextServiceTest`（9，含新增 2 個）皆為 0 failures/errors。

### 447.6 端到端驗證

流程：`docker compose -p asset-management build --no-cache business-services`（image
`2a474e9cd34d`，重建耗時屬正常範圍）→ `up -d --no-deps --force-recreate business-services`（3 次
輪詢內轉 healthy）→ `restart bff`（立即 healthy）→ 對 00679B／AMD 各送出與「修改前」完全相同的
request body 各一次 → 保存為 `after_00679b.json`／`after_amd.json`。落地前另用
`docker exec ... unzip -p app.jar ... | strings | grep resolveRateContextFromSeries` 確認執行中
container 的 jar 內確實含新方法（排除 stale image 誤判）。

**比對結果**：`diff before_00679b.json after_00679b.json` 與 `diff before_amd.json after_amd.json`
**原始位元組層級都非空**——但深入排查後確認差異**完全不是**業務邏輯或數值變化，而是 JDK
`Map.of()`／`Map.copyOf()` 眾所皆知的「每次 JVM 進程啟動各自隨機化 iteration order」設計
（`java.util.ImmutableCollections` 內建 per-VM salt，Javadoc 明載「iteration order is
unspecified」，目的正是勸阻開發者依賴其順序）。具體排查步驟與證據：

1. 用 Python 對兩份 JSON 做遞迴 key-sort 後正規化比較：**00679B／AMD 的實際數值差異皆為 0**
   （`find_value_diffs` 遞迴走訪整棵 JSON 樹，逐一比較每個 leaf 值），僅有 key **順序**差異
   （00679B 276 處、AMD 72 處），且全部集中在恰好兩個欄位：
   `parameterSnapshot.bondRate.tenorByBondTerm`（`{SHORT,MID,LONG}` 三鍵）與
   `parameterSnapshot.candidateWeightDeltas`（`{SHORT_MARKET,...}` 四鍵）。
   對兩份 JSON 做「遞迴 sort_keys 後 `diff`」得到的正規化檔案**逐位元組相同**
   （`diff before_00679b_pretty.json after_00679b_pretty.json`、AMD 同理，皆 exit code 0，
   檔案位元組數也分別相等：00679B 1,128,876 bytes、AMD 760,053 bytes）。
2. 追蹤這兩個欄位的原始碼：`RuleParameters.java` 的 compact constructor 對兩者都以
   `Map.copyOf(copied)` 收斂（`:87`、`:238`），且 `tenorByBondTerm` 的驗證來源
   `BondRateCandidate.ELIGIBLE` 本身就是 `Map.of(SHORT,...,MID,...,LONG,...)`（`:214`）；
   `candidateWeightDeltas` 的實際數值來自 `BacktestService.v13CandidateGrid()`（`:2552` 一帶）
   逐一以 `Map.of(CandidateWeight.SHORT_MARKET, ..., CandidateWeight.MEDIUM_MARKET, ...)`
   硬編碼建構。這兩處**皆不在本任務 447.1／447.2／447.3 觸及的程式碼範圍內**（`v13CandidateGrid`
   在 `buildV13Report`／`appendV13Code` 之外的獨立方法，`RuleParameters.java` 整檔本任務未修改）。
3. 決定性佐證：對**同一個**（本次修改後、未重啟）JVM 進程重複發送兩次完全相同的 00679B／AMD
   請求，兩次回應**逐位元組完全相同**（`diff after_00679b.json after_00679b_rerun.json`、
   AMD 同理，皆 exit code 0）——證明同一 JVM 進程內完全確定性，差異只可能來自「重建 container
   （＝重啟 JVM）換了一次 salt」，與本次程式碼改動無關；若換成完全不改任何程式碼、單純
   重新 build＋recreate container 一次，也會出現同一種差異。

**結論**：改用「遞迴正規化後比較」（任務文件 447.6 步驟 4 本身列為與逐位元 diff 等價的替代方案：
「或等價的結構化 JSON 比對」）視為本任務要求的比對基準，**兩個標的皆通過**——沒有任何一個實際
數值、欄位或陣列元素順序改變。原始 `diff` 非空的唯一原因，逐一排查到底就是上述兩個與本任務改動
完全無關、且改動前也同樣存在的 JDK 層級不確定性欄位，不代表批次化引入了行為差異。

### 447.6 步驟 5：耗時量測

| 標的 | 修改前（任務檔背景表，同一份請求、同一套環境） | 修改後（本次量測，3 個樣本） | 修改後平均 | 絕對改善 | 相對改善 |
|---|---|---|---|---|---|
| 00679B（債券 ETF，觸發全部三項修正） | 45.59s | 39.48s／37.32s／37.40s | 38.07s | 7.52s | 16.5% |
| AMD（美股個股，只觸發 447.1＋447.2，不觸發 Treasury） | 33.69s | 30.09s／30.48s／30.32s | 30.30s | 3.39s | 10.1% |

粗略拆解 Treasury 修正單獨貢獻：00679B 改善（7.52s）－ AMD 改善（3.39s）＝約 **4.1 秒**
（約當 00679B 原始 45.59s 的 9%），量級與任務檔背景段「00679B 比 0050 多耗約 36%（11-12 秒）」
的既有診斷方向一致（同一標的、同一次修正前後比較，理應比背景段「跨標的比較」更乾淨）。

**必須誠實揭露的量測限制**（不得誇大）：
- 「修改前」欄位取自任務檔背景段落先前記錄的診斷數字，與本次「修改後」量測**不是同一次
  Docker container 生命週期**，中間隔了本次 image 重建＋container recreate，host 端背景負載
  （其餘 8 個同時運作的 container）在兩次量測之間未必完全相同，故絕對秒數差異包含少量環境雜訊，
  不是實驗室等級的控制對照。
- AMD 的 10.1% 改善**不得解讀為 FX 快取（447.2）本身的效能貢獻**：任務檔背景段已誠實記錄
  「fxAt／假日曆呼叫鏈在單檔股票全歷史規模實測中沒有可觀測的耗時差異（AMD 反而略快於 2330）」，
  本次 AMD 的改善量級與此前既有結論一致（同屬「量不出獨立訊號」的雜訊範圍），447.1
  （`stockStyleIncomeThreshold` 去重複）本身也早已定性為「零風險免費清理，不是效能亮點」。
  AMD 的改善應理解為環境雜訊，而非 447.1／447.2 的實測效益證明。
- 三個樣本內部一致性良好（00679B 37.32s／37.40s 兩個乾淨樣本相差僅 0.08s；AMD 30.09s／30.48s／
  30.32s 相差在 0.4s 內），代表「修改後」本身的量測是穩定的，不確定性主要來自跨 container
  生命週期的「修改前）比較基準。

### 與任務檔規劃的偏差

無實質偏差。以下為實作時需要做出的具體判斷，記錄供稽核：

1. **`requestHasBondCode(...)` 的具體實作方式**——任務檔只說「該次請求存在至少一個
   `profile.bond()==true` 的標的」作為 gate 條件，未指定怎麼判定。實作採用：對
   `codesByMarket` 逐一呼叫 `stockRepo.findByCodeAndMarket` + `TradingRadarAssetProfileResolver
   .resolve(...)`（與 `appendV13Code()` 內初始 `profile` 建構完全相同的呼叫形狀，
   `valuationYieldPct=null`），因為 `bond()` 只由 `assetClass` 決定、`assetClass` 分類邏輯完全
   不讀 `valuationYieldPct`／`incomeThresholdRatio`（已讀原始碼確認），故此處判定不可能與
   `appendV13Code` 內逐日重算的 `evidenceProfile.bond()` 結果分岔，不會有 false negative 導致
   有債券標的卻誤判無債券而跳過 preload 的風險。
2. **`resolveV13TreasuryRateObservation` 5→6 引數而非維持 5 引數**——任務檔稱其為「5-引數 cache
   版本」是指修改前的識別方式（在 `:3254-3270` 一帶找到這支方法），而非要求維持 5 引數；
   由於這支方法需要多接收 `series`，改為 6 引數（新增 `List<TreasuryYieldDto.StoredBatch>
   series` 於尾端）。`cache == null` 的防禦分支（目前無實際呼叫端會命中）維持呼叫原本
   4-引數單次查詢版本，避免討論不到的邊界改變行為。
3. **`TreasuryYieldService.selectedRateBatch(Instant)` 內部重構**——任務檔未要求，但為了讓
   `resolveRateContext`／`resolveRateContexts`（既有單次路徑）與新的 series 路徑共用完全相同的
   staleness／lagDays 計算（`toSelectedRateBatch`），避免手動複製貼上兩份邏輯產生分歧風險，
   抽出一個 private 方法。純提取，不改變 `selectedRateBatch(Instant)` 的外部行為（既有全部
   `TreasuryYieldServiceTest` 測試在未修改斷言的情況下全數通過，證明這個重構未引入回歸）。
4. **`JdbcTreasuryYieldBatchRepositoryTest` 新增的 SQL 文字斷言測試**——447.5 未明文要求，額外
   補上（與既有 `selectionSql`／`betaSeriesSql` 兩個既有測試同構），理由是這類原始 SQL 字串
   在 `mvn test`（H2 或純 mock）層級無法驗證真實語法正確性，補一個文字層級的 smoke test 能在
   447.6 的 Docker 重建（成本高、耗時久）前先攔住明顯的 SQL 拼寫錯誤。
