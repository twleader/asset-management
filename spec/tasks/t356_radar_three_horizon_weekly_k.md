# [t356] 今日交易雷達拆為「一周」「1周~1月」「1月~6月」三軌，並把日K棒與真正的週K納入評分

**對應 Requirements:** Requirement 93（交易雷達由兩軌拆為三軌持有期，並把日K 棒與週 OHLC 聚合指標真正納入分析；適用台股個股、台股大盤、美股個股與美股大盤）
**前置任務:** t291（雙軌獲利機會評分，已完成）／t305（因子貢獻共用求值，已完成）／t342（美股大盤量價環境因子，已完成）
**Liquibase changeset:** 無（本任務不新增資料表、不新增欄位、不寫任何 migration）

## 背景

使用者於 2026-08-22 提出兩件事：

1. **把現有兩軌拆成三軌**——「一周」、「1周~1月」、「1月~6月」。現行 `TW_RULES_V14` 只有短期（5 個交易日）與中期（20／60／120 個交易日）兩軌，中間約 5–20 個交易日的波段尺度沒有任何一軌代表，被硬塞進其中一軌時兩邊都不準。
2. **分析時加入日K、周K**。使用者已確認範圍為「**週K 聚合＋週K 指標進評分**」，且「**台股個股＋台股大盤＋美股都要**」。

現況有三個必須先講清楚的事實，否則會做錯：

- **`weeklyMa` 不是週K。** `TechnicalIndicatorService.FullIndicators.weeklyMa`、`TradingRadarDto.StockDecision.weeklyMa`、`MarketSummary.weeklyMa` 命名為「週線」，實際上是**日K 收盤序列的 5 日簡單移動平均**（`TechnicalIndicatorService` 對 `series` 取前 5 筆平均），不是週 OHLC 聚合。本任務**不改動** `weeklyMa` 的定義與欄位名（改名會讓既有 Redis 快照、Excel 匯出與 OpenAPI `required` 清單同時靜默改變語意），改以獨立命名的新欄位承載真正的週K。
- **⚠ 專案裡已經有一份週 OHLC 聚合，但它在 BFF、而且它的「週指標」不是週指標。** Requirement 92／Task 355（股票分析價格圖的日K／週K，已 landed）在 `bff/src/main/java/com/steven/assets/bff/stockanalysis/ChartSeriesAligner` 加了 `weekly(...)`：以 `WeekFields.ISO` 的 `weekBasedYear`＋`weekOfWeekBasedYear` 分桶，`open` 取該週最早交易日、`high`／`low` 取極值、`close` 取最晚交易日。**週的分桶規則與本任務完全一致，必須保持一致**（見 356.2b-2）。但三件事不同，不得把兩者當成同一個東西：
  1. **它的週 KD／MACD／RSI 是「該週最後一個交易日的日K 指標值」，不是在週K 上重算的。** Task 355 的任務檔自己把「weekly 重新計算的 KD/MACD/RSI」明列為**不在該次範圍**。本任務要做的正是那件被排除的事。
  2. **價基不同。** `ChartSeriesAligner` 吃的是走勢圖的**原始價基**；本任務的週K 一律由**還原權息／分割後**的日K 聚合。`spec/design.md` 早已記載雷達與走勢圖價基「刻意不同」，這是既有且刻意的分歧，不是本次引入的。
  3. **進行中週的處理不同。** 圖表會畫出進行中週（圖本來就該畫到今天）；本任務一律排除進行中週（評分不能逐日抖動）。
  兩者因此是**兩個不同的量**，畫面與匯出文案不得讓使用者以為是同一個值。
- **「日K 已經在用了」這句話不完整。** 現行評分讀的是還原後的**收盤價序列**與由其導出的 MA／KD／MACD／RSI／BIAS／W%R，以及 `completedChangePercent`（收盤 vs 前一收盤）。`stock_price_history.open_price`／`high_price`／`low_price` 除了餵 KD 的 RSV 與 52 週高低之外，**從未以「K 棒本身」的形式進入任何因子**——沒有實體方向（收 vs 開）、沒有收盤在當日高低區間的位置、沒有上下影線。本任務補上這一組。
- **既有兩軌的欄位名不得動。** `shortXxx` 固定為「一周」軌、無前綴的 `score`／`action`／`reasons`／`risks` 固定為「1月~6月」軌。四個既有消費端直接讀這兩組名稱：`trading_radar_notification_setting.last_action`、Redis 快照 JSON、`TradingRadarExportService` 的既有欄、`docs/openapi/docker-external-api.yaml` 的 `required` 清單。新增的中間軌一律以 `swing` 前綴命名。

本任務推翻 Requirement 60（t291 修訂）的「兩軌收斂」決定。推翻的是**軌數**，不是既有兩軌的持有期定義——一周仍是 5 個交易日、1月~6月 仍是 20–120 個交易日。

## 要做什麼

### 356.1 三軌契約與欄位對應（不得更動既有兩軌）

| 軌 | 持有期 | 引擎 enum | DTO 欄位前綴 | 回測 horizon | 是否寄信 |
|---|---|---|---|---|---|
| 一周 | 約 5 個交易日 | `Horizon.SHORT` | `shortXxx`（沿用，語意不變） | `5` | 否 |
| 1周~1月 | 約 5–20 個交易日 | `Horizon.SWING` | `swingXxx`（新增） | `10`、`20` | 否 |
| 1月~6月 | 約 20–120 個交易日 | `Horizon.MEDIUM` | 無前綴（沿用，語意不變） | `60`、`120` | **是（唯一）** |

- [ ] **356.1a** 在 `TradingRadarRuleEngine` 新增巢狀 `public enum Horizon { SHORT, SWING, MEDIUM }`，取代目前貫穿 `evaluateHorizon`／`applyCandidatePolicy` 的 `boolean shortTerm` 參數。**不得**保留 boolean overload 與 enum 版本並存——兩條路徑會隨演進分歧。
- [ ] **356.1b** `TradingRadarRuleEngine.StockResult` 新增 `Integer swingScore`、`Action swingAction`、`List<String> swingReasons`、`List<String> swingRisks`。既有 component 的**順序與名稱一律不動**，新欄位一律追加在既有欄位之後（`normalizedBias`／`shortNormalizedBias` 之後），並保留既有相容建構式使舊測試仍可編譯。
- [ ] **356.1c** `horizonConflict` 的定義由「短 vs 中兩軌分組不同」改為「**三軌的動作分組不全相同**」。分組沿用既有定義：買進組（`BUY_CANDIDATE`／`ADD_CANDIDATE`／`TRIAL_BUY`）、中性組（`HOLD`／`WATCH`／`HOLD_CAUTION`／`WAIT`）、賣出組（`REDUCE_CANDIDATE`／`EXIT_CANDIDATE`／`AVOID`）、無法判定組（`NO_TRADE`）。
- [ ] **356.1d** `TradingRadarService` 的排序 key 由 `max(shortScore, score)` 改為 `max(shortScore, swingScore, score)`；缺值排最後，再以股票代碼穩定排序。既有「缺值排最後」語意不變。
- [ ] **356.1e** 資料不完整的早退分支（`complete(input)` 為 false）必須同時把三軌都填成 `NO_TRADE`／`score=null`，swing 軌的 risks 文案為「必要資料不足，1周~1月 軌今日不交易。」。**不得**只填兩軌而讓 swing 欄位為 `null` 物件。

### 356.2 週K 聚合（`WeeklyBarAggregator`，純計算、無 Spring 依賴）

- [ ] **356.2a** 新增 `com.steven.assets.service.WeeklyBarAggregator`，為 `final class` ＋ private 建構式 ＋ static 方法，**不注入任何 repository、不讀系統時間、不碰網路**（與 `RadarObservationResolver` 同一風格），使其可在無 Spring context 下單元測試。
- [ ] **356.2b** 週的單位為 `trading_date` 的 **ISO-8601 週**（週一為週首），以 `java.time.temporal.WeekFields.ISO` 的 `weekBasedYear` ＋ `weekOfWeekBasedYear` 組成 key。**必須用 week-based-year，不得用 `getYear()`**——否則 2026-12-28（ISO 2027-W01）會被歸進 2026 年的桶，跨年那一週被切成兩根。與市場時區無關：`trading_date` 本來就是該市場的當地交易日期，故同一段程式碼同時適用台股與美股。
- [ ] **356.2b-2** **分桶規則必須與 BFF 既有的 `ChartSeriesAligner.weekly(...)` 逐字一致**（同為 `WeekFields.ISO` 的 `weekBasedYear` ＋ `weekOfWeekBasedYear`，`open` 取該週最早交易日、`high`／`low` 取極值、`close` 取最晚交易日、`weekEndDate` 取該週最晚交易日）。理由：同一檔股票在「股票分析走勢圖的週K」與「交易雷達的週K」如果連**哪幾天算同一週、哪一天是週收盤**都不一樣，使用者無從解釋，這違反本專案「同義欄位必須同源」的規範。價基與進行中週的差異是刻意且已記載的（見背景段），**分桶規則的差異則不可接受**。
  > 本任務**不**把 `ChartSeriesAligner.weekly` 抽成共用元件：它在 BFF、吃的是 display DTO（`PricePointDto`）、且回傳整組畫圖用的欄位陣列；business-services 這一側吃的是還原後的 `StockPriceHistory` 實體、只需要 OHLCV。硬抽會讓 business 依賴 BFF 的 DTO（違反依賴方向）。**改以測試釘住兩者的分桶等價**：`WeeklyBarAggregator` 的測試須包含與 `ChartSeriesAlignerTest` **相同的週界案例**（一般週、短週、ISO 跨年、單日成週），並在 Javadoc 互相指名對方為必須同步的對應實作。
