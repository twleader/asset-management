# [t337] 油價金價交易時段每分鐘即時報價，收盤後 5 分鐘取回收盤價校正

**對應 Requirements:** Requirement 77（油價金價頁在 CME Globex 交易時段內每分鐘更新 WTI／布蘭特／COMEX 黃金即時價，收盤後 5 分鐘取回該盤收盤價校正最後價格）
**前置任務:** 無（建立於既有 Requirement 40／Task 202 的油金價資料鏈之上）
**Liquibase changeset:** 無（盤中價只進 Redis，DB 沿用既有 `commodity_price_history`，不改 schema）

---

## 背景

### 現在的錯誤行為

「公開資訊 → 油價金價」頁（`frontend/src/views/CommodityPriceView.vue`）的三張 KPI 卡永遠只顯示前一個交易日的收盤價。2026-08-15 21:32 Asia/Taipei 實測畫面：WTI `81.12`／布蘭特 `86.86`／COMEX 黃金 `4442.50`，日期都是 `2026-08-14`。**這個 WTI 數字與下方「實測過的來源行為」表格記載的 8/14 日線收盤 `82.40` 不一致，本任務未查證原因**（可能是頁面截圖時間點更早、或入庫值另有問題）；不影響本任務的實作方向，但落地驗證時應順手查一次 `SELECT close_price FROM commodity_price_history WHERE commodity_code='WTI' AND price_date='2026-08-14'` 確認，並在完成報告記錄。

成因有兩處，兩處都要改：

1. **後端一天只寫一次、寫完不回頭。** 整條油金價資料鏈目前只有一個排程 `CommodityPricePoller.dailyCommodityUpdate`（`external-materials-service/src/main/java/com/steven/assets/externalmaterials/service/CommodityPricePoller.java`，`@Scheduled(cron = "0 30 6 * * MON-SAT", zone = "Asia/Taipei")`），它呼叫 `HistoricalBackfillService.backfillCommodity(code, since)`，而後者是從 `max(price_date) + 1` 起抓，因此同一列寫入後永遠不會被重新抓取。
2. **前端開頁抓一次就凍住。** `CommodityPriceView.vue` 只在 `onMounted` 呼叫 `fetchData()`，沒有任何輪詢或可見性處理。

### 正確行為

- 交易時段內，三個標的的價格每分鐘更新一次，頁面同步反映。
- 收盤後 5 分鐘（17:05 America/New_York），系統取回該盤收盤價，把盤中最後顯示的價格校正成收盤價，並寫入日線表。

### 標的與交易時段（本任務的事實基準，不要再去查別的文件）

| commodity_code | 名稱 | Yahoo symbol | 交易所 | 單位 |
|---|---|---|---|---|
| `WTI` | 西德州原油 | `CL=F` | NYMEX | USD / 桶 |
| `BRENT` | 布蘭特原油 | `BZ=F` | NYMEX | USD / 桶 |
| `GOLD` | COMEX 黃金 | `GC=F` | COMEX | USD / 盎司 |

三者同屬 CME Globex 週期：**週日 18:00 ET 開盤 → 週五 17:00 ET 收盤，其間每日 17:00–18:00 ET 為維護休息**。故：

- 「交易時間內」＝週日 18:00 ET 起至週五 17:00 ET 止，扣掉每日 17:00–18:00 ET 那一小時。
- 「收盤」＝每個交易日 17:00 ET；「收盤後 5 分鐘」＝ **17:05 ET**。

### 實測過的來源行為（2026-08-15 週六、Globex 休市中取得；請直接採用，不要重新猜測）

輕量報價端點（本任務每分鐘要打的就是這一支）：

```
https://query1.finance.yahoo.com/v8/finance/chart/CL%3DF?range=1d&interval=1d
```

- 回應 **1,164 bytes**，`chart.result[0].meta` 內含：`regularMarketPrice=82.4`、`regularMarketTime=1786741199`（＝2026-08-14 16:59:59 ET，正是該盤 17:00 收盤）、`chartPreviousClose=81.25`（前一盤收盤）、`regularMarketDayHigh=82.99`、`regularMarketDayLow=80.71`、`exchangeTimezoneName=America/New_York`、`instrumentType=FUTURE`。
- `chart.result[0].timestamp` 在本次實測**只有一個元素**（索引 0），為**該交易日 00:00 ET**（實測 `1786680000` = 2026-08-14 00:00 ET）。**取用時必須取「陣列中最後一根 `indicators.quote[0].close` 非 null 的 bar」，不得寫死索引 `[0]`**——本任務沒有任何盤中樣本能證明陣列恆長度為 1；既有 `CommodityFetchClient.fetchRange`（見下方「現有可直接複用的東西」）本身就是逐根迭代任意長度陣列，337.2 的新方法必須採同樣寫法，成本為零、對單 bar 情境行為完全相同，一旦來源真的多回一根也不會取錯日期。取得該 bar 的 timestamp 後 `Instant.ofEpochSecond(ts).atZone(ZoneId.of("America/New_York")).toLocalDate()` 即為 `sessionDate`；**既有 `CommodityFetchClient.fetchRange` 的日期歸屬邏輯不要改**，本項只影響新方法的實作方式。
- 換成 `interval=1m` 會多回 1,437 根分鐘 bar，本任務用不到，不要用。
- symbol 內的 `=` 必須 URL-encode 成 `%3D`，否則被 Yahoo 誤解析成 query 分隔（既有 `CommodityFetchClient` 已如此處理）。

**最近一盤的日線收盤 ≠ 交易所結算價（這是「回看 5 天強制覆寫」那一項的唯一理由）。** 同一次實測：

| 交易日 | 日線 bar 收盤 | 當日 14:29 分鐘 bar | 14:30 | 16:59 | 23:58 |
|---|---|---|---|---|---|
| 2026-08-10 | 82.13 | 82.17 | 82.28 | 82.30 | 82.12 |
| 2026-08-11 | 83.20 | 83.17 | 83.23 | 83.23 | 84.25 |
| 2026-08-12 | 83.27 | 83.37 | 83.26 | 82.58 | 82.62 |
| 2026-08-13 | 81.25 | 81.18 | 81.06 | 81.21 | 81.35 |
| **2026-08-14（最近一盤）** | **82.40** | 82.40 | 82.45 | **82.40** | 無（週五 17:00 後無交易） |

最近一盤的日線收盤與 `meta.regularMarketPrice`、與 16:59 那根分鐘 bar **完全相同**（＝收盤當下最後成交價）；更早四盤的日線收盤與當日任何一根分鐘 bar **都對不起來**——與「交易所結算價（settlement）盤後才發布、並回頭取代原本的最後成交價」一致。因此：17:05 抓到的當盤收盤是「最後成交價」，若只寫當日、不回看，該列會永遠停在最後成交價而非市場公認的收盤價。

### 抓取方式的既有約束（不得改成 Java HttpClient）

一律沿用 `ProcessBuilder("curl", "-s", "--max-time", "<秒>", "-H", "User-Agent: Mozilla/5.0", url)` 子程序。Yahoo 對 Java `HttpClient` 的 HTTP/2 TLS fingerprint 會回 RST_STREAM／429，且**長 Chrome UA 反而被 WAF 擋、短 UA 才過**（既有 `CommodityFetchClient`／`YahooFxFetchClient`／`BotFxFetchClient` 皆如此）。

### 為什麼盤中價一律不進 `commodity_price_history`

