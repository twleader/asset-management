# [t263] 觀察清單台股大盤改讀盤中即時點位，並修正即時點位的日期驗證與當日 OHLC

**對應 Requirements:** Requirement 14（觀察股票清單由警示條件衍生，含代號 `0000` = 台股大盤那一列的報價與技術指標）／Requirement 43（今日交易雷達的大盤盤中即時點位 `price:台股:0000`，由 `TaiexIndexPoller` 每 2 分鐘寫入 Redis）
**前置任務:** 無（Task 228 已建立 `TaiexIndexPoller` 與 `price:台股:0000`，本任務修正其缺漏並讓觀察清單接上）
**Liquibase changeset:** 無（不動 schema）

## 背景

### 現在的錯誤行為

**症狀 A —— 觀察清單的大盤那一列整個盤中停在昨日。** 「股票觀察」頁（route `/stocks`，`tab=watch`）台股頁籤的 `0000 台股大盤` 那一列，2026-07-31 13:41 實測顯示：

| 欄位 | 畫面值 | 實際等於 |
|---|---|---|
| 股價 | 39,933.30 | `twse_index_daily_history` **7/30** 的 `close_point` |
| 開盤 | 40,048.94 | 同表 7/30 的 `open_point` |
| 最高 / 最低 | 41,155.42 / 39,404.65 | 同表 7/30 的 `high_point` / `low_point` |
| 昨收 | 40,039.18 | 同表 **7/29** 的 `close_point` |

同一時刻 Redis `price:台股:0000` 的值為 `price=43200.1094`、`updatedAt=2026-07-31T13:28:00`，即當日盤中即時點位一直有在更新（每 2 分鐘），只是這一頁沒讀。

成因：`backend/src/main/java/com/steven/assets/service/WatchStockService.java` 的 `toIndexResponse(code, market)` 對 `0000` 走特例分支，`price` / `openPrice` / `highPrice` / `lowPrice` / `tradingDate` 一律取 `twseDailyRepo.findTop60ByOrderByTradingDateDesc()` 的第 0 筆、`previousClose` 取第 1 筆，並把 `closed` 寫死 `true`。而 `twse_index_daily_history` 的今日列要等 `TwseIndexPoller`（cron `0 0 14 * * MON-FRI`，Asia/Taipei）才寫入，故 09:00–14:00 整個盤中「最新一筆」恆為昨日。

**同一列的技術指標卻是今天的。** `TechnicalIndicatorService.computeAllForTaiex()`（Task 228）在完成日 K 未到今日時，會查 `priceQuery.getLive("0000","台股")`，若其 `tradingDate` 等於台北今日就暫加一筆合成列到序列最前，再算 MA20 / MA60 / MA240 / KD。於是畫面同時呈現「今天的月線季線年線 KD」與「昨天的股價開高低」，兩者不可能互相印證。

**正確行為：** 完成日 K 未到今日、而 Redis 有今日即時點位時，報價欄取即時點位、`closed=false`；其餘情形維持既有的完成日 K 行為。判定條件必須與 `computeAllForTaiex()` **語意等價**（對任何輸入判定結果逐次相同；不是文字照抄，見 263.4.1）。

**症狀 B —— 即時點位本身帶著昨日的污染值。** 同日實測 `price:台股:0000` 的 `lowPrice=39933.3008`，而 Yahoo `^TWII` 當日真實低點為 41610.41（當日 5 分格區間 41610.41–43214.36）。`39933.30` 正是 7/30 的收盤點位。

成因：`external-materials-service/src/main/java/com/steven/assets/externalmaterials/service/TaiexIndexPoller.java` 的 `updateOnce()` 只從 `macroClient.fetchIndexIntraday("TWSE")` 的回傳中「由後往前取第一筆非 null 的 `close`」，**完全忽略 `IndexIntradayPoint.time()`，不檢查該點位屬於哪一天**。而 `fetchIndexIntraday` 打的是 Yahoo `interval=5m&range=5d`，回傳橫跨最近五個交易日，內部以 `byDate.lastKey()` 取「最新交易日」——**Yahoo 尚未產生今日第一根 5 分格時，最新交易日就是昨日**。`@Scheduled(cron = "0 0/2 9-13 * * MON-FRI")` 的第一輪落在 09:00:00 整、今日 09:00 那格要到 09:05 才收，故**每個交易日開盤都會發生一次**。

該昨日點位接著經 `PriceCacheWriter.write` → `IntradayHighLowTracker.observe(code, market, tradingDate, price)` 併進 `price:dayhl:台股:0000:{今日}`，而聚合是 max/min 的單向累積，**錯誤的極值無法被後續正確值修正**，會持續整個交易日（該 key TTL 36 小時）。症狀 A 修好之後這個錯值會直接出現在使用者畫面的「最低」欄，故必須同批修掉。

**症狀 C —— `openPrice` 恆為 null。** `TaiexIndexPoller.updateOnce()` 建構 `PriceResult` 時 `openPrice` 明確傳 `null`，原註解為「MA/KD 判斷不吃這欄，留白不臆造」。該理由在觀察清單改為顯示這一列 OHLC 之後不再成立——「開盤」欄會恆顯示空白。

### 推翻了什麼

- 推翻 Requirement 14 的 AC「觀察清單列：`price` / `previousClose` 取 `twse_index_daily_history` 最新與次新；`openPrice` / `highPrice` / `lowPrice` 從同表 OHLC 欄位填」。該句成文於觀察清單只有完成日 K 可用的年代；`price:台股:0000` 是 Task 228 之後才存在的。
- 補正 Requirement 43 修訂 V6（Task 228）的 AC「取該次回傳中最新一筆非 null 收盤點位」——該句只約束「非 null」，未約束「屬於今日」。

### 不變的部分

- `twse_index_daily_history` 仍是**完成日 K 的唯一權威來源**，`TwseIndexPoller` 的盤後批次一行不改。
- 走勢圖股價側對 `0000` 明訂「完全不併 live」（Task 261）維持不變。
- 不新增任何 `@Scheduled`、不新增資料表、不新增外部資料來源（沿用同一個 Yahoo chart URL）。
- 規則引擎的因子組成、權重、正規化方式、動作門檻一律不動（升版只是標籤，見 263.6）。

### 會跟著變的部分（不要當成範圍外）