- [ ] **356.2c** 一根週K 由該週全部完成日 K 聚合：`open` 取該週**最早**交易日的 `openPrice`、`high` 取該週 `highPrice` 最大值、`low` 取該週 `lowPrice` 最小值、`close` 取該週**最晚**交易日的 `closePrice`、`volume` 為該週 `volume` 之和（任一日 `volume` 為 null 時整根週 `volume` 為 null，不得以 0 補）、`weekEndDate` 為該週最晚交易日的 `trading_date`。`open`／`high`／`low` 只要該週有任一日缺該欄即該欄為 null（不得以 `close` 冒充）；`close` 缺值時整根週K 無效並丟棄。
- [ ] **356.2d** **序列中 ISO 週最晚的那一根一律視為「進行中週」，不進入任何指標與評分。** 完成週的判定完全由序列自身決定：不查交易日曆、不讀系統時間、不做「今天是不是週五」的推論。理由必須寫進 Javadoc：(a) 回測可重現；(b) 同一週之內週K 因子不會逐日抖動，符合「週K 本來就是慢變數」；(c) 半天交易日、臨時休市與颱風假都不需要特例。代價是最新一週的資訊延遲最多五個交易日，這是刻意接受的取捨。
- [ ] **356.2e** `WeeklyBarAggregator` 的唯一輸出型別為 `record Aggregation(List<WeeklyBar> completedDesc, WeeklyBar currentPartial)`，其中 `WeeklyBar` 為 `record WeeklyBar(LocalDate weekEndDate, BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close, Long volume)`。`completedDesc` 為**降序**（新到舊），第 0 筆為最新**完成**週；`currentPartial` 為進行中週（可為 `null`）。

  > `currentPartial` **本任務不揭露到任何 DTO、不進 `StockInput`／`MarketInput`**，只作為聚合結果的完整表示與測試斷言對象（「最晚 ISO 週確實被排除」要有東西可斷言）。Requirement 93 所寫的「進行中週得以獨立欄位純揭露」是**允許**而非要求，本任務刻意不做，留給後續任務；不得因此在 `WeeklyIndicators` 裡塞一組沒有人讀的進行中週欄位。

### 356.3 週K 指標（`TechnicalIndicatorService` 既有公式，換序列不換公式）

- [ ] **356.3a** 週K 指標一律由 `TechnicalIndicatorService.computeFromSeries(List<StockPriceHistory>)`（既有 package-private 入口，吃任意序列）算出，只是餵進去的 `series` 換成週K 而非日K。**禁止為週K 另寫一份 KD／MACD／RSI／SMA 公式**——兩份實作必然隨演進分歧。Wilder RSI、DI 價基 MACD、EMA seed 與精度一律不改。`WeeklyBarAggregator` 與週K 組裝一律放在 `com.steven.assets.service` package，以便存取該入口。
- [ ] **356.3b** **`FullIndicators` 追加一個純輸出欄 `BigDecimal ma10 = simpleMa(series, 10)`。** 這是必要的：現行 `FullIndicators` 只有 MA5／MA20／MA60／MA240，**沒有任何 10 期 MA**；10 期只以 `ExtendedIndicators.bias10` 的中間值形式存在於 `biasRaw()` 內、從未外露。而 356.6a／356.6c／356.10a／356.12a 全部依賴週MA10。此追加**不改動任何既有公式與精度、不改動任何既有欄位**，日K 路徑因此多出一個 `ma10` 值但無人讀取（`StockInput` 不接、DTO 不揭露），輸出逐位不變；須有測試釘住此點。**不得**改以 `ma10 = close / (1 + bias10/100)` 反推——那是把顯示用的四捨五入值當成算術來源。

  > 新增 record component 會讓**全部** `new FullIndicators(...)` 建構點編譯失敗（可見、不會靜默）。`FullIndicators.EMPTY` 與 TAIEX／NASDAQ 兩條 `computeAllForXxx` 路徑一律填 `null`（那兩條路徑不需要 MA10），既有測試建構點同步補 `null`。
- [ ] **356.3c** 週K 指標集合固定為：`ma5`（5 完成週）、`ma10`、`ma20`、`k`／`d`／`j9`（KD(9,3,3)）、`dif`／`macd`／`osc`、`rsi5`／`rsi10`、`bias10`、`bias20`、`volumeRatio`、`changePercent`（最新完成週收盤 vs 前一完成週收盤）。

  > **`bias10`／`bias20` 一律由 assembler 以 `biasPercent(現價, 週MA10)`／`biasPercent(現價, 週MA20)` 另算，不得取用 `ExtendedIndicators.bias10`／`bias20`。** 後者的分子是**序列最新一根的收盤**（`TechnicalIndicatorService.biasRaw()`），不是現價；週K 的「最新一根收盤」是上一個完成週的最後交易日收盤，拿它算乖離等於用一週前的價格判斷「現在進場貴不貴」。日K 路徑的 `ma60BiasPercent` 早已是同一作法（`RadarInputAssembler` 內 `biasPercent(price, indicators.quarterlyMa())`），此處只是沿用同一慣例。
- [ ] **356.3c-2** **週K 的 `high`／`low` 為 null 時，該週不得進入週K 指標序列**（整根丟棄；丟棄後若剩餘完成週不足 60 根則整組週K 因子缺值）。理由：`TechnicalIndicatorService` 的 KD 與 DI 價基 MACD 內部都有「`high`／`low` 缺值時 fallback 用 `close`」的既有慣例（其 Javadoc 自述是為了 `0000` 大盤舊資料）。356.2c 已明訂週K 不得以 `close` 冒充 `open`／`high`／`low`，但若把 `high`／`low` 為 null 的週K 直接丟進 `computeFromSeries`，那條 fallback 會**靜默**把它變成一根收盤價＝最高＝最低的退化偽K，與 356.2c 的立意完全相反且無任何揭露。`stock_price_history.high_price`／`low_price`／`open_price` 與 `twse_index_daily_history.open_point`／`high_point`／`low_point` 都是 nullable，舊列確實有 null，這不是理論風險。
- [ ] **356.3d** **最少樣本 60 根完成週**：完成週不足 60 根時整組週K 指標為 `null`（不是部分算、不是以短序列硬算）。理由寫進具名常數 Javadoc：週MACD 的 OSC 要到第 34 根完成週才有第一個值（EMA26 以 26 根 SMA 作 seed → 第 26 根才有 DIF；signal 以 9 根 DIF 的 SMA 作 seed → 第 34 根才有 MACD），60 根留了 26 根的 EMA 收斂餘裕。**此數字為判斷性取值，無回測依據**，不得於任何文案宣稱它能提高準確度。
- [ ] **356.3e** `weeklyVolumeRatio` ＝ 最新完成週 `volume` ÷ **之前 20 根**正成交量完成週的中位數；**分母排除最新週**；正樣本少於 10 根、最新量為 null／0、或中位數非正時回 `null`。與日K 的 `volumeRatio` 同形，不得另立規則。
- [ ] **356.3f** 所有週K 指標輸出 `setScale(2, HALF_UP)`，缺值為 `null` 不是 0。

### 356.4 取數視窗擴大，但日K 路徑必須逐位不變