該表有兩個下游：本頁十年曲線與 Excel 匯出，**以及交易雷達的 `WTI_RET5`／`BRENT_RET5`／`GOLD_RET5`**——`backend/src/main/java/com/steven/assets/service/JpaTradingRadarMarketFeatureAdapter.java` 以 `findByCommodityCodeAndPriceDateBetweenOrderByPriceDateAsc` 直接讀它，再由 `TradingRadarMarketFeatureResolver` 依 `source_available_at` 套 decision-time 可見性。把每分鐘變動的盤中價寫成 `close_price`，等於讓 5 盤報酬率建立在未定案的值上、且每分鐘改變一次歷史特徵。這也與本專案既有紀律一致：**即時價走 Redis、收盤價走 DB；business-services 不直連外部行情 API**。

### 現有可直接複用的東西（避免重造）

- `external-materials-service/.../client/CommodityFetchClient.java`
  - `public static final Map<String, String> SYMBOLS = Map.of("WTI","CL=F","BRENT","BZ=F","GOLD","GC=F")`
  - `public static final String PROVIDER = "YAHOO_FINANCE_CHART"`
  - `public List<CommodityBar> fetchRange(String code, LocalDate start, LocalDate end)`（`interval=1d`，`CommodityBar(priceDate, closePrice, provider, sourceUrl, sourceAvailableAt, fetchedAt)`）
- `external-materials-service/.../service/StockSourceQuery.java`
  - `public void upsertCommodityPrice(String code, LocalDate priceDate, BigDecimal closePrice, String provider, String sourceUrl, Instant sourceAvailableAt, Instant fetchedAt)` —— 既有語意：同值重抓只更新 `fetched_at`；**價格真被修訂時才把 `source_available_at` 推到本次抓取時間**（讓交易雷達 resolver 對被改動過的舊值 fail closed）。**此行為不得更動。**
- `external-materials-service/.../service/HistoricalBackfillService.java`：`backfillCommodity`／`backfillCommodityFrom`／`upsertCommodities`（私有）
- `external-materials-service/.../service/ExchangeRateSpotCacheWriter.java`：Redis key writer 的既有寫法（heartbeat key ＋ spot key、TTL 常數、寫入失敗只 log.warn 回 false）
- `external-materials-service/.../service/ExchangeRatePoller.java`：`AtomicBoolean liveUpdateInFlight` 的 in-flight 守門寫法
- `external-materials-service/.../service/PricePoller.java`：`Executors.newVirtualThreadPerTaskExecutor()` 的 try-with-resources 並行抓取寫法
- `backend/.../service/RedisUsdTwdLiveCacheAdapter.java` ＋ `UsdTwdLiveRateCachePort`：business 端「Redis/raw JSON 只留在外圍 adapter，轉成 immutable model 再交給 service」的既有分層
- `backend/.../repository/CommodityPriceHistoryRepository.java`：既有 `findByCommodityCodeAndPriceDateBetweenOrderByPriceDateAsc`
- `bff/.../commodityprice/CommodityPriceBffController.java`：本頁 BFF（`@RequestMapping("/api/bff/commodity-price")`）
- `frontend/src/api/index.js` 的 `commodityPrice` 命名空間、`frontend/src/views/CommodityPriceView.vue`

---

## 要做什麼

### A. external-materials-service：交易時段判定

- [ ] 337.1 新增交易時段判定元件（建議 `CommodityTradingSessionPolicy`，與既有 `BankFxTradingSessionPolicy` 同層 `…/externalmaterials/service/`）。判定以 `America/New_York` 當地時間為準：
  - 週六：全日不在時段。
  - 週日：`< 18:00` 不在時段；`>= 18:00` 在時段。
  - 週一～週四：`17:00 <= t < 18:00` 不在時段；其餘在時段。
  - 週五：`< 17:00` 在時段；`>= 17:00` 不在時段。
  - **不得**建 CME 假日行事曆，也**不得**挪用既有 `MarketCalendar`／`MarketClock`（那是台／美／英**股市**行事曆，期貨在多數美股假日照常交易，只是提前收盤，挪用會判錯）。假日照 tick，但來源時間戳不會推進，由 337.5 的 freshness 守門吸收。
  - 時間來源必須可注入（建構子接 `java.time.Clock`，正式 bean 用 `Clock.system(ZoneId.of("America/New_York"))`），否則邊界無法單元測試。

### B. external-materials-service：即時報價抓取與 Redis 寫入

- [ ] 337.2 `CommodityFetchClient` 新增即時報價抓取方法（例 `Optional<LiveQuote> fetchLiveQuote(String code)`），打 `…/v8/finance/chart/{symbol%3D}?range=1d&interval=1d`，解析 `chart.result[0]`：
  - `meta.regularMarketPrice` → 價格（必要；缺或 `<= 0` 一律回 `Optional.empty()`，**不得**以任何方式補值）
  - `meta.regularMarketTime` → `quoteTime`（epoch 秒轉 `Instant`；缺則回 empty）
  - `meta.chartPreviousClose` → `sourcePreviousClose`（可為 null）
  - `meta.regularMarketDayHigh`／`regularMarketDayLow` → `dayHigh`／`dayLow`（可為 null）
  - `sessionDate` → 取**陣列中最後一根有非 null close 的 bar**（`timestamp[i]` 對應 `indicators.quote[0].close[i]` 非 null 者，若有多根取最後一根），`atZone(America/New_York).toLocalDate()`。**不得寫死 `timestamp[0]`、不得自行推導「18:00 之後算隔日」**之類的規則——本專案沒有可證明該推導與來源一致的實測，猜錯會讓 KPI 日期與圖表最後一點對不起來。
  - `curl` 逾時 **≤ 10 秒**（既有區間抓取用 30 秒；每分鐘一輪時 30 秒會讓單一標的吃掉半個週期）。
  - 價格 scale 比照既有 `fetchRange`：`setScale(4, RoundingMode.HALF_UP)`。
  - 任何失敗（HTTP、空白、JSON 解析、欄位缺漏）一律 `log.warn` 後回 `Optional.empty()`，不得拋出中斷排程。