- **盤中 TAIEX 的 K／D 值會與修正前不同**，連帶影響觀察清單指標欄、交易雷達的 regime／score、走勢圖 KD 子圖今日點。詳見 263.3.6。
- **`RULE_VERSION` 由 `TW_RULES_V7` 升為 `TW_RULES_V8`**，含 `frontend/src/views/TradingRadarView.vue` 的兩行 hardcode fallback。詳見 263.6。這是本任務對 `frontend/` 的**全部**改動。

## 要做什麼

### 263.1 `MacroDataFetchClient` 新增當日 OHLC 摘要方法（不動既有方法）

檔案：`external-materials-service/src/main/java/com/steven/assets/externalmaterials/client/MacroDataFetchClient.java`

- [x] 263.1.1 新增 public record：

  ```java
  /** 指數當日 5 分 K 摘要（Task 263）：date 為該批點位所屬的交易所當地日期，呼叫端據此守門。 */
  public record DayQuote(java.time.LocalDate date, BigDecimal open, BigDecimal high,
                         BigDecimal low, BigDecimal latestClose) {}
  ```

- [x] 263.1.2 新增 `public DayQuote fetchIndexIntradayDay(String market)`：
  - URL 與既有 `fetchIndexIntraday` **完全相同**：`https://query2.finance.yahoo.com/v8/finance/chart/{symbol}?interval=5m&range=5d`，`symbol` 取自既有的 `INDEX_INTRADAY_YAHOO` map（`"TWSE" → "^TWII"`），`^` 需 `replace("^", "%5E")`。
  - 走既有的 `curlGetWithRetry(url, 2)`，**不得改動該方法的重試次數、退避秒數或逾時**（`TwRadarRefreshService` 的 12 秒等待上界依賴其現行行為）。
  - 時區取 `chart.result[0].meta.exchangeTimezoneName`，缺值 fallback `"Asia/Taipei"`（與既有方法一致）。
  - 逐格讀 `chart.result[0].indicators.quote[0]` 的 `open` / `high` / `low` / `close` 四個陣列與 `chart.result[0].timestamp`，以 `Instant.ofEpochSecond(ts).atZone(zone).toLocalDate()` 分日；**取日期最大的那一天**。（**與既有 `fetchIndexIntraday` 的 `byDate.lastKey()` 不完全相同**：後者在分組前就 `continue` 掉 close 為 null 的格，故它取的是「最新一個**有成交收盤**的交易日」；本方法對全部 `timestamp` 分日後取 max。兩者只在「今日有格但 close 整日皆 null」時分歧，而該情形經 263.3.2 的 `latestClose == null` 守門後行為一致，皆不寫入。）
  - 該日聚合：`open` = 該日**第一格**非 null 的 `open[i]`；`high` = 該日所有非 null `high[i]` 的最大值；`low` = 該日所有非 null `low[i]` 的最小值；`latestClose` = 該日**最後一格**非 null 的 `close[i]`。四個欄位各自獨立判 null（某陣列整日皆 null 時該欄為 null，不得因此丟棄整個 `DayQuote`）。
  - 數值一律經既有的 `jsonDecimal4(JsonNode)`（`BigDecimal.valueOf(v.asDouble()).setScale(4, RoundingMode.HALF_UP)`），與既有分時點位同精度。
  - 查無資料、無 `timestamp` 陣列、或例外時回 `null`（記 `log.warn`，比照既有方法回空集合的降級語意）。
- [x] 263.1.3 **既有 `fetchIndexIntraday(String market)` 一行不改**。它的回傳型別 `IndexIntradayPoint(String time, BigDecimal close)` 只有兩欄、且會補滿整個交易時段的 5 分格（未到的時段 `close` 為 null）以固定圖表 x 軸，供「股市大盤查詢」頁的「當日走勢」使用；改它會動到那一頁。兩支方法各自解析同一份 JSON 是刻意的重複。

### 263.2 `PriceCacheWriter` 新增「不做本地 high/low 聚合」的寫入路徑

檔案：`external-materials-service/src/main/java/com/steven/assets/externalmaterials/service/PriceCacheWriter.java`

- [x] 263.2.1 把現有 `public void write(PriceResult result, boolean markClosed)` 改為委派：

  ```java
  public void write(PriceResult result, boolean markClosed) {
      write(result, markClosed, true);
  }

  public void write(PriceResult result, boolean markClosed, boolean aggregateHighLow) { ... }
  ```

  三參數版承接原本的全部內容，**唯一差別**是 high/low 的取得：

  ```java
  BigDecimal mergedHigh, mergedLow;
  if (aggregateHighLow) {
      IntradayHighLowTracker.HighLow agg = hlTracker.observe(code, market, tradingDate, result.price());
      mergedHigh = mergeHigh(result.highPrice(), agg.high());
      mergedLow  = mergeLow(result.lowPrice(),  agg.low());
  } else {
      mergedHigh = result.highPrice();
      mergedLow  = result.lowPrice();
  }
  ```

  `aggregateHighLow=false` 時**不得呼叫** `hlTracker.observe`（呼叫即寫入 `price:dayhl:*`，污染同樣會發生）。
- [x] 263.2.2 其餘所有行為（payload 欄位、TTL 24h、`price:index:{market}` set、`convertAndSend("price-update", json)`、`source` 含 `(` 就不進 `IntradayTickStore` 的 tick 守門）**逐行不變**。既有所有呼叫端走兩參數版 → 個股路徑行為完全不變。

### 263.3 `TaiexIndexPoller` 驗證日期、補當日 OHLC、不走本地聚合

檔案：`external-materials-service/src/main/java/com/steven/assets/externalmaterials/service/TaiexIndexPoller.java`

- [x] 263.3.1 `updateOnce()` 改用 `macroClient.fetchIndexIntradayDay("TWSE")`。
- [x] 263.3.2 **日期守門**：取得的 `DayQuote` 為 null、其 `date` 不等於 `LocalDate.now(MarketClock.TW_ZONE)`、或 `latestClose` 為 null 時，**整輪不寫入**（直接 return），保留 Redis 內上一輪真實值。不得以昨日點位或空值覆寫——這與 Task 228 既有「查無有效點位不寫」的處置相同，只是判定條件補上了日期。
  > **連帶效果，驗證時不要誤判為 regression：** 今日第一根 5 分格產生前（約 09:00–09:05），「今日交易雷達」頁的大盤卡會顯示 `stale=true`／`intraday=false`（`backend/src/main/java/com/steven/assets/service/TradingRadarService.java` 以 live 的 `tradingDate` 是否等於當前交易日判 `liveFreshToday`，第 205-206 行 `stale = !todayEodPresent && !liveFreshToday`）。修正前那一輪會把昨日點位寫進 Redis、而 `PriceCacheWriter` 蓋上的 `tradingDate` 來自 `TradingDateResolver`（盤中恆為今日），於是雷達顯示 `stale=false`／`intraday=true`——那是**用昨日點位假裝今日即時**。修正後短暫顯示「資料過時」才是誠實語意，且 `stale` 的效果全部往保守方向（買進閘門關閉、`RISK_OFF` 扣分仍生效）。**不得為了消除這幾分鐘的 `stale` 而放寬日期守門。**