- [ ] **356.4a** `TradingRadarService.buildStock` 的 `priceHistoryRepo.findRecentN(code, market, 250)` 改為 **`findRecentN(code, market, 500)`**（≈100 個 ISO 週，扣掉 provenance／未來列剔除後仍足以取得 60 根完成週）。台股大盤 `twseRepo.findTopNByOrderByTradingDateDesc(241)` 與美股大盤 `usIndexDailyHistoryRepo.findTopNByIndexCodeOrderByTradingDateDesc(IXIC_CODE, 241)` 同步改為 `500`。

  > **台股大盤在查詢之後還有第二個 `.limit(241)`，兩處都必須改，只改查詢那個等於沒改。** `buildMarket` 的實際形狀是：查 241 → 若 `context.marketAsOfDate() != null` 則 `filter(不晚於 asOfDate).limit(241)`。而 `marketAsOfDate` 在有 TAIEX 資料時**恆非 null**（`TradingRadarMarketContextService` 直接取最新完成日），故那條分支是常態路徑。只改查詢的 241 → 序列仍被截回 241 列 ≈ 48 個 ISO 週 < 356.3d 的 60 根完成週 → **台股大盤四組週K 因子永遠缺值**，畫面只會多一則「不採計」風險句，沒有任何錯誤訊息。美股大盤路徑（`buildUsMarket` → `resolveMarketFromRows`）的量比與美股科技各有 20 筆／5 日上限，擴窗對其既有輸出逐位無影響，不需比照 356.4e 釘回。
- [ ] **356.4b** **日K 路徑必須收到與擴窗前逐位相同的輸入。** `RadarObservationResolver` 既有的 `indicatorSeriesRows` 維持 `INDICATOR_SERIES_MAX_ROWS = 241` 截斷不動；新增 `weeklySeriesRows`，套用**與 `indicatorSeriesRows` 完全相同**的過濾（不晚於 completed session、收盤為正且有限、等於 completed session 的最新一根台股仍須命中 provenance 白名單），**唯一差別是不做 241 截斷**。大盤兩處同理：切出最新 241 列餵既有路徑，長清單只供週K 聚合。
- [ ] **356.4c** **只查一次、只還原一次。** 長視窗取回後由 `DistributionAdjustedPriceService.adjust()` 還原**一次**，日K 與週K 都用這一份還原結果。**不得**改為兩次查詢或兩次還原——兩份長度不同的序列各自跑分割偵測啟發式，結果可能分歧，會讓日K 與週K 在同一筆決策中用上兩種價基（既有鐵則明文禁止）。
- [ ] **356.4d** `RadarInputAssembler.assemble(...)` 的簽章擴充為同時接收「完整長序列」與**顯式的日K 契約列數** `dailyContractRows`。`Assembled` 追加 `dailyCandle`、`weekly`（見 356.5／356.6）、`weeklyBarsDesc` 與**週K 的 `TechnicalIndicatorService.FullIndicators`**（供 356.11a 的 `dif`／`macd` 揭露欄，見 356.4h）；既有欄位順序與語意不動。

  > **日K 契約列數必須是顯式參數，不能靠「傳進來的 list 有多長」推導。** 擴窗之後 `combinedDesc.size()` 不再等於日K 契約，凡是直接吃整份 `adjustedRows` 的計算都會被長視窗改掉，而**不會有任何編譯錯誤或執行期例外**。

  > **⚠ `dailyContractRows` 是「完成列」契約（`241`），live K 不計入；實際視窗長度為 `dailyContractRows + (liveAdded ? 1 : 0)`。** 這是本任務最容易寫錯的一行。`RadarObservationResolver` 的 `INDICATOR_SERIES_MAX_ROWS = 241` 只截斷**完成列**，`TradingRadarService` 之後才在 index 0 插入 live K，故盤中 `combined.size()` 是 **242**（`RadarInputAssembler` 自己的 `indicatorRows = min(size, liveAdded ? FULL_WINDOW + 1 : FULL_WINDOW)` 就是在處理這件事）。若視窗寫成 `min(size, 241)`，盤中 `ma60BiasPercentile` 的觀測數會由 `182`（`i` 由 `firstCompleted=1` 跑到 `lastStart=242-60=182`）掉成 `181`，分位改值 → `TimingState` 改變 → `action` 改變——**正好破壞這一條原本要保護的不變式**。收盤後（`liveAdded=false`，`n=241`、`firstCompleted=0`）觀測數同樣是 182，兩種情形必須都是 182。

- [ ] **356.4d-2** 為了讓 356.4d 可實作，`RadarObservationResolver.INDICATOR_SERIES_MAX_ROWS` 的可見性由 `private` 改為 `public static final`。**只改修飾詞，值（`241`）與既有截斷行為一律不動。**

- [ ] **356.4e** **兩個已查證會被擴窗改掉的既有輸出，必須明確釘回日K 契約視窗**（這兩條是實作時最容易漏、且漏了完全靜默的地方）：

  | 既有輸出 | 現況（`RadarInputAssembler`） | 擴窗後若不處理會怎樣 | 本任務要求 |
  |---|---|---|---|
  | `ma60BiasPercentile` | `ma60BiasPercentile(adjustedRows, firstCompleted, ma60Bias)` 直接吃**整份** `adjustedRows`；觀測數 `= size − 60 + 1 − firstCompleted`，現況盤中（`n=242`、`firstCompleted=1`）與盤後（`n=241`、`firstCompleted=0`）皆為 **182 筆** | 500 列 → 441 筆觀測，分位值改變。它不是純揭露欄：`TimingState` 的極端超買／超賣分位路徑讀它，`describeBiasPercentileExtreme` 直接寫 `reasons`／`risks`，`TimingState` 又是 356.8b 四個閘門的輸入 → `action` 一併改變 | 一律改吃 `adjustedRows.subList(0, min(size, dailyContractRows + (liveAdded ? 1 : 0)))`——**必須含 live K 那一列，寫成 `min(size, 241)` 會讓盤中掉成 181 筆**（見 356.4d 的警告）。`BIAS_PCT_MIN_SAMPLES = 120` 不變，`RadarInputAssembler` 與 `TradingRadarRuleEngine.StockInput` 中「在自身**近一年**分布中的分位」的 Javadoc 語意列為**不得漂移的契約** |
  | `distributionAdjusted` | `= Adjustment.adjusted()`，而事件查詢區間 `fromDate` 取自 `combined` 最舊一列的 `tradingDate`（`TradingRadarService`），`adjust()` 再以序列首日二次過濾 | 除息日落在 12–24 個月前的標的，`adjusted` 由 `false` 翻 `true`，`reasons`／`shortReasons` 因此各多一則還原揭露句，`StockDecision.distributionAdjusted`（OpenAPI `required` 欄）也翻面 | `DistributionAdjustedPriceService.Adjustment` 追加 `List<LocalDate> appliedEventDates()`（growth ≠ 1 的事件日期，升冪）；assembler 改以「**存在事件日期嚴格晚於日K 契約視窗最舊一列的交易日**」判定 `distributionAdjusted`。「最舊一列」同樣以 `dailyContractRows + (liveAdded ? 1 : 0)` 界定，寫成 241 會讓邊界整體位移一列 |

  > **第二條不只是為了回歸穩定，它同時是更誠實的揭露。** `priceScale(i) = cumulative(i) / finalPriceGrowth`：若視窗內最舊一列的日期已晚於（或等於）全部事件日，視窗內每一列的 `cumulative` 都等於 `final`，`priceScale` 恆為 `1`，還原後的價格在**數值上**與原始價相同。此時宣稱「MA／KD 已使用還原權息價」是對使用者的假陳述——實際上什麼都沒還原。判準因此必須是「事件日期**嚴格晚於**視窗最舊一列」而不是「視窗日期區間內有事件」：事件日恰等於最舊一列時，該事件在該列即已計入 `cumulative`，視窗內仍無任何一列被縮放。
  >
  > **但「數值相同」不等於 `equals` 相同，測試必須用 `compareTo`。** 視窗內無事件時 `adjust()` 走 early-return 直接回**原物件**（`stock_price_history.close_price` 為 `numeric(15,4)`，scale 4）；一旦長視窗撈到 12–24 個月前的事件就改走縮放路徑，`scale()` 會 `setScale(8, HALF_UP)`。同一個價格因此由 `100.5000` 變成 `100.50000000`：`compareTo == 0` 但 `equals == false`。`completedCloses`／`adjustedRowsDesc` 都是這批物件。

- [ ] **356.4f** 其餘既有輸出已各自有界，**不得**在本次順手改動其視窗：`indicators`／`week52High`／`week52Low` 由 `indicatorRows = min(size, liveAdded ? 241 : 240)` 界定、`completedCloses` 由 `completedRowCount` 界定、`kdBandWidthPercent` 取前 9 列、`volumeRatio` 取前 21 列、`previousAdjustedClose`／`completedChangePercent` 取固定索引。
- [ ] **356.4h** `Assembled` 追加的週K `FullIndicators` 是 356.11a `WeeklyIndicators.dif`／`macd` 的**唯一來源**。`WeeklyInput`（356.6）刻意只帶 `osc`（進評分的只有 OSC），`dif`／`macd` 屬純揭露欄；兩者必須出自**同一次** `computeFromSeries(週K 序列)` 呼叫，不得為了揭露再算第二次。`DailyCandle.asOfDate` 取 `adjustedRows.get(firstCompleted).getTradingDate()`（最新完成日），由 assembler 一併輸出。
- [ ] **356.4g** 必須有回歸測試以**固定資料**斷言擴窗前後日K 路徑的 `indicators`、`completedCloses`、`week52High/Low`、`kdBandWidthPercent`、三個 `Confirmation`、`ma60BiasPercent`／`ma60BiasPercentile`、`volumeRatio`、`ruleChangePercent`、`distributionAdjusted`、以及最終 `score`／`action`／`reasons`／`risks` **逐位不變**。測試資料必須**刻意包含**一筆除息日落在第 241 列之外的事件，否則 356.4e 第二列的防護不會被覆蓋到。**不得**以「應該不會變」作為結論。