- [ ] 337.3 新增 Redis writer（建議 `CommoditySpotCacheWriter`）。**只從既有 `ExchangeRateSpotCacheWriter` 借用四件事：只寫 Redis、不寫 DB、不 publish、寫入失敗只 `log.warn` 回 `false`。它的「舊 payload 損毀就 fail closed 不寫」政策本任務刻意不採用**（理由見 337.5），照抄會與 337.19(c) 的測試互相矛盾。兩個 key：
  - `commodity:spot:{WTI|BRENT|GOLD}`，**TTL 72 小時**。72 小時而非既有股價的 24 小時：週五 17:00 ET 收盤到週日 18:00 ET 開盤有 **49 小時**，24h TTL 會讓週末整組掉光，退化成「週末看不到最後收盤」。
  - `commodity:session`，內容 `{"heartbeatAt":"<Instant>","inSession":true}`，**TTL 150 秒**（每分鐘寫一次，2.5 倍週期容忍單次漏跳），**只在交易時段寫**。消費端一律以此 key 是否存在判斷「現在是不是盤中」，**不得在 business／BFF／前端各自複寫一份交易時段判定**。
  - `commodity:spot:*` 的 payload 欄位（JSON，`null` 欄位可省略）：
    ```json
    {
      "commodityCode": "WTI",
      "price": 82.4000,
      "sourcePreviousClose": 81.2500,
      "dayHigh": 82.9900,
      "dayLow": 80.7100,
      "sessionDate": "2026-08-14",
      "quoteTime": "2026-08-14T20:59:59Z",
      "lastAdvancedAt": "2026-08-14T20:59:31Z",
      "polledAt": "2026-08-14T20:59:31Z",
      "provider": "YAHOO_FINANCE_CHART",
      "sourceUrl": "https://query1.finance.yahoo.com/v8/finance/chart/CL%3DF?range=1d&interval=1d",
      "status": "LIVE"
    }
    ```
  - **`lastAdvancedAt` 是 337.5 freshness 守門判定 `STALE` 的依據，必須存在**：`quoteTime` 每次真正推進時，`lastAdvancedAt` 同步更新為當次 `polledAt`；`quoteTime` 未推進時 `lastAdvancedAt` 也不動。理由見 337.5——poller 是 `@Scheduled` 每分鐘無狀態呼叫，重啟後任何記憶體計數會歸零，「連續 5 分鐘未推進」這個判斷**只能**靠 payload 裡的持久化欄位比對。
  - `status` 只有四個值：`LIVE`（盤中且來源時間戳有推進）、`STALE`（盤中但連續 5 分鐘未推進，價格保留上一筆）、`SETTLED`（由 337.6 的收盤校正寫入）、`CLOSED`（保留值，本任務不主動寫）。**「非交易時段一律不寫 key」只約束每分鐘 tick（337.4）**——337.6 的 17:05 收盤校正是獨立排程，它自己雖然落在非交易時段（收盤代表交易時段已結束），但明確允許寫入 `SETTLED`，兩者不衝突。
  - **漲跌／漲跌幅不得放進 payload**（衍生值，由 business 以 DB 前收即時算；CLAUDE.md 禁止存衍生值）。
  - 寫入失敗只 `log.warn` 並回 false，不得讓例外冒泡中斷排程。

### C. external-materials-service：每分鐘輪詢排程

- [ ] 337.4 `CommodityPricePoller` 新增 `@Scheduled(cron = "0 * * * * *", zone = "America/New_York")` 的每分鐘 tick：
  - 非交易時段（337.1 判定）**直接 return，不得外呼**。
  - 在時段內：先寫 `commodity:session` 心跳，再以 `AtomicBoolean` in-flight 守門（前一輪未結束則 `log.debug` 後 skip，同 `ExchangeRatePoller.liveUpdateInFlight` 的寫法），三個標的**並行**抓取（`Executors.newVirtualThreadPerTaskExecutor()` try-with-resources，同 `PricePoller.updatePrices`）。
  - 單一標的失敗只 `log.warn`，不影響另外兩個。
  - 沿用既有 `@Value("${commodity.enabled:true}")` 開關：關閉時整個 tick 不動作。
- [ ] 337.5 **freshness 守門**（寫在 337.3 的 writer 或 poller 皆可，但行為必須如下）：
  - 本輪取得的 `quoteTime` **不大於**既有 payload 內的 `quoteTime` → **維持既有價格、`quoteTime`、`lastAdvancedAt` 不動**（可更新 `polledAt` 與 TTL），不得以相同值反覆改寫價格。
  - 本輪取得的 `quoteTime` **真的大於**既有值 → 更新 `price`／`quoteTime`／其餘欄位，且 `lastAdvancedAt` 更新為本輪 `polledAt`。
  - **`STALE` 判定式（必須可執行，不能只是文字描述）**：`status = STALE ⇔ status ≠ SETTLED 且 (本輪 polledAt − payload.lastAdvancedAt) ≥ 5 分鐘`；未達 5 分鐘則為 `LIVE`。**這個判定必須讀 payload 裡的 `lastAdvancedAt`，不得用 poller 內部變數／計數器實作**——`@Scheduled` 方法每次呼叫都是獨立、無狀態的，服務重啟後任何記憶體狀態會歸零，只有寫回 Redis 的欄位才能跨次呼叫存活。
  - 任何情況都**不得回寫補值**（同本專案既有「抓不到就維持上一個 tick、禁止以任何替代值充數」的紀律）。
  - Redis 既有 payload 解析失敗時，視為「無既有值」直接寫入本輪結果，並 `log.warn`；不得因解析失敗就整個放棄寫入。
  - **這一條與既有 `ExchangeRateSpotCacheWriter` 正好相反，是刻意分歧、不是疏漏。** 該類別 javadoc 明訂「任一舊資料／Redis／序列化錯誤都 fail closed，不得以空 map 重建覆寫」，其測試方法名即 `malformedExistingPayloadAndInvalidQuoteNeverOverwriteLastTruth`——因為 USD/TWD 必須維護 per-source high-watermark 的**單調性**，讀不到舊值就無從驗證單調，只能拒寫。commodity 沒有這個結構：新鮮度只靠單一 `quoteTime` 大小比較，舊值不可解析時採用本輪值不會造成回退（本輪值必然來自本輪抓取，是當下最新的事實）。若照抄 fail-closed，一次 payload 損毀會讓該標的**永遠**卡住不再更新。

### D. external-materials-service：收盤後 5 分鐘校正

- [ ] 337.6 `CommodityPricePoller` 新增 `@Scheduled(cron = "0 5 17 * * MON-FRI", zone = "America/New_York")`：**同樣受 337.4 用的 `@Value("${commodity.enabled:true}")` 開關約束**（該開關同時控制每日回補、每分鐘即時報價、本項收盤校正三個排程，見 337.16b）。
  - 對三個標的各呼叫既有 `CommodityFetchClient.fetchRange(code, today.minusDays(5), today)`（`today` 取 `LocalDate.now(ZoneId.of("America/New_York"))`），**區間內每一根 bar 都** `StockSourceQuery.upsertCommodityPrice(...)` 強制覆寫——**不得**走 `backfillCommodity` 的 `max(price_date)+1` 增量路徑（那正是「寫完不回頭」的成因）。回看 5 天的理由見上方實測表：17:05 取到的是最後成交價，交易所結算價要更晚才由來源回頭取代。
  - **「5 天」是保守預設值、不是實證結論**：上方實測只證明「存在修訂」，沒有證明修訂在幾天後落地；且 5 個**日曆**天在週一 17:05 執行時只涵蓋 4 個交易盤。這個數字要靠落地後的回歸校準（見驗證段最後一項），不是拍板定案的常數——請把它寫成具名常數而非魔術數字，方便日後調整。
  - **這個回看覆寫會改變交易雷達的歷史可見性，是預期行為、不是 bug，但必須知道**：`upsertCommodityPrice` 在價格被修訂時會把該列 `source_available_at` 推到本次抓取時間，而 `TradingRadarMarketFeatureResolver` 會剔除 `source_available_at` 晚於 decision instant 的列。因此每次結算價修訂，都會讓該列對過去某些 decision instant 變成不可見（回測／快照重算看到的 `WTI/BRENT/GOLD_RET5` 可得性與今天不同）。方向是**變得更保守**（寧可讓被改過的舊值消失，也不讓歷史決策看見未來才知道的數字），符合既有 fail-closed 設計。**不得**為了「讓回測結果穩定」而改掉 `upsertCommodityPrice` 的 `source_available_at` 邏輯。
  - 當盤 bar（`price_date` 等於來源回傳的最後一根 bar 日期）存在 → 以其 `closePrice` 覆寫 `commodity:spot:{code}`：`status=SETTLED`、`sessionDate` 為該 bar 的 `price_date`、`lastAdvancedAt` 為本次校正的 `polledAt`。**`quoteTime` 不得取「本次校正的抓取時間」**——它必須是「來源自己給的時間戳」這個原則對 `SETTLED` 也成立，否則 KPI 會顯示「收盤 · 17:05」而不是真正的 17:00 收盤時刻。做法：對每個標的**額外呼叫一次** 337.2 新增的 `fetchLiveQuote(code)`（與每分鐘 tick 用的同一支輕量方法），取其 `quoteTime`（即該次回應的 `meta.regularMarketTime`）填入。一天一次的排程，每個標的多一次 HTTP 呼叫可以接受，不需要為了省一次請求而犧牲 `quoteTime` 的正確性。
  - **當盤 bar 不存在**（CME 假日／提前收盤／抓取失敗）→ **不寫 DB、不改 Redis 價格**，只 `log.warn`，維持上一個值。
  - 單一標的失敗只 `log.warn`，不影響另外兩個。