- [x] 263.3.3 `PriceResult` 的 `openPrice` / `highPrice` / `lowPrice` 改為填入 `DayQuote` 的 `open` / `high` / `low`（原本三者分別為 `null` / `null` / `null`，靠 `PriceCacheWriter` 的本地聚合補 high/low）。`price` 填 `latestClose`。
- [x] 263.3.4 `previousClose` 改為「`twse_index_daily_history` 中 `trading_date` **嚴格早於 `DayQuote.date`** 的最後一筆 `close_point`」。現行 `source.loadRecentTaiexCloses(1)` 取的是無條件最新一筆——`TwseIndexPoller` 於 14:00 寫入今日完成日 K 之後，「最新一筆」即為今日，昨收會變成今日收盤。改法：呼叫 `source.loadRecentTaiexCloses(2)`（回傳為 `List<StockSourceQuery.ClosePoint>`，已在方法內 `Collections.reverse` 成**升冪**），由後往前取第一筆 `date().isBefore(dayQuote.date())` 者；找不到則 `previousClose = null`（下游 `PriceCacheWriter` 對 null 昨收會讓 `priceChange`/`changePercent` 為 null，屬既有行為）。**不得修改 `StockSourceQuery.loadRecentTaiexCloses` 的 SQL**。
  > 現行 cron（`0 0/2 9-13`）與 `MarketClock.isTwMarketOpen()`（上界 13:30）使 poller 不會在 14:00 之後執行，故這是防禦性修正、當前不改變任何輸出值。仍要做：`TwRadarRefreshService` 亦會呼叫 `updateOnce()`，其守門是 `clock.isTwMarketOpen()`，與 cron 各自獨立。
- [x] 263.3.5 寫入改為 `writer.write(result, false, false)`（第三參數 `aggregateHighLow=false`）。理由寫進 javadoc：Yahoo 5 分 K 本就提供整日 high/low 陣列，本地聚合的存在理由（外部 API 不給 dayrange，起因為 NASDAQ 對 ETF 的 `keyStats` 為 null）對大盤不成立；且聚合的 max/min 語意使誤入的極值無法被後續正確值修正。
- [x] 263.3.6 **連帶效果：本變更改變 `price:台股:0000` 的 high／low 語意，盤中 TAIEX 的 K／D 值會與修正前不同。這是修正，不是 regression。** 現況 `TaiexIndexPoller` 傳 `highPrice`/`lowPrice = null`，`PriceCacheWriter` 的 `mergeHigh(null, agg)` 回聚合值 → Redis 的 high／low 實際上是**「5 分格收盤價」的本地 max/min**；改後是 **Yahoo「5 分格 high／low 陣列」的 max/min**，區間必然變寬（實測 2026-07-31：low 由 39933.30 變 41610.41，前者還疊了昨日點位的污染）。下游有三個消費者，全部直接進 KD 的 RSV 分母：
  - `TechnicalIndicatorService.java:310-311`（`computeAllForTaiex` 合成今日列的 `setHighPoint`／`setLowPoint`）→ 觀察清單 `0000` 列的 K／D 欄。
  - `TechnicalIndicatorService.java:223-224`（`taiexSeriesAsc` 的今日點，Task 261）→ 走勢圖 KD 子圖的今日點。
  - 前者再經 `TradingRadarService.buildMarket()` → `MarketSummary.kValue`/`dValue` → `TradingRadarRuleEngine.evaluateMarket()` 的 regime／score（Requirement 43），並可能連動 Requirement 44 的狀態轉換寄信。

  **因此 `TradingRadarRuleEngine.RULE_VERSION` 必須升為 `TW_RULES_V8`，見 263.6。** 本專案的升版判準不是「公式有沒有變」——Task 228（V6）與 Task 232（V7）**都明文寫著「因子組成、權重與正規化方式完全相同」卻照樣升版**，理由都是「使用者可觀察行為有實質變化」。唯一的不升版先例 Task 249 之所以成立，是因為「同一份輸入在修訂前後產生**完全相同的輸出**」——本任務明確不符合（K／D、regime／score 都會變）。
- [x] 263.3.7 `source` 字串維持 `"TWSE指數(5m)"`（**含括號**，`PriceCacheWriter` 據此不把大盤併入 `IntradayTickStore`）；`stockName` 維持 `"台股大盤"`；`buyPrice` / `sellPrice` / `volume` 維持 `null`；`change` / `changePct` 維持 `null`（由 `PriceCacheWriter` 依 `previousClose` 自算）。`@Scheduled` 的 cron、zone、`clock.isTwMarketOpen()` 守門**全部不變**。

### 263.4 觀察清單大盤列改讀盤中即時點位

檔案：`backend/src/main/java/com/steven/assets/service/WatchStockService.java`，方法 `toIndexResponse(String code, String market)`

- [x] 263.4.1 判定盤中即時。**條件必須與 `TechnicalIndicatorService.computeAllForTaiex()` 語意等價——即對任何輸入，兩者的判定結果必須逐次相同**；不是要求文字照抄（那與 263.4.8「不得改動 `TechnicalIndicatorService` 的任何一行」互斥，且兩邊的區域變數形狀本就不同）。對照基準是 `backend/src/main/java/com/steven/assets/service/TechnicalIndicatorService.java:301-305` 的實際五行：

  ```java
  LocalDate today = LocalDate.now(MarketZones.TW_ZONE);
  if (desc.isEmpty() || !today.equals(desc.get(0).getTradingDate())) {
      Optional<PriceQueryService.LivePrice> liveOpt = priceQuery.getLive("0000", "台股");
      if (liveOpt.isPresent() && liveOpt.get().tradingDate() != null
              && today.toString().equals(liveOpt.get().tradingDate())) {
  ```

  本方法要寫的等價形（`MarketZones.today("台股")` 內部即 `LocalDate.now(resolve("台股"))`，而 `resolve("台股")` 回 `TW_ZONE`，兩者恆等）：

  ```java
  LocalDate today = MarketZones.today("台股");          // = LocalDate.now(Asia/Taipei)
  boolean eodHasToday = !recent.isEmpty() && today.equals(recent.get(0).getTradingDate());
  Optional<PriceQueryService.LivePrice> liveOpt = eodHasToday
          ? Optional.empty()
          : priceQuery.getLive("0000", "台股");
  boolean useLive = liveOpt.isPresent()
          && liveOpt.get().tradingDate() != null
          && today.toString().equals(liveOpt.get().tradingDate());
  ```

  其中 `recent` 為既有的 `twseDailyRepo.findTop60ByOrderByTradingDateDesc()`（降冪，第 0 筆最新）。