### 356.5 日K 棒因子（`DAILY_CANDLE`）

- [ ] **356.5a** 引擎新增 `public record CandleInput(BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close)`，由 `StockInput` 以 `dailyCandle` 欄位承載**最新完成日**的還原 OHLC。`MarketInput` 同樣新增 `dailyCandle`。
- [ ] **356.5b** 三個分量，每個各自 clamp 在 `[-1,1]`，**取可用值的平均**（缺值不計入平均、不以 0 冒充）：
  - **收盤區間位置**：`closePosition = (close - low) / (high - low)`，貢獻 `= clamp((closePosition - 0.5) * 2)`。收在上緣為正（買方主導），下緣為負。`high == low`（無波動或漲跌停鎖死）時此分量缺值。
  - **實體方向**：`sign(close - open)`，即 `+1`／`-1`／`0`。缺 `open` 時缺值。
  - **下影線比例**：`lowerShadow = (min(open, close) - low) / (high - low)`，貢獻 `= clamp((lowerShadow - 0.25) * 4)`。長下影線代表低檔有承接，為正貢獻；`0.25` 為中性點（四分之一全幅），是判斷性取值、無回測依據，須於 Javadoc 明寫。缺 `open`／`high`／`low` 或 `high == low` 時缺值。
- [ ] **356.5c** **三個分量抽成 `public static` 純函數（比照 `RadarInputAssembler.volumeRatio`／`bandWidthPercent` 的既有作法），引擎的因子計算與 DTO 的 `DailyCandle`／`WeeklyIndicators` 映射共用同一支，不得各寫一份。** 注意兩邊要的東西不同：DTO 要的是**未經 clamp／未經線性轉換的原值**（`closePosition ∈ [0,1]`、`bodyDirection ∈ {-1,0,1}`、`lowerShadowRatio ∈ [0,1]`），引擎要的是由原值再轉出的 contribution。純函數回原值，轉換留在引擎。
- [ ] **356.5d** 三個分量全部缺值時整個 `DAILY_CANDLE` 因子為 `null`，權重由 `Accumulator` 重分配。
- [ ] **356.5e** Javadoc 必須寫明本因子與 `completedChangePercent` **正交**：後者量「相對昨天走到哪」，前者量「當天這根 K 棒內部誰主導」；兩者不得合併計分，也不得因為「都跟當日漲跌有關」而互相取代。

### 356.6 週K 因子（四組）

引擎新增 `public record WeeklyInput(CandleInput candle, BigDecimal ma5, BigDecimal ma10, BigDecimal ma20, BigDecimal k, BigDecimal d, BigDecimal j9, BigDecimal osc, BigDecimal rsi5, BigDecimal rsi10, BigDecimal bias10, BigDecimal bias20, BigDecimal volumeRatio, BigDecimal changePercent, LocalDate weekEndDate, int completedWeeks)`，由 `StockInput` 與 `MarketInput` 各自以 `weekly` 欄位承載。`weekly == null` 或 `completedWeeks < 60` 時四組週K 因子**全部**缺值。

- [ ] **356.6a `WEEKLY_TREND`（週線趨勢）**：現價相對週 `ma5`／`ma10`／`ma20` 的位置，各為 `price > ma => +1`、`price < ma => -1`、相等 `0`，取可用值平均。沿用既有 `positionOf()` 的語意，不另立規則。三條週均線傳給 `positionOf()` 的**文案標籤固定為「週MA5」「週MA10」「週MA20」**。

  > **同時必須改掉既有日K MA5 因子的標籤。** 現行 `TradingRadarRuleEngine` 是 `positionOf(input.price(), input.weeklyMa(), "週線", reasons, risks)`，而 `positionOf` 會把標籤直接寫進 `reasons`／`risks`（「最新價位於{label}之上／之下」）。不改的話，同一筆決策的 reasons 會同時出現「最新價位於**週線**之上」（其實是日K 的 5 日 SMA）與「最新價位於**週MA5** 之上」（真正的週K），而「`weeklyMa` 命名為週線是措辭錯誤」正是 Requirement 93 的立案理由之一。**該標籤一律改為「日線 MA5」**，並以測試斷言同一份 `reasons`／`risks` 不得出現兩則含「週線」字樣的句子。
- [ ] **356.6b `WEEKLY_MOMENTUM`（週線動能）**：三個分量取可用值平均——(i) 週KD：`k > d => +1`、`k < d => -1`，再與 `clamp((50 - avg(k,d)) / 50)` 及 `clamp((50 - j9) / 50)` 取平均（使低檔有利於承接、高檔不鼓勵追價）；(ii) 週MACD：沿用 V12 建立的「OSC 相對現價幅度正規化」而非零軸硬翻面（避免零軸附近逐週抖動），公式為 `oscPct = osc / price * 100`、貢獻 `= clamp(oscPct / WEEKLY_OSC_FULL_SCALE_PCT)`。**必須新增具名常數 `WEEKLY_OSC_FULL_SCALE_PCT = 2.0`，不得直接沿用日K 的 `OSC_FULL_SCALE_PCT = 0.5`**——週 OSC 的幅度本來就大於日 OSC，沿用 `0.5` 會讓幾乎每一根週K 都打到 clamp 邊界而失去解析度。`2.0` 為**判斷性取值、無回測依據**，須於 Javadoc 明寫（比照既有 `OSC_FULL_SCALE_PCT` 的 Javadoc 慣例）；(iii) 週RSI：`clamp((50 - avg(rsi5, rsi10)) / 50)`。
- [ ] **356.6c `WEEKLY_BIAS`（週線乖離）**：`clamp(-bias10 / 15)` 與 `clamp(-bias20 / 20)` 取可用值平均。負乖離（現價在週均線下方）為正貢獻＝低接位置，正乖離為負貢獻＝追價成本。分母 `15`／`20` 比日K 的 `10`／`20` 寬，因為週均線的乖離本來就比日均線大；此為判斷性取值、無回測依據，須於 Javadoc 明寫。
- [ ] **356.6d `WEEKLY_CANDLE_VOLUME`（週K 棒與量能）**：三個分量取可用值平均——(i) 週收盤區間位置，公式同 356.5b 第一項但用週K 棒；(ii) 週實體方向 `sign(weeklyClose - weeklyOpen)`；(iii) 週量價確認，沿用日K 的四象限：`changePercent > 0 && volumeRatio >= 1.2 => +1`；`changePercent < 0 && volumeRatio >= 1.2 => -1`；`changePercent > 0 && volumeRatio <= 0.7 => -0.4`；`changePercent < 0 && volumeRatio <= 0.7 => +0.4`；其餘 `0`。
- [ ] **356.6e** 四組週K 因子全部缺值時，須在 `risks` 加入一則揭露：「週K 完成週不足 60 根（目前 N 根），本日不採計週線因子，缺值權重已重分配。」——**不得**沉默，也不得寫成「週線中性」。

### 356.7 三軌 × 23 因子權重表（本任務唯一契約）

全部為具名 `private static final double` 常數，命名 `SW_*`（一周）／`SWG_*`（1周~1月）／`MW_*`（1月~6月）。既有 `SW_*`／`MW_*` 常數名沿用，值依下表更新。

| 因子組 | 一周 | 1周~1月 | 1月~6月 |
|---|---:|---:|---:|
| 週線 MA5（日K 的 5 日 SMA，`weeklyMa`） | 0.05 | 0.04 | 0.02 |
| 月線 MA20 | 0.05 | 0.05 | 0.04 |
| 季線 MA60 | 0.03 | 0.05 | 0.06 |
| 年線 MA240 | 0.02 | 0.03 | 0.06 |
| KD／J（含 W%R 分量） | 0.12 | 0.08 | 0.05 |
| MACD | 0.07 | 0.06 | 0.04 |
| RSI | 0.05 | 0.04 | 0.03 |
| 乖離率 BIAS | 0.06 | 0.06 | 0.06 |
| 個股相對量 | 0.07 | 0.06 | 0.04 |
| 市場環境 | 0.08 | 0.06 | 0.05 |
| 完成日漲跌 | 0.03 | 0.02 | 0.01 |
| 匯率 | 0.03 | 0.03 | 0.03 |
| ETF 折溢價 | 0.02 | 0.02 | 0.02 |
| EPS 年增 | 0.02 | 0.04 | 0.06 |
| 近似 ROE | 0.02 | 0.04 | 0.06 |
| 近三月營收年增 | 0.03 | 0.03 | 0.04 |
| PE 自身分位 | 0.02 | 0.03 | 0.04 |
| 產業營收年增 | 0.07 | 0.08 | 0.10 |
| **日K 棒**（新增） | **0.06** | **0.03** | **0.01** |
| **週線趨勢**（新增） | **0.03** | **0.06** | **0.07** |
| **週線動能**（新增） | **0.03** | **0.05** | **0.05** |
| **週線乖離**（新增） | **0.02** | **0.02** | **0.03** |
| **週K 棒與量能**（新增） | **0.02** | **0.02** | **0.03** |
| **合計** | **1.00** | **1.00** | **1.00** |