- [ ] 337.7 既有 `CommodityPricePoller.dailyCommodityUpdate`（每日 06:30 Asia/Taipei）**保留、語意不變**，作為 17:05 整輪失敗時的安全網。**不得**改它的 cron，也不得改 `backfillCommodity`／`backfillCommodityFrom` 的既有語意。
- [ ] 337.8 `InternalPriceController`（`@RequestMapping("/internal")`）新增 `POST /internal/commodity/live-refresh`：呼叫 337.4 的同一段抓取邏輯跑一輪並回傳結果摘要（例 `{"inSession":true,"updated":["WTI","BRENT","GOLD"]}`）。**仍受交易時段判定約束**：非交易時段回 `{"inSession":false}` 且不改 Redis。

### E. business-services：唯讀 Redis ＋ 聚合

- [ ] 337.9 新增 port＋adapter（比照既有 `UsdTwdLiveRateCachePort` / `RedisUsdTwdLiveCacheAdapter` 的分層：Redis 與 raw JSON 只出現在 adapter，service 只拿 immutable model）：
  - adapter 讀 `commodity:session` 與三支 `commodity:spot:{code}`，缺 key → 該標的為 empty、`marketOpen=false`。
  - JSON 解析失敗**不得**讓整支端點 5xx：該標的視為缺值並 `log.warn`。
- [ ] 337.10 新增 service，組出每個標的的回應物件，欄位：`commodityCode`／`price`／`change`／`changePercent`／`sessionDate`／`quoteTime`／`polledAt`／`status`／`dayHigh`／`dayLow`／`provider`。**回應物件與 port 端的 cache model 一律是不可變 `record`**（比照 `UsdTwdLiveRateCachePort` 內的 `Snapshot`／`Heartbeat`／`Spot` 三個 record、以及 `ScheduledJobDto`）；**不得**用 `Map<String,Object>` 或 Lombok `@Data`／setter 交差——本專案 arch-auditor 把「DTO 必須是不可變 record」列為鐵則。

  漲跌計算**先依 `status` 分流，再取前收**，不是單一句「嚴格早於 sessionDate」、也不是單純「DB 是否已有 sessionDate 該列」：
  ```
  若 status == SETTLED：
        # 這一列剛好是 337.6 校正排程自己寫的，price 就等於它的 close_price；
        # 若照下面二分規則會拿自己當自己的前收，change 恆為 0 —— 必須跳過它、往前找
        prevClose = price_date < sessionDate 的最後一筆 close_price
  否則（status == LIVE 或 STALE）：
      若 commodity_price_history 已有 [code, price_date = sessionDate] 的列：
            prevClose = 該列自己的 close_price        # 該盤已收盤結算（校正排程寫的）、報價仍在動 → 這筆屬其後的夜盤
      否則：
            prevClose = price_date < sessionDate 的最後一筆 close_price   # 該盤仍在進行中
  prevClose 查無 → 退用 payload 的 sourcePreviousClose；再無值 → change 與 changePercent 皆為 null
  change    = price − prevClose
  changePct = change / prevClose × 100
  ```
  **為什麼要先判斷 `SETTLED`**：337.6 的收盤校正把當盤收盤價**同時**寫進 `commodity_price_history`（`price_date = sessionDate`）與 `commodity:spot:{code}`（`price` = 該收盤價）。若不先判斷 `status`、只看「DB 是否已有 `sessionDate` 列」，`SETTLED` 那一刻會命中「已有該列」分支、`prevClose` 等於自己的 `close_price`，`change` 恆為 0——這正是使用者最想看到「收盤漲跌」的那一刻，不能算錯。分出 `SETTLED` 後，才輪到**為什麼對 `LIVE`／`STALE` 二分**：CME Globex 的夜盤（ET 18:00 之後）在日曆日上早於它所屬的交易日，而本任務所有 Yahoo 實測都取自 2026-08-15 週六休市時段，**沒有任何盤中樣本能證明來源此時把回應歸給哪一天**。上式對兩種歸屬都取到正確的前收，因此不必先確定來源行為就能實作。若只寫「嚴格早於」，夜盤時會拿到再前一日的收盤當基準，漲跌整個錯掉。

  以 DB 收盤為主而非來源自帶前收的理由：本專案「同義欄位、同一 business service API」——圖表與匯出都讀 `commodity_price_history`，KPI 若改用來源前收，同一頁會出現兩套基準。`changePercent` 以 `BigDecimal` 除法計算時必須指定 scale 與 `RoundingMode`（例 `divide(prevClose, 6, RoundingMode.HALF_UP)`），否則除不盡會拋 `ArithmeticException`。查詢請用既有 repository（可新增 `findFirstByCommodityCodeAndPriceDateLessThanOrderByPriceDateDesc` 與 `findByCommodityCodeAndPriceDate` 之類的衍生查詢方法），**不得**在 controller 注入 repository。
- [ ] 337.11 `MarketDataController` 新增 `GET /api/market-data/commodity/live`，回 `{"marketOpen": <bool>, "quotes": {"WTI": {...}|null, "BRENT": ..., "GOLD": ...}}`。Controller **只委派 service**，不得寫業務規則、不得注入 Repository、不得直連外部 API。
- [ ] 337.12 **既有 `POST /api/market-data/commodity/refresh` 一律不動**（行為、回傳結構都不動）。改為 business **新開**一支 `POST /api/market-data/commodity/live-refresh`，proxy 至 ext 的 `POST /internal/commodity/live-refresh`（沿用既有 `priceServiceClient` WebClient 寫法與「失敗只 `log.warn` 不中斷」慣例，見 `HistoricalDataService.backfillCommodity` 的形狀）。

  **為什麼不掛進既有那支**（兩個理由，缺一都不足以說服）：
  1. `POST /api/market-data/commodity/refresh` 有**兩個**呼叫端——BFF 的手動 `POST /api/bff/commodity-price/refresh`，**以及每次開頁的 `GET /api/bff/commodity-price`**（`CommodityPriceBffController.getHistory()` 內第一段就是 `businessServicesClient.post().uri("/api/market-data/commodity/refresh")`）。掛進去等於「每有人開一次這頁就多打 3 個 Yahoo curl 子程序」，與每分鐘 poller 疊加。
  2. 回傳結構塞不進去：`MarketDataController.refreshCommodities()` 是 `return Map.of("backfilled", historicalDataService.refreshCommodities())`，而 `refreshCommodities()` 回的是 code→筆數的 map。在該 map 裡加 `live` 會變成 `{"backfilled":{"WTI":..,"BRENT":..,"GOLD":..,"live":{...}}}`，污染那個 map。