- [x] 263.4.2 `useLive == true` 時：`price` = `live.price()`、`openPrice` = `live.openPrice()`、`highPrice` = `live.highPrice()`、`lowPrice` = `live.lowPrice()`、`tradingDate` = `live.tradingDate()`、`closed = false`。**這五欄全部取自 live，不得逐欄 fallback 回完成日 K**——混搭會讓同一列出現兩個日期的值（`openPrice` 為 null 時就顯示「—」，那是誠實的空值）。
- [x] 263.4.3 `useLive == false` 時：維持既有行為，五欄取 `recent.get(0)` 的 `closePoint` / `openPoint` / `highPoint` / `lowPoint` / `tradingDate`，`closed = true`。`recent` 為空時全部維持 null（既有行為）。
- [x] 263.4.4 `previousClose` 兩種情形**共用同一條規則**：`recent` 中 `getTradingDate()` **嚴格早於**當列 `tradingDate` 的第一筆（`recent` 已降冪，即由前往後找第一筆 `isBefore(displayDate)`）的 `closePoint`。盤中顯示日為今日 → 昨收即 `recent.get(0)`；盤後顯示日為 `recent.get(0)` 的日期 → 昨收為 `recent.get(1)`，與既有行為一致。**不得改讀 Redis payload 的 `live.previousClose()`**：「股市大盤查詢」頁當日卡的昨收讀同一張日線表、且用同一條「嚴格早於顯示日的最後一筆」規則（`GdpTwseBffController`），兩頁的昨收是同義欄位、必須同一事實來源。
- [x] 263.4.5 `priceChange` / `changePercent` 一律由 `price` 與 `previousClose` 現算，維持既有算式不變：

  ```java
  BigDecimal diff = price.subtract(previousClose);
  priceChange   = diff.setScale(4, RoundingMode.HALF_UP);
  changePercent = diff.divide(previousClose, 6, RoundingMode.HALF_UP)
                      .multiply(BigDecimal.valueOf(100)).setScale(4, RoundingMode.HALF_UP);
  ```

  （`previousClose == null` 或 `signum() == 0` 時兩者為 null，既有行為。）**不得改用 `live.priceChange()` / `live.changePercent()`**——那是 `PriceCacheWriter` 依它自己那份昨收算的，與 263.4.4 的昨收來源不保證一致。
- [x] 263.4.6 `buyPrice` / `sellPrice` / `volume` **恆為 null**（大盤無買賣盤口、無成交量定義），即使 live payload 出現這三欄亦不得採用。`stockName` 維持常數 `TAIEX_INDEX_NAME`（`"台股大盤"`）。
- [x] 263.4.7 本方法其餘部分（警示條件 `buildConditions`、群組合併、`lastAlert`、`indicatorService.computeAll(code, market)` 填入的五個指標欄）**逐行不變**。
- [x] 263.4.8 **不得順手改動**：`HistoricalDataService.getStockHistory` 對 `0000` 的今日格規則（走勢圖股價側明訂完全不併 live，Task 261）、`TechnicalIndicatorService` 的任何一行、`TwseIndexPoller`、`StockAlertService` 對 `0000` 的守門。

### 263.5 測試

- [x] 263.5.1 **改既有** `external-materials-service/src/test/java/com/steven/assets/externalmaterials/service/TaiexIndexPollerTest.java`：現有三個 case（`noValidPoints_doesNotWriteRedis`、`emptyPoints_doesNotWriteRedis`、`latestNonNullClose_priceResultFieldsAreCorrect`）stub 的是 `macroClient.fetchIndexIntraday("TWSE")`，改後該方法不再被呼叫，必須改為 stub `fetchIndexIntradayDay("TWSE")`、並把 `verify(writer, never()).write(any(), anyBoolean())` 改為對三參數版的 verify。**另有一個安靜失敗的 stub 要一併改**：`latestNonNullClose_priceResultFieldsAreCorrect` 現在 stub 的是 `when(source.loadRecentTaiexCloses(1))`，而 263.3.4 把生產碼改成 `loadRecentTaiexCloses(2)`；該檔用的是 `mock(...)` 而非 `@Mock`＋`MockitoExtension`，**不會**拋 `UnnecessaryStubbingException`，而是安靜回空 list → `previousClose` 為 null → 既有的 `assertThat(result.previousClose()).isEqualByComparingTo("19800.00")` 掛在 null 上。改為 stub `(2)`，且回傳的 `ClosePoint.date` **必須嚴格早於** stub 的 `DayQuote.date`，否則 263.3.4 的新規則會把它濾掉、`previousClose` 仍為 null。
- [x] 263.5.2 **新增** `TaiexIndexPollerTest` case：
  - `dayQuoteIsYesterday_doesNotWriteRedis`：`DayQuote.date` 為昨日 → `verify(writer, never()).write(any(), anyBoolean(), anyBoolean())`。
  - `dayQuoteIsToday_writesOhlcFromSource`：`date` 為今日、`open/high/low/latestClose` 皆有值 → 捕獲 `PriceResult`，斷言四欄逐一等於 `DayQuote` 的對應值。
  - `dayQuoteIsToday_writesWithAggregateHighLowFalse`：`verify(writer).write(any(), eq(false), eq(false))`。
  - `previousClose_isStrictlyBeforePointDate`：`loadRecentTaiexCloses(2)` 回傳含「與 `DayQuote.date` 同日」與「前一交易日」兩筆 → 斷言 `previousClose` 取的是前一交易日那筆。
  - `nullDayQuote_doesNotWriteRedis`：`fetchIndexIntradayDay` 回 null → 不寫入。