> **上表 23 × 3 個權重全部是判斷性取值、無回測依據**——包含既有 18 個因子的新值。改動來源是「為 5 個新因子讓出 0.16–0.19 的權重，並讓每一列跨軌單調」，不是任何量測結果。**不得於任何 Javadoc、UI 或匯出文案宣稱這組權重能提高準確度或降低風險。** 356.13 的回測是事後稽核，不是這組數字的來源；若回測顯示某軌表現不佳，處理方式是如實揭露數字，不是回頭偷改權重（見 356.13d）。

- [ ] **356.7a** 三軌各自建立 `Accumulator`、各自加權累加；**不得**以「同一個分數套三組門檻」實作。缺值因子不進 `sumW`，其權重由可用因子重新正規化，**不得以 0 冒充缺值**。所有 contribution clamp 在 `[-1,1]`；`score = round(50 + 50 × weightedMean)` 並 clamp 在 `[0,100]`。
- [ ] **356.7b** 三軌的 `WEIGHT_SUM` 常數各自以編譯期加總表示（沿用既有 `SHORT_WEIGHT_SUM`／`MEDIUM_WEIGHT_SUM` 的寫法，新增 `SWING_WEIGHT_SUM`），並以測試斷言三者皆等於 `1.00`（容差 `1e-9`）。既有 `WEIGHT_SUM`（舊測試相容別名）繼續指向 `MEDIUM_WEIGHT_SUM`。
- [ ] **356.7c** **跨軌單調性必須被測試釘住**：對上表每一列，`一周 → 1周~1月 → 1月~6月` 的三個值必須是單調不增或單調不減，不得出現中間軌高於或低於兩端的鋸齒。測試以資料驅動逐列斷言。理由寫進 Javadoc：中間軌的存在理由就是「介於兩者之間」，非單調等於宣告它是第三套獨立直覺，那需要各自的回測依據，本任務沒有提供。
- [ ] **356.7d** 因子貢獻與其共用文案沿用 t305 建立的 `computeFactors()` **只算一次**、三軌各自加權的結構；`FactorContributions` 由 18 個欄位擴為 23 個，新增五欄追加在 `industry` 之後、`reasons`／`risks` 之前。

### 356.8 三軌的動作映射、閘門與極端保護

- [ ] **356.8a** 三軌都使用既有門檻映射：`>=75` 買進／加碼候選，`55–74` 持有／觀察，`40–54` 謹慎持有／等待，`25–39` 減碼候選／迴避，`<25` 出場候選／迴避（**沿用既有常數 `V12_ACTION_THRESHOLDS = (75, 55, 40, 25)`，三軌共用、不得改名為 `V15_...`**——該名稱指的是 V12 **參數集**而非 `RULE_VERSION`，V13／V14 兩次升版都沒動過它；改名會讓「參數集版本」與「production 規則版號」共用同一組前綴卻指不同東西。須於 Javadoc 註明此點）。
- [ ] **356.8b** 下列四項保護在**三軌各自**成立，不得只在其中兩軌實作：
  - **禁止追高與仍在下跌時買進**：`TimingState` 為 `OVERBOUGHT`／`EXTREME_OVERBOUGHT`、`KdHeat=OVERHEATED`、完成日漲幅 `>=5%`、完成日漲跌缺值或 `<0`、匯率分位 `>=90`、ETF 溢價 `>=3%`、大盤 stale，任一成立即關閉一般買進／加碼閘門。
  - **逢低承接**：沿用 `TRIAL_BUY` 的長期結構＋短期超賣轉強條件，並要求最近完成日 `completedChangePercent>=0`。
  - **極端超賣不殺低**：基礎動作若為減碼／出場／迴避而 `TimingState=EXTREME_OVERSOLD`，一律改為已持有 `HOLD_CAUTION`、未持有 `WAIT`。
  - **逢高獲利了結需多證據**：`profitTakingConfirmed` 必須同時位於 `EXTREME_OVERBOUGHT`，且 `kdDeadCross`／`OSC<0`／`completedChangePercent<0 && volumeRatio>=1.2` 三項至少兩項成立。
- [ ] **356.8c** `TimingState`、`KdHeat`、`profitTakingConfirmed`、`kdDeadCross`、`longTermBroken` 五個判定**維持每檔只算一次**、三軌共用，公式與門檻一律不改。新增的週K 與日K 棒因子**只進分數，不進這五個判定**——把新指標接進極端態判定會同時改動保護門檻，本任務沒有回測依據支持那麼做。此限制須寫進 Javadoc。

### 356.9 證據信心、evidence gate 與 V13 promotion 邊界

- [ ] **356.9a** `TradingRadarEvidenceConfidenceResolver.Horizon` 新增 `SWING`，其 group 權重為 `PRICE_TECHNICAL .40`、`MARKET_LIQUIDITY .25`、`VALUATION .10`、`FINANCIAL_OPERATING .10`、`ASSET_SPECIFIC .15`（合計 `1.00`，介於既有 SHORT 與 MEDIUM 之間）。測試須斷言三個 horizon 的 group 權重各自合計為 `1.00`。

  > **⚠ 光加 enum 值不會有任何效果，而且不會有編譯錯誤。** `TradingRadarEvidenceConfidenceResolver` 內的 horizon 分派**全部是二元三元運算**（`horizon == Horizon.SHORT ? shortXxx : mediumXxx`），`GroupEvidence` 也只有 `shortCoverage`／`mediumCoverage`、`shortAvailable`／`mediumAvailable`、`shortFresh`／`mediumFresh` 兩套欄位，`priceGroup` 只組 `shortComponents`／`mediumComponents` 兩份。新增 `SWING` 之後每一處都會**靜默落到 medium 分支**，結果是 `swingRiskCoverage`／`swingDownsideRisk` 保證與 medium 逐位相同、`EvidenceGroup.swingCoverage` 只是 `mediumCoverage` 的複本——看起來有做，其實整條 swing 證據鏈是假的。

- [ ] **356.9a-2** 因此必須一併完成：`GroupEvidence` 追加 `swingCoverage`／`swingAvailable`／`swingFresh`；各 group 新增第三份 `swingComponents` 與其權重；`coverage`／`available`／`fresh`／`meets`／`gateOpen`／`riskCoverage`／`downsideRisk` 等所有以 horizon 分派的方法一律改為 `switch (horizon)` 且**不得有 default 落到 medium**（新增第四個 horizon 時必須編譯失敗）；`risk(Inputs, LocalDate, Horizon)` 與 `dividendReason(dividend, horizon)` 必須明確定義 SWING 的視窗天數（既有為 SHORT `"5"`／MEDIUM `"20"`，SWING 用 `"20"` 但須是**顯式的 case**，不是落到 default）。測試須斷言：構造一組 short 與 medium 明顯不同的證據，`swing` 三欄**不得**恆等於 medium 三欄。
- [ ] **356.9b** `TradingRadarEvidenceGate.GatedActions` 擴為三軌。既有形狀為 `(mediumAction, shortAction, reasons, candidateMediumAction, candidateShortAction)`，**新增 `swingAction`（緊接 `shortAction`）與 `candidateSwingAction`（緊接 `candidateShortAction`）**——命名必須與既有兩軌對稱，不得寫成 `rawSwingAction`。`apply(...)` 對三軌各自套用既有的 buy 降級與 risk-evidence 判定；既有兩軌的 gate 行為不得改變。
- [ ] **356.9c** **1周~1月 軌不納入 V13 candidate／promotion 機制。** `evaluateCandidate`／`evaluatePromoted` 只解析 short／medium 兩把 key；swing 軌**一律**走 V15 baseline，任一 promoted key 都不得影響它。`composeTrackScopedResult` 的簽章擴為 `(medium, shortTerm, baseline)`，swing **四欄**（`swingScore`／`swingAction`／`swingReasons`／`swingRisks`）與三軌 `horizonConflict` 一律由 `baseline` 供給。

  > **這需要多呼叫一次 `evaluateStock(input)`，不能省。** 現行 `evaluatePromoted` 在**兩把 key 都 promoted** 時完全不會呼叫 `evaluateStock`，兩個參數都是 candidate 結果；照「從 medium 取 swing」的字面實作會讓 promoted 參數靜默污染 swing 軌——正是本條要禁止的事。故只要**任一** key promoted，就必須額外跑一次 baseline。同時 `composeTrackScopedResult` 內既有的 `horizonConflict = actionGroup(medium.action()) != actionGroup(shortTerm.shortAction())` 也要一併改為三軌比較（356.1c）。多一次全量 evaluate 的成本必須在完成報告中量測並列出。此限制須寫進 Javadoc，理由：promotion registry 的候選參數是以 `5／20／60／120` 對兩軌做樣本外校準選出的，沒有針對 swing 軌的 holdout 證據，硬套等於未經校準就上線。須以測試釘住——**不得**因為「registry 現在是空的」就留下未來會靜默生效的路徑。