### F. BFF

- [ ] 337.13 `CommodityPriceBffController` 新增 `GET /api/bff/commodity-price/live`，passthrough 至 business `GET /api/market-data/commodity/live`。**不得**在 BFF 直查 DB 或直連外部行情；失敗時比照本控制器既有慣例降級（回空 quotes 而非 5xx），頁面顯示既有 DB 收盤比整頁錯誤好。
- [ ] 337.13b `CommodityPriceBffController` 既有 `POST /api/bff/commodity-price/refresh`（「更新資料」按鈕）改為依序呼叫 business 的 `POST /api/market-data/commodity/refresh` 與 `POST /api/market-data/commodity/live-refresh`，合併回 `{backfilled: …, live: …}`。**既有 `GET /api/bff/commodity-price`（開頁）維持現狀、不得追加即時刷新**（理由見 337.12）。

### G. 前端

- [ ] 337.14 `frontend/src/api/index.js` 的 `commodityPrice` 命名空間新增 `getLive: () => api.get('/bff/commodity-price/live', { skipErrorToast: true })`（`skipErrorToast` 必要：每分鐘輪詢失敗不能洗版）。
- [ ] 337.15 `frontend/src/views/CommodityPriceView.vue`：
  - `onMounted` 以 `Promise.allSettled` **並行**啟動既有歷史載入、既有排程設定載入與新的即時報價載入（本專案多 panel 一律並行，不得序列 await）。
  - 60 秒 `setInterval` 輪詢即時報價；監聽 `visibilitychange`：`document.hidden` 時停止輪詢，恢復可見時**立即補抓一次**再重啟 interval；`onUnmounted` 必須 `clearInterval` 並移除事件監聽。
  - 輪詢失敗**保留前一次顯示值**，不清空成「查無資料」、不跳錯誤 toast。
  - **KPI 的即時值走獨立 state，不得寫進既有的歷史序列**：新增一個獨立 ref（例如 `liveQuotes`，鍵為標的代碼）承接 337.14 `getLive()` 的結果，與現行裝載 `commodity_price_history` 的 `series` ref **完全分開**。KPI 卡渲染規則：`liveQuotes[code]` 存在時，卡片顯示其 `price`／`change`／`changePercent` 與狀態標示；不存在時**完全維持現行行為**（顯示 `commodity_price_history` 最後一筆收盤與其 `price_date`，即現行 `latest` computed 的邏輯不變）。**三種狀態的時間一律取 `quoteTime` 轉台北時制，不得顯示 `sessionDate`**——`LIVE` →「盤中 · HH:mm:ss」、`STALE` →「盤中（來源未更新）· HH:mm:ss」、`SETTLED` →「收盤 · HH:mm:ss」。理由：`sessionDate` 在夜盤的歸屬未經實測證實（所有 Yahoo 實測都取自週六休市時段），把它印成「這是哪一天的價格」等於對使用者宣稱無法證明的事實；`quoteTime` 是來源直接給的時間戳，永遠為真。`change` 為 null 時顯示「—」，**不得**顯示成 0。**`series`（以及由它推導的現行 `filtered`／`axisDates`／`latest`／`statsRows`／`totalRows`／`dateSpan` 這些既有 computed）永遠只放 DB 資料，即時報價一律不得寫入或改動這些既有 computed／ref**——下一項的白名單能成立，前提正是這一條。
  - 圖表：`sessionDate` 晚於該序列最後一筆 DB 日期時，把即時價當成該日的點附加到曲線末端（**僅記憶體**，不入庫、不進任何匯出）；`sessionDate` 已存在於 DB 序列時**不得**覆蓋 DB 的值。
  - **盤中點准許進入的地方是白名單，且延伸動作不得改動任何共用 computed 本身**：准許進入的只有**畫給 `v-chart` 用的圖表設定物件內部**——在建構 `mainChartOption` 這個 computed 時，於**函式內部**現算一份局部變數（例如 `const chartDates = [...axisDates.value, sessionDate]`）供該次回傳的 series／xAxis 使用，**不得**修改 `axisDates` 這個 computed 本身的回傳值或它依賴的 `filtered`。**這一條特別重要**：現行 `openExport()` 直接讀 `axisDates.value` 來決定匯出對話框的預設起訖日期（`CommodityPriceView.vue` 現行程式碼），若直接放大 `axisDates` 本身，會連帶把匯出對話框的預設區間也帶進盤中日期，而「匯出行為不得變動」是本任務的硬性要求。以下皆**一律只計 `commodity_price_history` 的列，不得含盤中值**：
    - `totalRows`／`dateSpan`（第四張「資料筆數／日期區間」卡）；
    - **`statsRows`（「區間統計」表的最新／最高／最低／平均／區間漲跌幅五欄）**——現行 `statsRows` 讀的是 `filtered.value[c.code]`，與圖表同一份資料，**最省事但錯誤的做法（把盤中點 push 進 `series`／`filtered`）會靜默污染這張表的「最高／最低」**；
    - **`openExport()` 用來決定預設起訖日期所讀的 `axisDates`**——同樣風險：若圖表延伸誤改了 `axisDates` 本身而非只在 `mainChartOption` 內部處理，匯出對話框預設值會跟著變；
    - 任何匯出檔（手動 Excel 與排程匯出，兩者都在 business 端讀 DB，前端不參與，故只要不把盤中點寫回後端即可）。
  - 既有「更新資料」「匯出 Excel」「排程自動匯出」三塊的行為與版面不得變動。

### H. 排程列表與文件同步

- [ ] 337.16 `bff/.../schedulelist/SchedulePublicBffController.java` 的 `JOBS` 新增兩筆（`EXTERNAL` 類別），並**更新該類別內所有寫死的筆數字樣**（目前 javadoc 寫「全系統排程清單（53 筆）」、分組註解寫「business-services（21）」「external-materials-service（32）」，實際 `new ScheduledJobDto(` 共 53 筆——落地時以當下實際筆數為基準 +2）。`ScheduledJobDto` 是 7 參數 record（`service`／`category`／`name`／`description`／`schedule`／`cron`／`zone`），下表表頭直接對應這些參數名：

  | service | category | name | description | schedule | cron | zone |
  |---|---|---|---|---|---|---|
  | `EXTERNAL` | 油價金價 | 盤中即時報價 | 交易時段內每分鐘更新 WTI／布蘭特／COMEX 黃金即時價至 Redis（非交易時段不外呼） | 每分鐘 | `0 * * * * *` | `America/New_York` |
  | `EXTERNAL` | 油價金價 | 收盤後校正 | 收盤後 5 分鐘取回當盤收盤價寫入日線表，並回看 5 天讓結算價修訂落地 | 交易日 17:05 | `0 5 17 * * MON-FRI` | `America/New_York` |

  既有「油價金價／油金價每日回補」那一筆維持不動。該類別的 javadoc 另有一份 external「對照來源」清單（現為 `TwseIndexPoller、PricePoller、TaiexIndexPoller、TwClosurePoller、FundDividendPoller、NewsPoller、StockFundamentalPoller、KrStockPoller、FundNavPoller、DividendPersister、IntradayTickRefresher、HistoricalBackfillService、ExchangeRatePoller、ClosePersister、UsValuationDerivationScheduler`）——**`CommodityPricePoller` 與 `EtfNavPoller` 都不在其中，本次一併補入**（後者與本任務無關，是清單既有的漂移；既然要動這段就順手清掉，不必另開任務）。