- [x] 263.5.3 **改既有** `external-materials-service/src/test/java/com/steven/assets/externalmaterials/service/PriceCacheWriterTest.java`。**該檔現況與初稿假設不同，照以下四步做**：
  1. 現有四個 `@Test`（`coldCache_usesPreviousDbCloseAndCalculatesChange`、`flatClose_writesNumericZeros`、`staleRedisPreviousClose_isIgnoredInFavorOfDb`、`noPreviousHistory_omitsDerivedFields`）**全部走 `syncAndCapture()` → `writer.syncClosedFromDb(...)`**，`PriceCacheWriter.write` 在本檔（實則全 `external-materials-service` 測試樹）**零覆蓋**。故 263.5.3 是**新增**兩支 case，不是「保留既有的」。
  2. `IntradayHighLowTracker` 目前是 `setUp()` 內建構時的**匿名 inline mock**（`new PriceCacheWriter(redis, source, mock(IntradayHighLowTracker.class), mock(IntradayTickStore.class), tradingDateResolver)`），沒有欄位持有、無法 verify。先把它提為欄位（比照同檔既有的 `redis` / `values` / `source` / `writer` 四個欄位寫法），在 `setUp()` 內 `hlTracker = mock(IntradayHighLowTracker.class);` 後再傳入建構子。
  3. 新增兩支 case：
     - **聚合仍生效（個股路徑迴歸錨點）**：呼叫 `writer.write(result, false)`（兩參數版），**必須先 stub** `when(hlTracker.observe(any(), any(), any(), any())).thenReturn(new IntradayHighLowTracker.HighLow(h, l))`——未 stub 的 mock 回 null，`PriceCacheWriter` 第 61-64 行 `agg.high()` 會直接 NPE。斷言 payload 的 `highPrice`/`lowPrice` 為外部值與聚合值 merge 後的 max/min。
     - **大盤路徑不聚合**：呼叫 `writer.write(result, false, false)`，`verify(hlTracker, never()).observe(any(), any(), any(), any())`，且 payload 的 `highPrice`/`lowPrice` 逐字等於 `result` 帶的值。
  4. 捕獲 payload 沿用同檔既有 `syncAndCapture()` 的手法（`ArgumentCaptor<String>` ＋ `verify(values).set(anyString(), json.capture(), any())` ＋ `MAPPER.readTree`），但**不可**直接重用該 helper（它呼叫的是 `syncClosedFromDb`）；另寫一支 `writeAndCapture(PriceResult, boolean, Boolean)` 或在 case 內 inline。
- [x] 263.5.4 **新增** `backend/src/test/java/com/steven/assets/service/WatchStockTaiexIntradayTest.java`（JUnit 5 + `@ExtendWith(MockitoExtension.class)`，比照既有 `TechnicalIndicatorTaiexLiveBlendTest` 的風格，`@Mock` 掉 `WatchStockService` 的七個建構子相依：`StockAlertRepository`、`StockAlertGroupRepository`、`PriceQueryService`、`StockPriceHistoryRepository`、`StockRepository`、`TechnicalIndicatorService`、`TwseIndexDailyHistoryRepository`）。

  **兩個 mock 陷阱，不處理會全紅：**
  1. **四個 case 皆須先 stub** `when(indicatorService.computeAll("0000", "台股")).thenReturn(TechnicalIndicatorService.FullIndicators.EMPTY)`（`EMPTY` 定義於 `TechnicalIndicatorService.java:47`）。`toIndexResponse` 必然走到 `indicatorService.computeAll(...)`，未 stub 的 mock 回 `null`，接著 builder 的 `.monthlyMa(ind.monthlyMa())` 直接 NPE。這與 263.5.3 第 3 步的 `hlTracker.observe` 是同一個坑，換一支測試而已。
  2. 「完成日 K 已含今日」那個 case **不得** stub `priceQuery.getLive`。263.4.1 的等價形是 `eodHasToday ? Optional.empty() : priceQuery.getLive(...)`，該分支根本不呼叫它；`MockitoExtension` 預設 STRICT_STUBS，多餘的 stub 會拋 `UnnecessaryStubbingException`。

  覆蓋：
  - 完成日 K 最新為昨日、live `tradingDate` 為今日 → `price`/`openPrice`/`highPrice`/`lowPrice` 取自 live、`tradingDate` 為今日、`closed=false`、`previousClose` 等於完成日 K 最新一筆（昨日）的 `closePoint`。
  - 同上但 `priceQuery.getLive` 回 `Optional.empty()` → 五欄取完成日 K 最新一筆、`closed=true`、`previousClose` 等於次新一筆。
  - live 存在但 `tradingDate` 為昨日 → 同樣退回完成日 K（**不得把昨日點位當今日報價**）。
  - 完成日 K 最新已是今日 → 不併 live、`closed=true`、`previousClose` 等於次新一筆。
  - 四種情形皆斷言 `buyPrice`/`sellPrice`/`volume` 為 null。
  - **昨收規則的單側迴歸錨點**：以含**跳日**的日線列（例如 `2026-07-24` → `2026-07-27` 跨週末、`2026-07-29`、`2026-07-30`）斷言新規則在盤中情形回 `recent.get(0)` 的 `closePoint`、盤後情形回 `recent.get(1)` 的 `closePoint`。
    > **這條錨點只釘得住 business 這一側，要誠實看待。** 263.4.4 的規則在本專案有**兩套獨立實作**：BFF 側 `bff/src/main/java/com/steven/assets/bff/gdptwse/GdpTwseBffController.java:298-316` 的 `previousCloseBefore(daily, beforeDate)`（private、字串字典序比對、跑在 bff 模組、`bff/src/test` 現有四支測試皆未涵蓋它），與本任務在 `WatchStockService.toIndexResponse` 新寫的 `LocalDate.isBefore` 版本。兩者不共用程式碼、不在同一個 Maven 模組，**測試無法同時呼叫兩者做等值斷言**——這與 `spec/tasks/t261_kd_subchart_five_indicators.md` 那個「同一個類別內兩套實作」的前例不可類比，不要照抄它「等值必測」的措辭來自我安慰。BFF 側的判準原文（「`tradingDate` 嚴格早於 `beforeDate` 的最後一筆」）請寫進本測試的 javadoc **當作日後人工比對的基準**，但要清楚那是註解、不會失敗。若日後要真的機械釘住，得把 `previousCloseBefore` 提為 package-private 並在 bff 模組補一支等價測試，兩側 javadoc 互指——那是另一個任務的範圍，本任務不做。
  - 一個漲跌幅數值 case（兩欄一起當錨點）：`price=43200.1094`、`previousClose=39933.30` → `priceChange` 為 **`3266.8094`**、`changePercent` 為 **`8.1807`**。逐步驗算：`diff = 3266.8094` → `setScale(4, HALF_UP)` 不變；`3266.8094 / 39933.30 = 0.081806647…` → `setScale(6, HALF_UP)` 的第 7 位小數為 `6` **故進位**得 `0.081807`（寫成 `0.081806` 是截斷不是 HALF_UP）→ `×100` → `setScale(4, HALF_UP)` = `8.1807`。**若測試紅燈，是期望值抄錯、不是算式該改**——263.4.5 已凍結該算式，且它與個股列共用同一段程式碼。