- [ ] **356.9d** `RuleParameters` **不新增** swing 相關的 `ActionThresholds` 或 `CandidateWeight` enum 值。新增等於在 candidate 機制裡開一條沒有校準依據的路。

### 356.10 大盤（台股與美股）也吃週K 與日K 棒

- [ ] **356.10a** `MarketInput` 新增 `CandleInput dailyCandle` 與 `WeeklyInput weekly`。`evaluateMarket()` 在既有加減分之後、`clamp` 之前追加下列項；任一資料缺值即該項計 `0` 並於 `risks` 揭露「不採計」，**不得**寫成中性結論：
  - 現價 `>` 週MA10 加 `6`／`<` 減 `6`；現價 `>` 週MA20 加 `5`／`<` 減 `5`。
  - 週K `k > d` 加 `4`／`k < d` 減 `4`。
  - 週 `osc > 0` 加 `4`／`< 0` 減 `4`。
  - 最新完成週上漲且週量比 `>= 1.1` 加 `4`；下跌且 `>= 1.1` 減 `5`；上漲但 `<= 0.8` 減 `2`；下跌且 `<= 0.8` 加 `2`。
  - 日K 棒的收盤區間位置 `>= 0.7` 加 `3`；`<= 0.3` 減 `3`。

  > 上列全部加減分幅度（`±6`／`±5`／`±4`／`4`／`5`／`2`／`±3`）與門檻（`1.1`／`0.8`／`0.7`／`0.3`）**一律為判斷性取值、無回測依據**，須全部具名常數化並於 Javadoc 明寫此點（比照既有 `OSC_FULL_SCALE_PCT` 的 Javadoc 慣例）。不得於任何文案宣稱它們能提高準確度或降低風險。
- [ ] **356.10a-2** **`complete(MarketInput)` 一律不得納入 `weekly`／`dailyCandle`。** 該方法目前只檢查 price／changePercent／indicators／兩個 confirmation，其結果經 `MarketRegime.DATA_INCOMPLETE` 導出 `MarketSummary.dataComplete`。若「順手」把 weekly 併進去，完成週不足 60 的大盤會整組變 `DATA_INCOMPLETE`，連帶**關掉全部個股的買進閘門**。週K 缺值只影響加減分與揭露文字，不影響 `dataComplete`。
- [ ] **356.10b** 台股大盤（`TWSE`／`0000`）由 `twse_index_daily_history` 的 `open_point`／`high_point`／`low_point`／`close_point`／`trade_volume` 聚合週K；美股大盤（`IXIC`）由 `us_index_daily_history` 的 `open_point`／`high_point`／`low_point`／`close_point`／`volume` 聚合。兩者共用同一支 `WeeklyBarAggregator`（先各自映射成中性的 OHLCV 形狀再聚合，不得為每個來源各寫一份聚合）。
- [ ] **356.10c** `MarketSummary` 新增第 31 個 component `weeklyIndicators`（型別見 356.11），台股組與美股組各自算自己那一份。市場理由／風險文字須寫出週線方向；資料不足時揭露不採計。
- [ ] **356.10d** 美股個股走與台股個股**同一條**組裝與評分路徑（`RadarInputAssembler` → `TradingRadarRuleEngine`），因此週K 與日K 棒自動適用，不得為美股另開分支。

### 356.11 DTO、快照與 OpenAPI

- [ ] **356.11a** `TradingRadarDto` 新增兩個 record：
  - `DailyCandle(BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close, BigDecimal closePosition, BigDecimal bodyDirection, BigDecimal lowerShadowRatio, String asOfDate)`
  - `WeeklyIndicators(String weekEndDate, Integer completedWeeks, BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close, Long volume, BigDecimal ma5, BigDecimal ma10, BigDecimal ma20, BigDecimal k, BigDecimal d, BigDecimal j9, BigDecimal dif, BigDecimal macd, BigDecimal osc, BigDecimal rsi5, BigDecimal rsi10, BigDecimal bias10, BigDecimal bias20, BigDecimal volumeRatio, BigDecimal changePercent, BigDecimal closePosition, BigDecimal bodyDirection)`
- [ ] **356.11b** `StockDecision` 新增 11 個 component（**一律追加在既有 62 個之後**，順序固定）：`swingAction`、`swingActionLabel`、`swingScore`、`swingReasons`、`swingRisks`、`swingDownsideRisk`、`swingEvidenceConfidence`、`swingRiskCoverage`、`swingCandidateAction`、`dailyCandle`、`weeklyIndicators`。總數由 62 → **73**。既有相容建構式必須補上新欄位的預設值（`null`／`List.of()`）而非刪除。
- [ ] **356.11c** `RadarEvidence` 新增 `swingDownsideRisk`／`swingEvidenceConfidence`／`swingRiskCoverage`／`swingCandidateAction`；`EvidenceGroup` 新增 `swingCoverage`／`swingAvailable`／`swingFresh`。
- [ ] **356.11d** 所有新增 `BigDecimal` 顯示／匯出 2 位小數，缺值為 `null`／留白，**不得顯示 0**。
- [ ] **356.11e** `TradingRadarSnapshotStore` 無自訂 schema，須以 DTO round-trip 測試證明新欄位保存；**舊快照缺少新欄位時反序列化為 `null`／`false`，不得讀檔失敗**。

  > **缺值的 `List` 欄位會是 `null`，不是 `List.of()`。** `StockDecision` 是 record 且**沒有 compact constructor** 做正規化，Jackson 對缺欄一律給 `null`。前端、匯出與 `TradingRadarExportService` 必須自行處理 `null` list。若要改成空清單，必須明確加 compact constructor 並確認不影響既有欄位的既有行為——本任務**不做**這件事，只要求消費端容忍 `null`。
- [ ] **356.11f** `docs/openapi/docker-external-api.yaml` 必須在**同一個 commit** 內補齊全部新欄位——`StockDecision`、`RadarEvidence`、`EvidenceGroup`、`MarketSummary` 的 `properties` 與 **`required` 清單**，以及兩個新 schema `DailyCandle`／`WeeklyIndicators`。依 Requirement 86 的硬規則，文件不完整即視為功能未完成；既有 gateway／OpenAPI 雙向 contract test 必須綠燈。

  > **同一 commit 內還必須改 YAML 裡的 synthetic example，機械檢查抓不到它。** 現行 example 內有 `ruleVersion: TW_RULES_V14`（全樹唯一一處不屬 Java／Vue／測試的版號 hardcode），且 `market:`／`usMarket:` 兩個 inline example **逐欄列出全部 30 個 `MarketSummary` 欄位**；`TradingRadarResponse` 是 `additionalProperties: false` 且 `MarketSummary` 有完整 `required`，補了 `weeklyIndicators` 之後 example 就與 schema 不一致。既有的 OpenAPI 測試只驗「example 存不存在」、不驗內容，所以這一處**不會有任何紅燈提醒**。三處都要改：版號改 `TW_RULES_V15`、兩個 example 補 `weeklyIndicators: null`。
  >
  > 另須在 `TradingRadarOpenApiSchemaContractTest` 的 schema 綁定表與 nullable 表登錄兩個新 schema（`DailyCandle`／`WeeklyIndicators`），否則新 schema 不受該測試保護。

### 356.12 匯出、通知與前端