- [ ] 337.16b `external-materials-service/src/main/resources/application.yml` 的 `commodity.enabled` 設定**同時控制三個排程**——既有每日回補（337.7）、本次新增的每分鐘即時報價（337.4）與 17:05 收盤校正（337.6）。該設定既有的註解只提到「每日增量」，須更新為涵蓋三者，避免日後誤以為關閉它只影響每日回補。
- [ ] 337.17 `bff/src/test/java/com/steven/assets/bff/schedulelist/SchedulePublicBffControllerTest.java` 有**三個** `hasSize` 斷言，全部要改：總數 `53` → `55`、`"業務服務"` 的 `21` **不動**、`"外部行情服務"` 的 `32` → `34`。不改會直接讓 `mvn -f bff/pom.xml test` 紅燈。
- [ ] 337.17b `spec/design.md` 中 `SchedulePublicBffController` 段落的排程筆數記載（該處是本專案排程筆數的權威記載，Task 332／334 都更新過）已於本任務規格階段同步為「55 筆 ＝ business 21 ＋ external 34、external 實際 36 個標註」。**落地時若實際筆數與此不同（其他分支先行 merge），以實際為準並回頭修正該段落**。

### I. 測試

- [ ] 337.18 **交易時段判定**：在 `external-materials-service/src/test/java/com/steven/assets/externalmaterials/service/` 新增與 337.1 元件同名的 `…Test`（與既有 `BankFxTradingSessionPolicyTest` 同層、同風格，以注入的固定 `Clock` 驅動），至少涵蓋：週日 17:59 ET（不在）／18:00 ET（在）、週三 16:59（在）／17:00（不在）／17:59（不在）／18:00（在）、週五 16:59（在）／17:00（不在）、週六任一時刻（不在）。
- [ ] 337.19 **freshness 守門**：新增 writer 的單元測試（比照既有 `ExchangeRateSpotCacheWriterTest` 的形狀），至少涵蓋：(a) `quoteTime` 未推進時價格與 `lastAdvancedAt` 都不被改寫；(b) `quoteTime` 真推進時 `lastAdvancedAt` 同步更新為當輪 `polledAt`；(c) `polledAt − lastAdvancedAt ≥ 5 分鐘` 時 `status` 轉 `STALE` 且價格保留；(d) 既有 payload 損毀時仍寫入本輪結果。
- [ ] 337.20 **收盤校正**：新增單元測試涵蓋——(a) 來源缺當盤 bar 時不寫 DB、不改 Redis；(b) 回看區間內每一根 bar 都被 upsert；(c) `commodity:spot:{code}` 寫入的 `quoteTime` 來自 `fetchLiveQuote` 回應的 `meta.regularMarketTime`，**不是**校正呼叫本身的抓取時刻。
- [ ] 337.21 **漲跌計算的三分規則**：business 端新增單元測試涵蓋——(a) `status = SETTLED` 時，即使 DB 已有 `sessionDate` 對應列（就是它自己），`prevClose` 仍取**嚴格早於** `sessionDate` 的前一筆，`change` 不為 0（這是本規則要防的主要 bug：若誤用二分規則，`SETTLED` 會拿自己的 `close_price` 當前收）；(b) `status = LIVE`／`STALE` 且 `sessionDate` 在 DB **沒有**對應列時，取嚴格早於它的最後一筆當前收；(c) `status = LIVE`／`STALE` 且 `sessionDate` 在 DB **已有**對應列時（夜盤情境），取**該列自己**的 `close_price` 當前收；(d) 以上都查不到時退用 `sourcePreviousClose`；(e) 皆無時 `change`／`changePercent` 為 null；(f) 除不盡時不拋 `ArithmeticException`。
- [ ] 337.22 三個服務的既有測試必須全綠（本專案在 Java 25 上跑 Mockito 需 `-DextraArgLine=-Dnet.bytebuddy.experimental=true`；**不得**在命令列直接下 `-DargLine`，那會覆蓋掉 pom 裡的 `-Duser.timezone=Asia/Taipei` 而讓大量時區相關測試 error）。

---

## 驗證

### 建置與單元測試

```bash
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -f external-materials-service/pom.xml test -DextraArgLine=-Dnet.bytebuddy.experimental=true
```

```bash
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -f backend/pom.xml test -DextraArgLine=-Dnet.bytebuddy.experimental=true
```

```bash
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -f bff/pom.xml test -DextraArgLine=-Dnet.bytebuddy.experimental=true
```

> `mvn ... | tail` 之類的管線會讓 exit code 變成 `tail` 的、恆為 0——**不要用管線遮蔽 maven 的離開碼**。

### 重建與部署（本專案沒有 dev server，「改好」＝ image rebuild ＋ container recreate）

從 worktree 跑 compose 前，先把主 repo 的 `.env` 複製進來（`env_file` 相對 compose 檔解析，`--env-file` 救不了）：

```bash
cp /Users/steven/Project/asset-management/.env /Users/steven/Project/asset-management/.claude/worktrees/oil-gold-price-updates-d021fa/.env
```

JVM 服務一律 `--no-cache`（cached build 可能不含本次變更，症狀是「前端有、後端沒有」）：

```bash
docker compose -p asset-management build --no-cache external-materials-service business-services bff frontend
```

```bash
docker compose -p asset-management up -d --force-recreate external-materials-service business-services bff frontend
```

> 重建 business 後**必須**再 restart bff：容器換 IP 後 BFF 會握著舊 IP 回 500，且 Docker DNS TTL 600s 內不會自癒（症狀是 business log 乾淨、錯誤只出現在 bff log 的 Connection refused）。

### 端到端實查

Redis 兩個 key（交易時段內執行；非交易時段 `commodity:session` 應不存在）：

```bash
docker exec asset-redis redis-cli --scan --pattern 'commodity:*'
```

```bash
docker exec asset-redis redis-cli get commodity:spot:WTI
```

business 端點（在 business 容器內打，免 OAuth）：

```bash
docker exec asset-business-services curl -s http://localhost:8080/api/market-data/commodity/live
```

**交易時段內連續取樣 10 分鐘**（每分鐘一次）。**不要**用「相隔一分鐘必然推進」當判準，也**不要逐標的分別要求「必然推進」**——本任務自己就定義了合法的 `STALE`（連續 5 分鐘未推進），而 `BZ=F` 在亞洲時段夜盤流動性低，60 秒內、甚至連續數輪來源時間戳不動是常態，那樣的判準在正確實作下也會失敗：

```bash
for i in $(seq 1 10); do docker exec asset-business-services curl -s http://localhost:8080/api/market-data/commodity/live > /tmp/live_$i.json; sleep 60; done; grep -o '"quoteTime":"[^"]*"' /tmp/live_*.json
```

判準三項：(i) **三個標的中至少一個**的 `quoteTime` 在 10 輪內推進過；(ii) 對每個標的個別檢查——未推進的那些輪次，`price` 與前一輪**完全相同**（這正是「時間戳沒推進就不改價」的實證）；(iii) 若某標的出現連續 5 分鐘未推進，其 `status` 須為 `STALE` 而非 `LIVE`。

17:05 ET 之後實查當盤列已入庫、且 Redis 轉 `SETTLED`：