- [x] 263.5.5 **改既有** `backend/src/test/java/com/steven/assets/service/TechnicalIndicatorTaiexLiveBlendTest.java`：補一支 case 釘住 263.3.6 揭露的因果鏈——**live 的 `highPrice`／`lowPrice` 真的會進 KD 的 RSV，而不是被 fallback 成 `price`**。
  - **該檔現況**：全檔 102 行、**三個** `@Test`。其中只有 `completedKNotToday_liveFreshToday_blendsIntoMa20` 會建 `LivePrice`（另兩支一支 `verify(priceQuery, never()).getLive(...)`、一支 `thenReturn(Optional.empty())`），而它用的 helper `liveOn(LocalDate tradingDate, BigDecimal price)`（該檔 50-55 行）把 high／low 都填 null。故 `live.highPrice() != null` 的 **true 分支目前零覆蓋**。
  - **需新增 helper overload** `liveOn(LocalDate, BigDecimal price, BigDecimal high, BigDecimal low)`；**既有兩參數版保留不動**（改它會動到既有三個 case）。`PriceQueryService.LivePrice` 是 17 個參數的 record，不要在 case 內 inline 建。
  - **新 case 的 fixture 約束（不遵守會得到誤導性的紅燈）**：`taiexKd` 的 `rsv = (close − lowest) / (highest − lowest) × 100`（`TechnicalIndicatorService.java:343-362`）。既有 `descRows` fixture 的 live close 遠高於其餘各格，故 `highest == close`、RSV 恆為 100——此時**只放寬 low 不會改變 K／D**（`k`／`d` 相等），斷言會紅燈但生產碼沒錯。故新 case 的 live `highPrice` **必須嚴格大於** `price`。方向性：設低點下移 δ、高點上移 ε，`RSV_new < RSV_old` 的充要條件是 `δ·(H−C) < (C−L)·ε`，**「區間變寬 → K 下降」不是恆真命題**；本 case 固定用「只放寬 high」（δ=0、ε>0）的資料，該條件必然成立，斷言 K 下降。
  - 用途：日後若有人把 `TaiexIndexPoller` 的 high／low 改回本地聚合（等於退回收盤價序列的 max/min），這裡會紅燈。

### 263.6 規則版本升級 `TW_RULES_V7` → `TW_RULES_V8`

263.3.6 改變了大盤 K／D 的輸入取值，使交易雷達的 regime／score 與修正前不同。本專案對此的既有判準是「使用者可觀察行為有實質變化即升版，即使因子組成／權重／正規化完全未動」——Task 228（V6）與 Task 232（V7）兩個先例的 AC 原文都明白這樣寫。

- [x] 263.6.1 `backend/src/main/java/com/steven/assets/service/TradingRadarRuleEngine.java:19` 的 `public static final String RULE_VERSION = "TW_RULES_V7";` 改為 `"TW_RULES_V8"`。
- [x] 263.6.2 **前端兩處 hardcode fallback 須同步**（Task 232 的 AC 明列這個陷阱，漏改會讓後端已升版、畫面仍顯示舊版）：`frontend/src/views/TradingRadarView.vue:28` 的 `{{ radar.ruleVersion || 'TW_RULES_V7' }}` 與 `:586` 的 `const radar = ref({ ..., ruleVersion: 'TW_RULES_V7' })`，兩處字面值一律改為 `'TW_RULES_V8'`。**這兩行是本任務對 `frontend/` 的全部改動**，不得順手改動該檔其他任何一行。
- [x] 263.6.3 `backend/src/main/java/com/steven/assets/dto/TradingRadarDto.java:9` 的 javadoc「所有分數／建議皆為 `TW_RULES_V7` 即時計算的衍生值」同步改為 `TW_RULES_V8`。
- [x] 263.6.4 **不新增「不可比性揭露」機制**：V8 的因子組成、權重、正規化方式與 V7 完全相同，分數本身跨版本可比；修正前後的差異來自輸入取值變正確，不是規則換了。Task 223 為「因子組成改變」建立的不可比性揭露機制不套用於本次。
- [x] 263.6.5 **升版不會觸發任何機械行為**：`RULE_VERSION` 在本專案只被 `TradingRadarService.java:156` 塞進 DTO、由 `TradingRadarExportService.java:113` 寫進 Excel。改版本號的唯一效果是畫面標籤與匯出欄位的字串。
  > **順帶更正一個既有的錯誤斷言（不必改那些檔，知道即可）：** Task 249 的 AC 與 `spec/design.md` 對應段落宣稱「升版會觸發 Requirement 44 的通知基準（`last_action`／`last_counter_trend_state`）全面重建與首輪只建基準不寄信」，並以此當作不升版的代價理由。**實測不成立**：`trading_radar_notification_state` 表的欄位只有 `id`／`setting_id`／`state_type`／`state_code`（2026-07-31 於運行中 DB `\d` 確認），**沒有 `rule_version` 欄**，全樹亦無任何依 `RULE_VERSION` 重建通知狀態的程式碼。Task 249 的「不升版」結論本身仍然成立（它的主要理由是「同一份輸入前後輸出完全相同」，那條為真），只是附帶的代價論述是虛的。Task 263 升版的代價因此確實為零。

### 263.7 spec 回填

- [x] 263.7.1 實作完成後回填本檔「完成報告」段：實際改了哪些檔、驗證輸出、與本計畫的偏差及原因。
- [x] 263.7.2 勾選本檔所有 checkbox。
- [x] 263.7.3 **不需**改 `spec/tasks.md`：該檔的索引表目前收到 **Task 228** 為止（表頭雖寫「Task 1–220」，實際列到 228；t222–t228 是「自足任務檔且有登錄」的一批），而 t249–t261 等近期自足任務檔**皆未登錄**。本任務比照近期作法辦理。
- [x] 263.7.4 **不需**同步 `SchedulePublicBffController.JOBS`：本任務**未新增、未刪除、未調整任何 `@Scheduled`**（`TaiexIndexPoller` 的 cron 與 zone 逐字不變），排程列表頁的 46 筆計數不受影響。