- [ ] **356.12a** `TradingRadarExportService` 個股決策工作表新增「1周~1月 動作」「1周~1月 分數」兩欄（緊接既有短期欄之後），以及週K 摘要欄（`週結束日`、`週MA10`、`週K`、`週D`、`週OSC`、`週量比`、`週漲跌%`）與日K 棒欄（`日K收盤位置`、`日K實體`、`日K下影線比`）。**另補上 1周~1月 軌的四個證據欄**（`1周~1月證據信心`、`1周~1月下檔風險`、`1周~1月風險覆蓋`、`1周~1月候選動作`），位置比照既有的短期／中期兩套；否則匯出會變成三軌分數卻只有兩軌證據。**既有表頭字串「短中期分歧」改為「持有期分歧」**（它現在描述的是三軌），並**同步更新既有測試 `TradingRadarDualFormatTest` 中對該表頭字串的斷言**。

  > **`TradingRadarExportService` 的 `headers`／`formats`／每列 cell 是三份必須同步的平行清單，只改表頭一定爆。** 程式內既有註解已寫明「headers／formats／rows 三者長度與順序必須一致——`ExportDoc.Table` 只在 **runtime** 才擲長度不符」，且 `formats` 是逐欄帶索引註解的 `ExportDoc.Format` 清單。本次一次新增 2（swing 動作／分數）＋7（週K）＋3（日K 棒）＋4（swing 證據）＝ **16 欄**，三份清單都要照同一順序插入。驗證段須有一條「表頭長度 ＝ formats 長度 ＝ 每列 cell 數」的斷言，Excel 與 JSON 各驗一次。大盤總覽工作表新增週線方向欄。Excel 與 JSON 走同一份 `ExportDoc`；舊快照對應欄位留白。
- [ ] **356.12b** **通知邊界不變**：`TradingRadarNotificationService` 只比較 **1月~6月** 軌的 action，`trading_radar_notification_setting` **不新增任何欄位**、不新增 changeset。通知設定 UI 的表單標籤由「中期建議（1–6 月）」改為「1 月~6 月建議」。
- [ ] **356.12c** `frontend/src/views/TradingRadarView.vue`：
  - 頁首 `page-sub` 文案改為三軌敘述，並明示新增日K 棒與週K。
  - 個股表收合列由兩欄（「短期（約一週）」「中期（1–6 月）」）改為**三欄**：「一周」「1周~1月」「1月~6月」，各自顯示 action tag 與分數。欄寬須重新配置，避免橫向溢位。
  - 展開列由兩組 reasons／risks 改為**三組**，標題分別為「一周支持訊號／風險提醒」「1周~1月 支持訊號／風險提醒」「1月~6月 支持訊號／風險提醒」。
  - 展開列新增「日K 棒」與「週K」兩個指標區塊，顯示 356.11a 的欄位；缺值顯示 `—` 不顯示 0。
  - 「短／中期下檔風險」「短期證據信心」「中期證據信心」等既有標籤同步改為三軌。
  - 大盤卡片（台股／美股兩個分頁）新增週線區塊。
  - 分歧欄（`el-table-column label="分歧"`）的 tag 文案由「**短中分歧**」（現行字串就是這四個字，沒有「期」）改為「持有期分歧」，欄頭「分歧」改為「持有期分歧」，收合列即可看見。
  - `RULE_VERSION` 的兩處 hardcode fallback（顯示 fallback 與 `radar` ref 初始值）同步改為 `TW_RULES_V15`。
- [ ] **356.12d** 免責文字補上：三軌分數只描述目前位置的相對獲利機會，不是獲利機率或報酬預測；`TW_RULES_V14` 與 `V15` 的分數不可直接比較。

### 356.13 回測

- [ ] **356.13a** `BacktestService.DEFAULT_HORIZONS` 由 `List.of(5, 20, 60, 120)` 改為 `List.of(5, 10, 20, 60, 120)`。回測與 production 共用同一份 `RadarInputAssembler`／`TradingRadarRuleEngine`，不得另寫一份。
- [ ] **356.13a-2** **`BacktestService` 自己的 `WINDOW = 241` 與 `WARMUP = RadarInputAssembler.FULL_WINDOW`（240）必須同步放大，否則本任務的回測是空跑。** 兩者是 `BacktestService` 內的獨立字面常數，**不會**因為「共用 assembler」而自動變大。241 個交易日 ≈ 48 個 ISO 週 < 356.3d 要求的 60 根完成週，`completedWeeks` 永遠不足 → 四組週K 因子在回測中恆為缺值並重分配權重 → 356.13b 量到的是一組**沒有週K 的規則**，卻要拿來當新權重的稽核。作法：`WINDOW` 放大至與 production 的長視窗一致（`500`），`WARMUP` 相應放大至能同時滿足「240 根完成日 K」與「60 根完成週」的較大者。日K 契約仍由 356.4d 的 `dailyContractRows = 241` 顯式界定，回測與 production 共用同一個值。
- [ ] **356.13a-3** **放大 `WARMUP` 會讓短序列標的掉出樣本，必須如實揭露而不是靜默少掉幾檔。** `spec/design.md` 的回測框架段自己記載「各標的交易日數自 318 至 2439 不等……扣掉 240 筆暖機後最短者只剩約 78 個可用交易日」；暖機拉到約 305 後，318 筆的標的只剩約 13 個可用訊號日，低於 `BacktestService.MIN_SAMPLES = 30` 而整檔被標記為樣本不足。完成報告必須列出「**因暖機放大而掉出 `MIN_SAMPLES` 的標的清單**」與「**逐標的週K 可用比例（`completedWeeks >= 60` 的訊號日佔比）**」，不得只報總表。
- [ ] **356.13b** 新增三軌述詞：一周買進、1周~1月 買進、1月~6月 買進、各軌獲利了結、極端超賣保護。完成報告須列出三軌各述詞在 `5／10／20／60／120` 的樣本數、平均／中位報酬、勝率、跌逾 10% 比例，以及對同標的同期間基準的差額。
- [ ] **356.13b-2** **回測自行組建的兩處 `MarketInput` 必須接上 `weekly` 與 `dailyCandle`，且台股大盤映射必須補上成交量。** `BacktestService` 對 IXIC 與 TAIEX 各有一處 `new TradingRadarRuleEngine.MarketInput(...)`；TAIEX 那處目前只映射 `openPrice/highPrice/lowPrice/closePrice`，**沒有帶 `volume`**（`TwseIndexDailyHistory.getTradeVolume()`），故回測的台股大盤週量比會恆為 null，而 production 由 `trade_volume` 算得出值。這正是 `BacktestService` 內既有註解記載的舊病：「線上生效、回測不生效，且沒有任何測試抓得到」，Task 323 的整個標題就是在講這件事，不得再犯一次。驗證段須有一條「回測與 production 對同一日 TAIEX 產生相同 `regime` 與相同週量比」的斷言。
- [ ] **356.13c** 回測的週K 聚合必須只使用該訊號日**當時已完成**的週：把長序列截到訊號日之後再聚合，**不得**把訊號日之後的日K 帶進當週。既有「大盤量能、美股科技與匯率一律傳入該訊號日 `14:00 Asia/Taipei` instant」的前視防護不變。
- [ ] **356.13d** **回測是稽核，結果不好也必須如實揭露**，不得因此隱藏或事後調參；要改本任務的權重或門檻必須先改規格再重審。

### 356.14 版號、註解掃描與不得做的事

- [ ] **356.14a** `TradingRadarRuleEngine.RULE_VERSION` 由 `TW_RULES_V14` 升為 **`TW_RULES_V15`**。同步 active Java／Vue／測試**與 `docs/openapi/docker-external-api.yaml` 的 synthetic example**（見 356.11f）。**禁止修改**已執行的 `v1.83.0-radar-notification-rule-version.sql` 內容或註解。版號不符時沿用既有機制只重建 1月~6月 軌的通知基準、首輪不寄信。
- [ ] **356.14b** 以 `rg -n` 掃描 active code 中所有「短期／中期」「雙軌」「兩軌」「`TW_RULES_V14`」「週線 MA5」等敘述，凡實際行為已改者一併修正 Javadoc、行內註解與畫面文案。**歷史任務檔、歷史版本敘述與 Liquibase changeset 不做批次取代。** 掃描範圍必須包含**執行期組出來的字串**（`positionOf`／`describeHeat` 等傳入的 label、`TradingRadarExportService` 的表頭常數、`TradingRadarView.vue` 的 tag 文案），不只是 Javadoc 與行內註解——本次已知的兩處是 `positionOf(..., "週線", ...)`（見 356.6a）與匯出表頭「短中期分歧」（見 356.12a）。
- [ ] **356.14c** **不得做的事**：不新增排程、不新增資料表或欄位、不寫 Liquibase changeset、不直接呼叫外部行情／新聞／匯率 API、不觸發爬蟲、不使用 AI／LLM、不擴大評分標的集合（維持 `目前持股 ∪ 觀察清單`）、不自動下單、不新增 9090 路由、不改動 `weeklyMa` 既有定義與欄位名、不改動 `TechnicalIndicatorService` 的任何**既有公式、既有欄位與精度**（356.3b 的 `ma10` 純追加輸出欄是唯一例外，且不得改動任何既有值）、不把新指標接進 `TimingState`／`KdHeat`／`profitTakingConfirmed` 判定。