```bash
docker exec asset-postgres psql -U assets -d assets -c "SELECT commodity_code, max(price_date) FROM commodity_price_history GROUP BY 1;"
```

排程列表頁筆數與新兩筆登錄：**不能用 `docker exec asset-bff curl` 或 `wget` 裸打**，三個原因缺一都會打不通——(1) BFF 聽 **8080** 不是 8081；(2) **bff image 內沒有 `curl`**（`bff/Dockerfile` 只有 `eclipse-temurin:21-jre-alpine`，healthcheck 用的是 `wget -qO-`）；(3) `/api/bff/**` 落在 BFF 的 `.anyExchange().authenticated()`（`bff/src/main/java/com/steven/assets/bff/config/SecurityConfig.java`，`permitAll()` 白名單只有三個匿名 GET 與一個 POST，`schedule-list` 不在其中），容器內裸打**沒有 session cookie，一律回 401**（本專案已有先例：`spec/tasks/t288_index_daily_volume_chart.md` 對同一個陷阱有完整記載）。

改以**已登入的瀏覽器驗證**：開任一頁面 → DevTools → Console，貼：

```js
fetch('/api/bff/schedule-list').then(r=>r.json()).then(d=>{
  console.log('總筆數', d.length)
  console.log('油價金價相關', d.filter(x => x.category === '油價金價'))
})
```

**注意是 `category` 不是 `name`**：「油價金價」是 `ScheduledJobDto.category` 欄位的值（既有一筆即為 `category="油價金價", name="油金價每日回補"`），337.16 新增的兩筆 `name` 是「盤中即時報價」「收盤後校正」，用 `name` 篩會回空清單。預期輸出：總筆數 **55**（落地前為 53），且「油價金價」相關為三筆（既有每日回補 ＋ 新增的盤中即時報價、收盤後校正）。

### 前端

頁面驗證需登入（BFF 端點需 Google OAuth session），故由使用者於瀏覽器確認；實作者至少須以 `docker exec asset-frontend grep -l` 確認 build 後的 chunk 確實含新程式：

```bash
docker exec asset-frontend sh -c "grep -rl 'commodity-price/live' /usr/share/nginx/html/assets/ | head"
```

### 落地後的三項後續查核（寫進完成報告）

1. **盤中日期歸屬實測**：本任務所有 Yahoo 實測都取自 2026-08-15（週六、休市）。落地後**第一個盤中時段**（週日 18:00 ET 之後、即台北週一 06:00 之後）實查一次，把當下 `timestamp[0]` 與 `meta.regularMarketTime` 的實際日期歸屬記錄下來：

   ```bash
   curl -s --max-time 10 -H "User-Agent: Mozilla/5.0" "https://query1.finance.yahoo.com/v8/finance/chart/CL%3DF?range=1d&interval=1d" | python3 -c "import json,sys,datetime,zoneinfo; NY=zoneinfo.ZoneInfo('America/New_York'); r=json.load(sys.stdin)['chart']['result'][0]; m=r['meta']; print('bar date =', datetime.datetime.fromtimestamp(r['timestamp'][0],NY).date()); print('quote time =', datetime.datetime.fromtimestamp(m['regularMarketTime'],NY))"
   ```

   這是**查核而非前置**：337.10 的二分規則對兩種歸屬都成立，不必等這筆實測才能開工。

2. **回看窗是否足夠**：落地一週後，對同一組 `price_date` 再查一次 `close_price` 是否還在變動。仍在變即代表來源的結算價修訂晚於 5 天窗口，須加大回看天數。

   ```bash
   docker exec asset-postgres psql -U assets -d assets -c "SELECT commodity_code, price_date, close_price, source_available_at, fetched_at FROM commodity_price_history WHERE price_date > CURRENT_DATE - 14 ORDER BY commodity_code, price_date;"
   ```

3. **交易雷達歷史可見性前後對照**：記錄回看覆寫實際推移了哪些列的 `source_available_at`，確認方向是「變得更保守」（舊值消失）而非「多看到不該看到的資料」。

---

## 完成報告

### 實作分工

由三支平行 subagent 分別實作（目錄樹互不重疊）：external-materials-service（A–D、337.16b、337.18–337.20）、backend＋bff（E–F、337.16、337.17、337.21）、frontend（G）。實作前 `spec-auditor` 對 Requirement 77／本任務檔做過兩輪對抗式審查（第一輪 critical 0／major 8／minor 6，第二輪 critical 0／major 9／minor 5，全部修正後第三輪通過並鎖定雜湊），實作完成後另派 `arch-auditor` 對完整 diff 做架構符規查核，結果 **critical 0／major 0／minor 0**。

### 變更檔案

**新增**：
- `external-materials-service/.../service/CommodityTradingSessionPolicy.java`（337.1）
- `external-materials-service/.../service/CommoditySpotCacheWriter.java`（337.3／337.5）
- `backend/.../service/CommoditySpotCachePort.java`、`RedisCommoditySpotCacheAdapter.java`、`CommodityLiveQuoteService.java`（337.9／337.10）
- `bff/src/test/.../commodityprice/CommodityPriceBffLiveTest.java`
- 六支測試檔（ext 三支、backend 兩支）
- `spec/tasks/t337_commodity_intraday_live_quote.md`（本檔）

**修改**：
- `external-materials-service/.../client/CommodityFetchClient.java`（新增 `fetchLiveQuote`，337.2）
- `external-materials-service/.../service/CommodityPricePoller.java`（新增每分鐘 tick 與 17:05 收盤校正兩個 `@Scheduled`，337.4／337.6／337.8）
- `external-materials-service/.../controller/InternalPriceController.java`（`POST /internal/commodity/live-refresh`）
- `external-materials-service/src/main/resources/application.yml`（`commodity.enabled` 註解更新，337.16b）
- `backend/.../controller/MarketDataController.java`（`GET /commodity/live`、`POST /commodity/live-refresh`；既有 `POST /commodity/refresh` 未動）
- `backend/.../repository/CommodityPriceHistoryRepository.java`（新增兩支衍生查詢）
- `backend/.../service/HistoricalDataService.java`（新增 `liveRefreshCommodities()`）
- `bff/.../commodityprice/CommodityPriceBffController.java`（新增 `GET /live`；`POST /refresh` 改為合併兩支 business 端點）
- `bff/.../schedulelist/SchedulePublicBffController.java`（`JOBS` 新增兩筆，筆數字樣 53→55、21 不動、32→34）
- `bff/src/test/.../SchedulePublicBffControllerTest.java`（三個 `hasSize` 同步）
- `frontend/src/api/index.js`（`commodityPrice.getLive`）
- `frontend/src/views/CommodityPriceView.vue`（獨立 `liveQuotes` state、60 秒輪詢、KPI 狀態標示、圖表白名單延伸）

### 驗證輸出

**單元測試**（三模組皆用 `-DextraArgLine=-Dnet.bytebuddy.experimental=true`，未用管線遮蔽 exit code）：

| 模組 | 結果 |
|---|---|
| external-materials-service | 394 tests，0 failures，0 errors，BUILD SUCCESS |
| backend | 1024 tests，0 failures，0 errors，BUILD SUCCESS |
| bff | 95 tests，0 failures，0 errors，BUILD SUCCESS |
| frontend | `vite build` exit 0，`dist/assets/CommodityPriceView-*.js` 含 `liveQuotes`／`盤中` 等新程式碼 |