## 驗證

> **`scripts/spec-check.sh` 的預期 BLOCK（實作前）：** 該腳本的 B5 檢查對 `added_lines 'spec/**'` 抓取符合「大寫開頭＋`Test` 結尾」樣式的識別字，逐一比對全樹是否存在對應 `.java`／`.js`。本檔 263.5.4 宣告要**新建** `WatchStockTaiexIntradayTest`，故實作完成前必然命中一次：
>
> ```
> BLOCK │ spec 提到測試類 WatchStockTaiexIntradayTest，但全樹找不到 WatchStockTaiexIntradayTest.java/.js —— 宣稱的驗證不存在（Task 160 前例）
> ```
>
> B5 的用意是抓「拿不存在的測試當既成證據」（Task 160 前例），無法區分「將要新建」。**不要為了消除它而先建一支空測試檔**——那等於 code 先於審查，違反 SDD 順序，也把閘門變成形式；且 `backend/**` 的寫入在本次審查記錄之前本來就會被 `require-spec-review.sh` 擋下。實作完成後此 BLOCK 應自動消失；**若實作後仍在，代表 263.5.4 沒做**，屆時它就是真陽性。前例：`spec/tasks/t260_radar_recompute_before_export.md` 對 `TradingRadarServiceOwnerScopeTest` 的同一情形。
>
> 本檔提到的其餘測試類（`TaiexIndexPollerTest`、`PriceCacheWriterTest`、`TechnicalIndicatorTaiexLiveBlendTest`）**皆已存在**，不會命中 B5。

```bash
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f external-materials-service/pom.xml test -DextraArgLine=-Dnet.bytebuddy.experimental=true
```

```bash
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test -DextraArgLine=-Dnet.bytebuddy.experimental=true
```

重建並重啟三個服務（本專案沒有 dev server，「改好」＝ image rebuild + container recreate；**JVM 服務的 cached build 會出 stale jar、frontend 的普通 build 會命中 layer cache 沒重跑 vite，故三者一律 `--no-cache`**。frontend 要重建是因為 263.6.2 改了 `TradingRadarView.vue` 的兩行版本字串）。從 worktree 跑 compose 前先把主 repo 的 `.env` 複製進來（`env_file` 相對 compose 檔解析，`--env-file` 救不了）：

```bash
cp /Users/steven/Project/asset-management/.env .env
```

```bash
docker compose -p asset-management build --no-cache external-materials-service business-services frontend
```

```bash
docker compose -p asset-management up -d --no-deps --force-recreate external-materials-service business-services frontend
```

重建 business-services 會換 IP，BFF 握著舊 IP 會回 500 且不自癒（Docker DNS TTL 600s），故必須跟著重啟：

```bash
docker compose -p asset-management restart bff
```

確認 jar 內含新類別（防 stale image）：

```bash
docker exec asset-external-materials-service sh -c 'unzip -p /app/app.jar BOOT-INF/classes/com/steven/assets/externalmaterials/client/MacroDataFetchClient.class | strings | grep -c fetchIndexIntradayDay'
```

確認 backend 與 frontend 的版本字串都升到 V8（三處都要非零；frontend 那支是 vite 產出的 bundle，`grep -rl` 找含該字串的 js）：

```bash
docker exec asset-business-services sh -c 'unzip -p /app/app.jar BOOT-INF/classes/com/steven/assets/service/TradingRadarRuleEngine.class | strings | grep -c TW_RULES_V8'
```

```bash
docker exec asset-frontend sh -c 'grep -rl TW_RULES_V8 /usr/share/nginx/html/assets/ | head -1'
```

盤中（週一～五 09:00–13:30 台北時間）驗證 Redis 即時點位的日期與 OHLC：

```bash
docker exec asset-redis redis-cli --raw GET "price:台股:0000"
```

預期 `tradingDate` 等於當日、`openPrice`/`highPrice`/`lowPrice` 皆非 null、且 `lowPrice` 落在當日 Yahoo 5 分 K 的區間內。與來源對帳（**四個欄位各自過濾 null，與 263.1.2「各自獨立判 null」同口徑**）。**守門窗口有兩種輸出，判準是第一欄印出的日期、不是有沒有 `no bar yet`**：(a) 印出的日期**不等於今日**——Yahoo 連今日的 `timestamp` 都還沒產生（09:00–09:05 的典型情形，正是 263.3.2 的成因），此時會印出**昨日**一整組數字，這不代表修正沒生效；(b) 印 `no bar yet`——有今日格但 close 整日皆 null。兩者都代表 263.3.2 應當不寫入：

```bash
curl -s -A "Mozilla/5.0" "https://query2.finance.yahoo.com/v8/finance/chart/%5ETWII?interval=5m&range=5d" | python3 -c "import json,sys,datetime,zoneinfo; r=json.load(sys.stdin)['chart']['result'][0]; z=zoneinfo.ZoneInfo('Asia/Taipei'); q=r['indicators']['quote'][0]; d={}; [d.setdefault(datetime.datetime.fromtimestamp(t,z).date(),[]).append(i) for i,t in enumerate(r['timestamp'])]; day=max(d); f=lambda k:[q[k][i] for i in d[day] if q[k][i] is not None]; o,h,l,c=f('open'),f('high'),f('low'),f('close'); print(day,'no bar yet') if not c else print(day,'open',o[0] if o else None,'high',max(h) if h else None,'low',min(l) if l else None,'last',c[-1])"
```

確認觀察清單 API 的大盤那一列（在 business 容器內以 header 模擬租戶，免走 Google 登入）：

```bash
docker exec asset-business-services sh -c 'curl -s -H "X-User-Id: 1" -H "X-User-Role: ADMIN" -H "X-User-Status: ACTIVE" http://localhost:8080/api/watch-stocks' | python3 -c "import json,sys; print([r for r in json.load(sys.stdin) if r['stockCode']=='0000'])"
```

預期盤中該列 `closed=false`、`tradingDate` 為當日、`price` 與上一步 Redis 的 `price` 相同、`previousClose` 等於 `twse_index_daily_history` 最新一筆：

```bash
docker exec asset-postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "SELECT trading_date, close_point FROM twse_index_daily_history ORDER BY trading_date DESC LIMIT 3;"'
```