## 驗證

### 規格守門與單元測試

```bash
bash scripts/spec-check.sh
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -f backend/pom.xml -DextraArgLine=-Dnet.bytebuddy.experimental=true test
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -f bff/pom.xml -DextraArgLine=-Dnet.bytebuddy.experimental=true test
```

```bash
cd frontend && /Users/steven/.nvm/versions/node/v22.21.0/bin/npm run build
```

測試至少須涵蓋：

- 三軌權重各自精確為 `1.00`（容差 `1e-9`）；23 列權重跨軌單調（資料驅動逐列斷言）；分數恆在 `[0,100]`。
- 任一 optional 因子為 `null` 時確實重分配而非計 `0`（對五個新因子各驗一次）。
- 構造「一周超買、1月~6月 結構完好」的輸入，斷言三軌動作確實不同；若實作退化為單一分數切三刀，此測試必失敗。
- `held=true/false` 的同一市場輸入產生相同三軌分數，只有 action label 類別不同。
- **擴窗回歸（356.4g）**：以固定資料斷言 `findRecentN` 由 250 改 500 後，日K 路徑的全部中間值與最終 `score`／`action`／`reasons`／`risks` 逐位不變。測試資料**必須刻意包含一筆除息日落在日K 契約視窗之外的事件**，用以覆蓋 `distributionAdjusted` 不得翻面；另須有一組專測 `ma60BiasPercentile` 的案例，**`liveAdded=true` 與 `liveAdded=false` 兩種都要驗，且兩者觀測數都必須是 182**（不是 441、也不是 181）。**全部價格比對一律用 `compareTo` / `isEqualByComparingTo`，不得用 `assertEquals` / `equals`**——BigDecimal scale 會由 4 變 8（見 356.4e 的說明段），用 `equals` 會紅燈在一個不是缺陷的地方。
- **`ma10` 純追加不影響日K**：以固定資料斷言 `FullIndicators` 加上 `ma10` 後，既有 `monthlyMa`／`quarterlyMa`／`annualMa`／`weeklyMa`／`k`／`d`／`extended` 全部逐位不變。
- **`positionOf` 標籤**：同一份 `reasons`＋`risks` 不得出現兩則含「週線」字樣的句子；日K MA5 的句子須為「日線 MA5」。
- **週MACD 常數**：斷言 `WEEKLY_OSC_FULL_SCALE_PCT = 2.0` 且與日K 的 `OSC_FULL_SCALE_PCT = 0.5` 為兩個獨立常數。
- **週K 聚合**：open 取該週最早日開盤、high／low 取極值、close 取最晚日收盤、volume 加總；某日 `volume` 為 null 時整根週 volume 為 null；最晚 ISO 週恆被排除；**跨年 ISO 週**（2026-12-28～2027-01-01 屬 ISO 2027-W01）不得被切成兩根；單日成週（該週只有一個交易日）正確成立。
- **完成週不足 60 根**時四組週K 因子全部缺值且 `risks` 有揭露句；剛好 60 根時可用。
- **週量比**分母排除最新週、取中位數、正樣本 < 10 回 null。
- **日K 棒因子**：`high == low`（漲跌停鎖死）三分量全缺 → 因子 null；缺 `open` → 只剩收盤位置分量；十字線（`close == open`）實體方向為 `0`；長下影線為正貢獻、長上影線為負貢獻。
- **三軌各自**的買進閘門（`completedChangePercent < 0` 即使高分也不得 BUY／ADD）、止跌後低接、極端超賣無條件阻擋殺低、高檔至少兩項轉弱才獲利了結。
- **promoted registry 不影響 swing 軌**：構造 short 與 medium 皆 promoted 的 registry，斷言 `swingAction`／`swingScore` 與純 baseline `evaluateStock` 完全相同。
- **大盤**：台股與美股 `MarketSummary.weeklyIndicators` 各自非空且來自各自的序列；週線資料不足時 risks 有「不採計」揭露且 `score` 不因此被當成中性。
- **通知**：transition 只看 1月~6月 軌；只有一周或 1周~1月 軌變動時不寄信；`RULE_VERSION` 不符時只重建基準、不寄信。
- **回測視窗**：斷言 `BacktestService.WINDOW` 已放大且 `dailyContractRows` 仍為 241；並以一段可重放序列斷言回測路徑的 `completedWeeks >= 60`（否則週K 因子恆缺值，回測是空跑）。
- **回測與 production 不分岔**：同一日 TAIEX 在回測與 production 產生相同 `regime` 與相同週量比（覆蓋 356.13b-2 的 `MarketInput` 補線與 `volume` 映射）。
- **swing 證據鏈不是 medium 的複本**：構造一組 short 與 medium 明顯不同的證據，斷言 `swingEvidenceConfidence`／`swingRiskCoverage`／`swingDownsideRisk` 與 `EvidenceGroup.swingCoverage` **不恆等於** medium 對應值；並斷言 horizon 分派已無 default 落 medium 的路徑。
- **promotion 不污染 swing**：兩把 key **都** promoted 時，`swingScore`／`swingAction`／`swingReasons`／`swingRisks` 仍與純 baseline `evaluateStock` 完全相同（此案例現行程式碼根本不會呼叫 baseline，是最容易錯的一支）。
- **大盤 `dataComplete` 不受週K 影響**：完成週不足 60 的大盤仍為 `dataComplete=true`、`regime != DATA_INCOMPLETE`，個股買進閘門不因此被關掉。
- **台股大盤週K 真的生效**：正常資料下 `MarketSummary.weeklyIndicators` 非 null（覆蓋 `buildMarket` 第二個 `.limit(...)` 是否也改到）。
- **缺 high／low 的週不得混進週K 指標**：構造某週有一日 `high` 為 null，斷言該週被丟棄，且週KD **不得**以 `close` 頂替高低（覆蓋 `computeFromSeries` 的既有 close fallback）。
- **匯出三份平行清單同步**：表頭長度 ＝ `formats` 長度 ＝ 每列 cell 數，Excel 與 JSON 各驗一次。
- **快照 round-trip**：新欄位保存；舊快照（缺 11 個新欄位）反序列化為 `null`／`false`／空清單且不拋例外。
- **匯出**：Excel 與 JSON 同時保留既有兩軌欄與新增 swing／週K／日K 棒欄；舊快照該欄留白。
- **OpenAPI／gateway 雙向對齊** contract test 綠燈。

### 回測

```bash
docker exec asset-business-services curl -fsS -X POST 'http://localhost:8080/internal/backtest/rules' \
  -H 'Content-Type: application/json' \
  -d '{"horizons":[5,10,20,60,120]}' > /tmp/t356-backtest.json
```

### 實際 Docker stack 驗收（本專案沒有 dev server）

```bash
cp /Users/steven/Project/asset-management/.env .
```

```bash
docker compose -p asset-management build --no-cache business-services bff frontend
```

```bash
docker compose -p asset-management up -d --no-deps --force-recreate business-services bff frontend
```

```bash
docker compose -p asset-management restart bff
```

```bash
docker exec asset-business-services curl -fsS 'http://localhost:8080/api/trading-radar' -H 'X-User-Id: 1' -H 'X-User-Role: ADMIN' -H 'X-User-Status: ACTIVE' > /tmp/t356-radar.json
```

```bash
curl -fsS 'http://127.0.0.1:9090/api/public/trading-radar/today' | head -c 2000
```

實資料回應須確認：

- `ruleVersion=TW_RULES_V15`；每檔同時有 `shortScore/shortAction`、`swingScore/swingAction` 與 `score/action` 三組，且三組不是同一個數字套三組門檻（至少存在一檔三軌分數互異）。
- `dailyCandle` 的 `open/high/low/close` 與該檔最新完成日 K 一致；`closePosition ∈ [0,1]`、`bodyDirection ∈ {-1,0,1}`、`lowerShadowRatio ∈ [0,1]`。
- `weeklyIndicators.weekEndDate` 為**上一個完成週**的最後交易日，**不是本週任何一天**；`completedWeeks >= 60` 的標的才有非空指標。
- 台股與美股兩張大盤卡各自有自己的 `weeklyIndicators`，數值不相同。
- 瀏覽器開啟 `/trading-radar` 可在收合列同時辨識三軌建議與持有期分歧，展開列可見日K 棒與週K 兩個新區塊。

## 完成報告

（實作者完成後回填：實際修改檔案清單、單元測試／前端 build／容器驗收輸出、五個 horizon 的三軌回測摘要、擴窗回歸測試的逐位比對結果、實資料抽查（含至少一檔三軌分數互異的證據與 `weekEndDate` 落在上一完成週的證據）、雷達建置延遲在擴窗前後的量測、active 註解掃描結果，以及與本計畫的偏差及原因。）