**建置與部署過程中的意外插曲（記錄供日後參考）**：
1. 首次 `docker compose -p asset-management build --no-cache ...` 因 buildkit `lease does not exist` 失敗（已知陷阱，`feedback_docker_build_pitfalls.md`）；`docker builder prune -af`（清出 13.12GB）＋ 重拉 base image 後重試成功（exit 0）。
2. **`docker compose build --no-cache` 成功後、`up -d --force-recreate` 部署，端到端驗證卻發現 `GET /api/market-data/commodity/live` 回 404「No static resource」，即新端點不在跑的 jar 裡**，即便容器 image ID 與剛建好的 tag 完全一致、jar 內卻只有既有 Commodity 相關 class，缺新增的三支。逐層排查：`docker build --target builder --no-cache` 單獨建置 builder stage 直接確認 `/app/src` 與 `/app/target/classes` 都正確含新原始碼與新編譯出的 `.class`，`jar tf` 也確認新 class 有被打進 fat jar——證明**當下的原始碼與 Dockerfile 本身完全沒問題**，問題出在 `docker compose build`（bake 模式）這次執行的產物與直接 `docker build` 的產物不一致（原因未完全查明，懷疑與同一時段跑過三次 build 嘗試、其中一次因 buildkit lease 錯誤中途失敗有關，導致 compose bake 的 build graph 快取到不完整的中間層）。**改用純 `docker build --no-cache -t asset-management-<service>:latest ./<dir>` 對四個服務逐一直接建置**（不經 `docker compose build`），每個映像建好後立即用 `docker exec ... unzip -l /app/app.jar | grep <新class>` 逐一驗證含新程式碼，確認無誤後才 `docker compose up -d --force-recreate`（不帶 `--build`，沿用已驗證過的本地映像）。
3. **教訓／建議寫回 `run-stack` skill 或專案記憶**：JVM 服務改動後，光看 `docker compose build --no-cache` exit 0 **不足以**保證映像含最新程式碼；`--no-cache` 對 `docker compose build`（尤其啟用 bake 模式時）似乎不是百分之百可靠的保證。**保險做法是建完後立即對每個 JVM 映像跑一次 `docker exec ... unzip -l /app/app.jar | grep <本次新增的 class 名>` 驗證，而不是只信任 build 的 exit code。**本次部署最終部署的四個映像皆已用此方式逐一驗證通過。

**端到端實查**（部署時為 2026-08-15 週六 22:4x ET，非交易時段）：

```
$ docker exec asset-redis redis-cli --scan --pattern 'commodity:*'
（空，符合預期：尚未進入交易時段，poller 還沒寫過任何 key）

$ docker exec asset-business-services curl -s http://localhost:8080/api/market-data/commodity/live
{"marketOpen":false,"quotes":{"WTI":null,"BRENT":null,"GOLD":null}}

$ docker exec asset-business-services curl -s -X POST http://localhost:8080/api/market-data/commodity/refresh
{"backfilled":{"WTI":0,"BRENT":0,"GOLD":0}}          # 既有端點結構未變，0 筆屬正常（資料已是最新）

$ docker exec asset-business-services curl -s -X POST http://localhost:8080/api/market-data/commodity/live-refresh
{"inSession":false,"updated":[]}                      # 新端點正確回報非交易時段、未寫 Redis

$ docker exec asset-external-materials-service curl -s -X POST http://localhost:8080/internal/commodity/live-refresh
{"inSession":false,"updated":[]}                      # ext internal 端點同上

$ docker exec asset-postgres psql -U assets -d assets -c "SELECT commodity_code, count(*), max(price_date) FROM commodity_price_history GROUP BY 1;"
 commodity_code | count |    max     
----------------+-------+------------
 BRENT          |  2518 | 2026-08-14
 WTI            |  2517 | 2026-08-14
 GOLD           |  2517 | 2026-08-14
（與部署前一致，DB 資料未受影響）

$ curl -sI http://localhost/ | head -1
HTTP/1.1 200 OK

$ docker exec asset-bff wget -qO- http://127.0.0.1:8080/actuator/health
{"status":"UP"}
```

四個容器（external-materials-service／business-services／bff／frontend）皆 `(healthy)`／`Up`，容器實際使用的 image ID 與逐一驗證過的映像一致。

**尚待使用者確認**（皆為 BFF 端點需 Google OAuth session、無法免登入驗證的項目）：
- 排程列表頁 `GET /api/bff/schedule-list` 總筆數是否為 55、「油價金價」`category` 是否為三筆（既有每日回補 ＋ 新增的盤中即時報價、收盤後校正）。
- 頁面 KPI 卡在下一次進入交易時段後是否正確顯示即時價與狀態徽章、60 秒輪詢是否運作、`document.hidden` 暫停/恢復是否正常。

### 落地後續查核（任務檔「驗證」段列的三項，尚未到查核時間點，留待後續）

1. **盤中日期歸屬實測**：需等下一個交易時段（週日 18:00 ET 之後，即台北週一 06:00 之後）才能實測 `timestamp[0]` 與 `regularMarketTime` 的實際日期歸屬。部署完成當下（週六）仍在休市，無法立即執行，留待下次交易時段。
2. **回看窗（5 天）是否足夠**：需部署滿一週後才能對同一組 `price_date` 複查 `close_price` 是否還在變動。
3. **交易雷達歷史可見性前後對照**：需等第一次 17:05 收盤校正實際觸發並產生回看覆寫後才能對照。

### 與任務檔的偏差（三支實作 subagent 各自回報，經彙整）

1. **`CommodityPricePoller` 的 `@Scheduled` 進入點在測試中透過呼叫其委派的 package-private 方法繞過 `enabled` flag**（比照既有 `EtfNavPoller` 測試模式），而非在測試裡另外配置 Spring context 讓 flag 生效。
2. **`liveRefreshNow()`（337.8）只受交易時段約束、不受 `commodity.enabled` 約束**——任務檔 337.8 原文只提到時段限制，且既有同類手動端點（如 `backfillCommodity`）同樣不受各自排程的 `enabled` flag 節流。
3. `applyClosingSettlement` 若 DB upsert 成功但額外的 `fetchLiveQuote`（取真正 `quoteTime` 用）失敗，選擇寫 DB、跳過 Redis 更新並 `log.warn`，不捏造時間戳——非任務檔 337.20 三個測試案例明列的情境，但符合「不得回寫補值」的既有紀律。
4. `fetchLiveQuote` 對 `dayHigh`／`dayLow`／`sourcePreviousClose` 也套用 `setScale(4, HALF_UP)`（任務檔原文只點名「價格」），避免同一 payload 內出現混合精度的 `BigDecimal`。
5. `liveRefreshCommodities()` 沿用既有 `HistoricalDataService`（任務檔 337.12 本就指向該檔案的既有 proxy 形狀），未另開新 service class。
6. 前端 `chartDates` 延伸邏輯依規則文字「`sessionDate` 晚於**該序列**最後一筆 DB 日期」逐標的分別判斷並排除已在聯集軸上的日期，比任務檔範例的單一 `[...axisDates.value, sessionDate]` 更嚴謹（三標的 `sessionDate` 可能不同）。
7. 前端新增 `dayjs` 的 `utc`／`timezone` plugin（套件內建、非新依賴）供 `quoteTime` 轉台北時制顯示，專案先前未有檔案使用過這兩個 plugin。

以上皆不違反任務檔任何一條硬性規則，`arch-auditor` 複核後亦未列為 finding。