盤後（14:00 之後，完成日 K 已入庫）重跑同一支 API，預期該列 `closed=true`、`tradingDate` 等於當日完成日 K、`previousClose` 等於次新一筆。

## 完成報告

實作日期：2026-07-31。

### 實際改動的檔案（12 支）

| 檔案 | 內容 |
|---|---|
| `external-materials-service/.../client/MacroDataFetchClient.java` | 新增 `record DayQuote` 與 `fetchIndexIntradayDay(String)`（+77 行）。既有 `fetchIndexIntraday` 一行未動 |
| `external-materials-service/.../service/PriceCacheWriter.java` | `write(result, markClosed)` 改為委派；新增三參數 overload，`aggregateHighLow=false` 時跳過 `hlTracker.observe` |
| `external-materials-service/.../service/TaiexIndexPoller.java` | 改用 `fetchIndexIntradayDay`；日期守門；填當日 open/high/low；`previousClose` 改取嚴格早於點位日期者（`loadRecentTaiexCloses(2)`）；`write(result, false, false)` |
| `backend/.../service/WatchStockService.java` | `toIndexResponse` 分盤中／盤後兩層；`previousClose` 兩層共用「嚴格早於顯示日的最後一筆」；`closed` 不再寫死 |
| `backend/.../service/TradingRadarRuleEngine.java` | `RULE_VERSION` V7→V8，javadoc 同步 |
| `backend/.../dto/TradingRadarDto.java` | javadoc V7→V8 |
| `frontend/src/views/TradingRadarView.vue` | 兩處 hardcode fallback 字串 V7→V8（本任務對 frontend 的全部改動） |
| `external-materials-service/src/test/.../TaiexIndexPollerTest.java` | 改 stub 為 `fetchIndexIntradayDay`／`loadRecentTaiexCloses(2)`，6 個 case |
| `external-materials-service/src/test/.../PriceCacheWriterTest.java` | `hlTracker` 提為欄位；新增聚合／不聚合兩個 case，6 個 case |
| `backend/src/test/.../WatchStockTaiexIntradayTest.java` | **新增**，7 個 case |
| `backend/src/test/.../TechnicalIndicatorTaiexLiveBlendTest.java` | 新增 `liveOn` 四參數 overload 與 high/low 進 RSV 的 case，4 個 case |
| `backend/src/test/.../TradingRadarRuleEngineTest.java` | `ruleVersion_isV7` → `ruleVersion_isV8` |

### 驗證輸出

**單元測試**（`-DextraArgLine=-Dnet.bytebuddy.experimental=true`）：
- `external-materials-service`：`Tests run: 153, Failures: 0, Errors: 0, Skipped: 0` — BUILD SUCCESS
- `backend`：`Tests run: 376, Failures: 0, Errors: 0, Skipped: 0` — BUILD SUCCESS

**部署**：三個服務 `--no-cache` 重建 + `--force-recreate`，隨後 `restart bff`。防 stale image 檢查全數通過——ext jar 含 `fetchIndexIntradayDay`（1）、backend jar 含 `TW_RULES_V8`（1）、frontend bundle `TradingRadarView-Wg636PoQ.js` 含 `TW_RULES_V8`。bff 重啟後 60 秒內 `Connection refused`／`500` 計數為 0（重啟前那 3 筆是 business-services 換 IP 的既知空窗）。

**觀察清單 `0000` 實測**（18:38，盤後，完成日 K 已含今日 → 走 `closed=true` 分支）：

```
price 43119.75  previousClose 39933.3  priceChange 3186.45  changePercent 7.9794
openPrice 41610.41  highPrice 43214.36  lowPrice 41610.41
buyPrice None  sellPrice None  volume None
tradingDate 2026-07-31  closed True
```

逐項對照 `twse_index_daily_history`：7/31 列 close/open/high/low = 43119.75 / 41610.41 / 43214.36 / 41610.41（完全相符）、昨收 39933.30 = **7/30** 的 close（嚴格早於顯示日 7/31 的最後一筆，正確）。三個報價欄為 null，符合大盤語意。

**交易雷達實測**：`ruleVersion` 回 `TW_RULES_V8`、`stale=false`／`intraday=false`（完成日 K 已到今日，預期）、`asOfDate=2026-07-31`、regime NEUTRAL、score 53。

**來源解析口徑對帳**：主機端以驗證段的 one-liner 打 Yahoo `^TWII` 得
`2026-07-31 open 41610.41015625 high 43214.359375 low 41610.41015625 last 43119.75`，
與 TWSE 官方完成日 K 的 7/31 列**逐欄相同**。這是 `fetchIndexIntradayDay` 三個聚合規則（首格開盤／各格最高之最大／各格最低之最小）正確性的直接佐證。

### 與原計畫的偏差

1. **`TradingRadarRuleEngineTest.ruleVersion_isV7` 需一併改名與改斷言。** 任務檔 263.6 只列了 `RULE_VERSION`、`TradingRadarDto` javadoc、前端兩處，漏了這支既有測試——升版後它必然紅燈（實際發生：`expected: <TW_RULES_V7> but was: <TW_RULES_V8>`）。已改為 `ruleVersion_isV8` 並補上升版理由的 javadoc。
2. **`TradingRadarRuleEngine.java:18` 的 javadoc 也要同步。** 任務檔 263.6.3 只點名 `TradingRadarDto.java:9`，漏了引擎自身常數上方那行（本專案 V6→V7 的 commit `7e59046b` 是 javadoc 與常數同一個 hunk 一起換的）。由 `arch-auditor` 查出，已補。
3. **`WatchStockTaiexIntradayTest` 的 `findDistinctStockCodeMarket` stub 需用 `List<Object[]>` 具名變數。** `List.of(new Object[]{...})` 會被 Java 推導成 `List<Object>` 導致編譯失敗（`no suitable method found for thenReturn`）。改用 `new ArrayList<>()` 後正常。

### 尚未實機驗證的部分

**盤中路徑（`closed=false`、五欄取 Redis）今日無法實機驗證**：部署完成時已 18:38，台股 13:30 收盤、14:00 的 `TwseIndexPoller` 也已把今日完成日 K 寫入 DB，判定條件必然落在盤後分支；`TaiexIndexPoller` 受 `isTwMarketOpen()` 守門同樣不會執行。盤中行為目前由 13 個單元測試覆蓋（`WatchStockTaiexIntradayTest` 7 ＋ `TaiexIndexPollerTest` 6），實機確認需等下一個交易日 09:00–14:00 之間重跑「驗證」段的 Redis 與觀察清單 API 兩個指令。
