# [t258] 收盤 dump 逐檔守門 ＋ 既寫錯的收盤歷史可被權威來源覆寫修復

**對應 Requirements:** Requirement 7（市場資料整合——自動取得市場資料以提供即時的資產估值；本任務新增其中「收盤 dump 只能寫本次目標交易日的收盤前後所取得的值」與「既寫錯的收盤歷史必須能被權威來源覆寫修復」兩條驗收項）
**前置任務:** t257（把 `collectHeldStockCodes` 改為 per-owner，修正盤中抓價範圍）。**t257 只涵蓋 `2885` 一檔**，本任務處理的是其餘 14 檔與寫入機制本身，兩者互補、不重疊。
**Liquibase changeset:** 無（不動資料庫 schema）

## 背景

### 現在的錯誤行為

`stock_price_history` 是收盤價的唯一權威來源。2026 年已累積 **台股 122 列、美股 3 列、英股 3 列**假收盤（判準：與該檔前一個有資料的交易日 OHLCV 全等）。

實測 `2002`（中鋼，高流動性個股）：

```
 trading_date | open_price | high_price | low_price | close_price | volume
--------------+------------+------------+-----------+-------------+--------
 2026-07-16   |            |            |           |     19.1000 |      0
 2026-07-17   |            |            |           |     19.1000 |      0
 2026-07-20   |            |            |           |     19.1000 |      0
 2026-07-21   |            |            |           |     19.1000 |      0
 2026-07-24   |            |            |           |     19.1000 |      0
 2026-07-27   |            |            |           |     19.1000 |      0
 2026-07-28   |            |            |           |     19.1000 |      0
 2026-07-29   |            |            |           |     19.1000 |      0
```

Yahoo（`2002.TW`, `interval=1d`）同期實際收盤逐日為 `07-16` 18.80、`07-17` 18.65、`07-20` 18.55、`07-21` 18.80、`07-22` 18.85、`07-23` 19.10、`07-24` 19.15、`07-27` 19.25、`07-28` 19.00、`07-29` 18.95 —— 除 `07-16`／`07-21` 同為 18.80 外每日不同。**注意上表只有 8 列**：`07-22`／`07-23` 那兩日對其中 10 檔（`006205 00642 00646 1301 1616 2002 2409 2412 2606 9933`）**原本完全沒有列**——不是「那兩日資料正確」，是**資料不存在**，後來才由修復的 upsert 以 INSERT 補上（見 258.2.5a 與完成報告偏差 1）。另 4 檔（`00695B`／`00929`／`7556`／`2882`）那兩日本來就有列，前三者是因為歷史不滿 10 年、每次容器重啟都被 `startupBackfill` 全掃補齊（見 258.4.6）。故區間交易日數 10 ＝ 腐化列 8 ＋ 缺列 2。三個數字不可混用；下文的「台股 122 列」只計腐化列，**不含缺列**（LAG 判準是「與前一列 OHLCV 全等」，數不到不存在的列）。O/H/L 為 null、`volume` 為 0 是這類假收盤的特徵指紋。

受害代號固定且集中：`006205 00642 00646 00695B 00929 1301 1616 2002 2409 2412 2606 2882 7556 9933`，另加美股 `AAPL`／`MSFT`、英股 `IB01`。

### 成因（兩段，缺一不可）

**第一段 —— dump 完全不檢查值的新鮮度。** `ClosePersister.dumpRedisToDb`（`external-materials-service/src/main/java/com/steven/assets/externalmaterials/service/ClosePersister.java`，as-built 第 344–382 行）的寫入條件只有「Redis 有 `price`」：

```java
Set<String> codes = redis.opsForSet().members("price:index:" + market);
...
for (String code : codes) {
    String json = redis.opsForValue().get("price:" + market + ":" + code);
    if (json == null) continue;
    JsonNode r = MAPPER.readTree(json);
    BigDecimal price = bd(r, "price");
    if (price == null) continue;
    source.upsertHistory(code, market, tradingDate,     // ← tradingDate 是「今天」，與 payload 無關
            bd(r, "openPrice"), bd(r, "highPrice"), bd(r, "lowPrice"),
            price,
            r.hasNonNull("volume") ? r.get("volume").asLong() : null);
    n++;
}
```

代號集合來自 Redis SET `price:index:{market}`，而該 SET 由 `PriceCacheWriter` 在**任何**寫入時加入——包含開機一次性的 `PricePoller.warmCacheOnStartup`，它用的是涵蓋 `stock` 主檔的 `collectAllStockCodes`（實測台股 34 檔），遠大於盤中 cron 的 `collectHeldStockCodes`（t257 修正後 19 檔）。差集那 15 檔是「曾持有／曾觀察」但已不在任何 owner 最新快照、也不在 `stock_alert` 的標的；它們每次容器重啟被寫進 Redis 一次，之後再也不被 cron 刷新，卻仍被 13:32 的 dump 當成當日收盤寫進 DB。

**第二段 —— 錯誤值透過 Redis 洗回 DB，自我延續。** `PriceCacheWriter.syncClosedFromDb`（同目錄，as-built 第 195 行起）從 `StockSourceQuery.findMaxTradingDate` ＋ DB 最近收盤組 payload 寫回 Redis（休市刷新與 `warmCacheOnStartup` 在休市時走此路），payload 只有 `price` 沒有 OHLC：

```text
DB 錯誤列 → syncClosedFromDb 寫回 Redis → 13:32 dumpRedisToDb 冠上「今日」寫回 DB
          → 新錯誤列成為下一輪 syncClosedFromDb 讀到的「最近收盤」→ 每個交易日多一列
```

所以這**不是一次性殘留**：不修機制，數量每個交易日都會增加，而 t257 因為只擴大到 19 檔，救不到這 14 檔（實測已確認這 14 檔在 `stock` 主檔內、但都不在 t257 修好後的輪詢集）。

### 為什麼不能重用既有回補路徑

`HistoricalBackfillService.backfillTwStock`（`backfillUsStock`／`backfillUkStock` 同構）有兩道**各自獨立**的阻擋，現行程式碼逐字如下：

```java
if (maxDate == null) start = since;
else if (minDate != null && since.isBefore(minDate)) start = since;
else start = maxDate.plusDays(1);            // 阻擋一
LocalDate end = (until != null) ? until : today;
if (!start.isBefore(end)) return 0;
...
for (HistoricalBar bar : priceFetch.fetchTwHistoricalRange(stockCode, start, end)) {
    if (bar.tradingDate().equals(today)) { ...; continue; }
    if (store.existsHistory(stockCode, "台股", bar.tradingDate())) continue;   // 阻擋二
    store.upsertHistory(...);
}
```

- **阻擋一**：起點一律 `maxDate.plusDays(1)`，故區間中段永遠碰不到。腐化標的的 `maxDate` 就是最近交易日 → `start` 落在明天 → `!start.isBefore(end)` → `return 0`。
- **阻擋二**：`existsHistory` 命中即 `continue`，即使硬塞區間也會跳過每個已有列的日期。

`StockSourceQuery.upsertHistory` **本身是真 upsert**（先 `SELECT id FROM stock_price_history WHERE stock_code=? AND market=? AND trading_date=?`，存在則 `UPDATE ... WHERE id=?`），但上述兩道守門讓 backfill 路徑從不對既有日期呼叫它。**故修復必須是新的路徑，不是既有路徑加參數。**

### 權威來源的現況（已實測，不必重新確認）

- 容器內 `FINMIND_TOKEN` 為**空字串**（`application.yml` 為 `finmind.token: ${FINMIND_TOKEN:}`，主 repo 與 worktree 的 `.env` 皆未設該變數）。需 token 的 `TaiwanStockKBar`（5 分 K，供分時 tick）回 400——這是日誌裡 `token 未設定` 的來源。
- **但日線 `TaiwanStockPrice` 免 token 可用**：實測 `GET https://api.finmindtrade.com/api/v4/data?dataset=TaiwanStockPrice&data_id=2002&start_date=2026-07-20&end_date=2026-07-29` 回 HTTP 200，`2002` 2026-07-20 收盤 18.55 與 Yahoo 一致。`PriceFetchClient.fetchTwHistoricalRange` 走的正是這支，`volume` 取 `Trading_Volume`（股數級距，與 DB 既有列同口徑，回補不會造成量能口徑前後不一）。
- 美股 `fetchUsHistoricalRange`／英股 `fetchUkHistoricalRange` 走 Yahoo `chart?interval=1d`（curl 子程序 ＋ 短 UA）。台股上櫃標的（含債券 ETF `00xxxB`）在 Yahoo 需 `.TWO` 後綴——`00695B.TW` 回 404、`00695B.TWO` 正常；但台股回補走 FinMind 不經 Yahoo，此點僅供除錯時參考。

## 要做什麼

### 258.1 `dumpRedisToDb` 逐檔守門

- [x] 258.1 在 `ClosePersister` 新增 **package-private static** 方法作為可單元測試的接縫：

  ```java
  static boolean shouldDumpPayload(JsonNode payload, LocalDate targetDate, LocalDateTime now)
  ```

  回 `true` 才允許寫入。判定規則（兩條**皆須**成立）：

  - [x] 258.1.1 **`tradingDate` 必須等於 `targetDate`**。payload 的 `tradingDate` 缺漏、為 null、無法解析、或不等於 `targetDate` → 回 `false`。
  - [x] 258.1.2 **`updatedAt` 距 `now` 不得超過 12 分鐘**。`updatedAt` 缺漏／null／無法解析 → 回 `false`。負值（`updatedAt` 晚於 `now`，時鐘微幅倒退）視為 0 分鐘、**通過**（不得因此擋掉正常路徑）。以常數 `MAX_PAYLOAD_STALENESS = Duration.ofMinutes(12)` 表達，**須可在測試中引用**。

- [x] 258.1.3 **`updatedAt` 一律以台北牆鐘解讀，不做時區換算。** `PriceCacheWriter` 三處寫入（現行第 86／168／239 行）都是 `LocalDateTime.now(MarketClock.TW_ZONE)`，即**所有市場的 `updatedAt` 都是台北牆鐘**。`dumpRedisToDb` 呼叫本方法時 `now` 一律傳 `LocalDateTime.now(MarketClock.TW_ZONE)`，**不得**改傳市場當地時間——那會讓美股位移 12 小時、英股 7 小時，守門在該兩市場恆為 false。
- [x] 258.1.4 **不得以 payload 的 `closed` 欄位作為守門條件。** 13:32 dump 要取的正是 13:28～13:30 那輪盤中 cron 寫入的值，而 `PricePoller.scheduledTwIntradayUpdate` 呼叫的是 `updatePrices(tw, "台股", false)` → `PriceCacheWriter` 寫 `payload.put("closed", markClosed)` 為 `false`。以 `closed == true` 守門會把正常路徑整個擋掉、當日一列都寫不進去。
- [x] 258.1.5 在 `dumpRedisToDb` 的 for-loop 內，`price == null` 檢查之後、`upsertHistory` 之前套用本守門。守不過即 `continue`，**不寫任何列、不得回填昨收／開盤價／中價**（Requirement 7 禁止回寫充數）。
- [x] 258.1.6 `dumpRedisToDb` 回傳值語意不變（實際寫入列數）。另以 `log.info` 記錄被跳過的檔數與代號（**沿用本專案「不得靜默截斷」原則**：被跳過就要看得到，否則「dump 完成：N 檔」讀起來像全部成功）。訊息格式自訂，但須同時含寫入數與跳過數。**範圍釐清**：這裡記錄的只有**守門跳過**的檔；既有的 `json == null`（key 已逾 TTL）與 `price == null` 兩條 skip 沿用原本的靜默 `continue`、不計入跳過數。故「寫入數＋跳過數」可能小於 `price:index:{market}` 的檔數，讀日誌時以此為準（若日後要求三者可對帳，須另立一條 spec 項並同步改實作）。
- [x] 258.1.7 `dumpTwCloseFromRedis`／`dumpUsCloseFromRedis`／`dumpUkCloseFromRedis` 三支呼叫端**本身不改**（含既有的 `calendar.isXxxTradingDay` 假日守門與 log 訊息）。守門只加在共用的 `dumpRedisToDb` 內，三個市場一體適用。
- [x] 258.1.7a **`selfHealMissedClose` 也會間接走到 `dumpRedisToDb`**（開機補救「服務在 dump 時點沒在跑」），故它同樣受守門約束。**路徑要寫清楚**：`dumpRedisToDb` 本身是 private、只有 3 個直接呼叫點（三支 `dumpXxCloseFromRedis`），`selfHealMissedClose` 是呼叫那三支 wrapper，不是第四個直接呼叫端——照「第四個呼叫端」去 grep 會只找到 3 個而誤判 spec 過時。**連帶後果須明白寫出**：開機補跑時 Redis payload 的 `updatedAt` 幾乎必然早於 12 分鐘（那是收盤前寫入的值，開機時點通常已過收盤數十分鐘），且若 `warmCacheOnStartup` 已先跑過 `syncClosedFromDb`，payload 的 `tradingDate` 會被改寫成 DB 的 `maxTradingDate`——故**視兩條啟動執行緒的相對次序，擋下它的是規則 1 或規則 2；兩種情況都是整批擋掉，self-heal 的 Redis dump 分支因此實質失效**。刻意接受——把數小時前的值寫成收盤正是本任務要根除的行為；台股當日收盤仍由 16:00 TW FinMind verify 補上（走外部權威來源、不讀 Redis，不受守門影響）。**但美股沒有這層保障**：18:00 ET 的 FinMind verify 實測寫 0 檔（2026-07-29 日誌「成功覆寫 0 檔，缺漏 9 檔」，HTTP 200 但 `data` 為空，疑為 `USStockPrice` 需 token 而容器內 `FINMIND_TOKEN` 為空字串——`design.md` 的「FinMind 現況補記」只保證台股日線免 token）。故當下列三者**同時**成立時即造成**美股當日收盤永久缺列**，不需要「連 verify 時點也沒在跑」這個前提：(1) 服務在 16:02 ET 的 dump 時點沒在跑；(2) 回到線上時距最後一輪盤中 tick 已超過 12 分鐘，或 `warmCacheOnStartup` 的 `syncClosedFromDb` 已先把 `tradingDate` 改寫成 `maxTradingDate`；(3) 該次重啟早於 18:00 ET。**三個條件缺一不可**——16:02 時還活著的話 dump 已寫入、開機後 self-heal 會被 `!hasAnyHistoryFor(todayUs, "美股")` 短路；崩後很快回來（如 16:01 崩、16:05 回，最後一輪 tick 為 16:00 ET）則 `age ≤ 12 分鐘` 且 `tradingDate` 仍是當日，兩條規則都放行、self-heal 的 dump 照樣寫得進去。英股的 17:00 LON Yahoo verify 未查證（現行容器建立時已過該時點）。上述缺列一律以 `/internal/repair/history` 手動修復。

### 258.2 新增歷史修復路徑

- [x] 258.2 在 `HistoricalBackfillService` 新增：

  ```java
  public RepairSummary repairRange(String market, String stockCode, LocalDate from, LocalDate to)
  ```

  - [x] 258.2.1 `stockCode` 為 null／空白時，代號集合取 `StockSourceQuery.collectAllStockCodes(tw, us, uk)` 中對應該市場的那一份（＝ `stock` 主檔 ∪ **全庫最新一筆**快照持股 ∪ `stock_alert`——`collectAllStockCodes` 仍取 `SELECT id FROM asset_snapshot ORDER BY snapshot_date DESC LIMIT 1`，Task 257 刻意未把它改為 per-owner，見 257.2。「涵蓋所有可能有列的標的」靠的是 `stock` 主檔那一支 union，**不得因誤以為快照支已 per-owner 而把主檔支拿掉**，否則修復範圍會靜默縮小）。指定 `stockCode` 時只處理該檔。
  - [x] 258.2.2 市場字串只接受 `台股`／`美股`／`英股`，分別呼叫既有的 `priceFetch.fetchTwHistoricalRange`／`fetchUsHistoricalRange`／`fetchUkHistoricalRange`（**不得新寫抓取邏輯**）。其他值擲 `IllegalArgumentException`。
  - [x] 258.2.3 逐 bar 處理：**跳過** `bar.tradingDate().equals(LocalDate.now(該市場時區))` 的 bar（今日列獨佔給 `ClosePersister`，Task 84；該市場時區由 `MarketClock.zoneOf(market)` 取得，現行第 32–34 行已有該分流）。
  - [x] 258.2.4 其餘 bar **一律** `store.upsertHistory(...)` 覆寫，**不檢查 `existsHistory`**——這正是與 `backfillTwStock` 的差別，也是本任務存在的理由。
  - [x] 258.2.5 **來源未回傳某日 bar 時，該日既有列保留不動**：不刪除、不猜值、不以鄰日內插。理由：來源查無可能是真休市、可能是該檔當日無交易、也可能是來源暫時故障；三者都不足以支撐「刪掉既有資料」或「填一個近似值」。
  - [x] 258.2.5a **實際語意是 upsert，不只「覆寫」**：`upsertHistory` 無列時會 INSERT，故區間內原本**沒有列**的交易日也會被補上。刻意接受（來源既然回了權威 bar，補上比留空正確），但須知它不是「純修復」——對歷史稀疏的標的跑大區間會新增可觀列數，`rowsOverwritten` 這個欄名涵蓋 UPDATE 與 INSERT 兩者。
  - [x] 258.2.6 逐檔之間 `sleep`：台股沿用 `backfillTwStock` 呼叫端的 600ms、美／英股沿用 2000ms（見 `startupBackfill` 三個市場迴圈內既有的 `sleep(600)`／`sleep(2000)`，as-built 第 82／92／102 行），避免打爆外部來源。
  - [x] 258.2.7 單檔失敗只 `log.warn` 並計入 `codesFailed`，**不中斷整批**（graceful，沿用本專案逐來源 graceful 慣例）。
  - [x] 258.2.8 `RepairSummary` 為不可變 `record`，欄位：`market`、`from`、`to`、`codesProcessed`、`rowsOverwritten`、`codesWithNoSource`、`codesFailed`。
  - [x] 258.2.9 **冪等**：同一區間重跑以相同權威值覆寫，結果不變。不得引入「已修過就跳過」之類的狀態記錄。

- [x] 258.3 在 `InternalPriceController`（`external-materials-service/.../controller/InternalPriceController.java`，現行 `@RequestMapping("/internal")`）新增：

  ```java
  @PostMapping("/repair/history")
  public HistoricalBackfillService.RepairSummary repairHistory(
          @RequestParam String market,
          @RequestParam(required = false) String code,
          @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
          @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to)
  ```

  - [x] 258.3.1 `from`／`to` 由 Spring `@DateTimeFormat(iso = ISO.DATE)` 綁定為 `LocalDate`（沿用同檔 `/internal/backfill/stock` 的既有風格，controller 不自行 `LocalDate.parse`），格式錯誤由 Spring 綁定層回 **400**；`from` 晚於 `to` 與 market 白名單的驗證一律放在 `repairRange` 開頭（單一入口，直呼 service 的單元測試亦能覆蓋），擲 `IllegalArgumentException`。**因 ext-materials 全樹未設 `@ControllerAdvice`，該例外對外表現為 HTTP 500 而非 400**——刻意接受的維運端點現況。
  - [x] 258.3.2 端點放在 `/internal` 之下，與既有 `/internal/backfill/*` 同一層級與風格（該前綴不對外暴露，由 business-services 內部呼叫）。**本任務不新增 business-services 或 BFF 端的 proxy、不動前端**——這是維護用的一次性修復入口，不做成使用者可按的按鈕。
  - [x] 258.3.2a **`market` 為中文，手動呼叫時必須 URL-encode**：`%E5%8F%B0%E8%82%A1`（台股）／`%E7%BE%8E%E8%82%A1`（美股）／`%E8%8B%B1%E8%82%A1`（英股）。Tomcat 依 RFC 7230／3986 拒絕 request target 內的原始非 ASCII 位元，直接寫 `market=台股` 會在進 controller 之前回 400「Invalid character found in the request target」，且日誌只有 Tomcat 例外、看不到任何 repair 訊息。

### 258.4 明確不做的事

- [x] 258.4 **不新增、不修改任何 `@Scheduled` 方法**，故 `SchedulePublicBffController.JOBS` 排程清單不需同步（本任務不造成該頁計數漂移）。根因（t257 的抓價範圍）與機制（258.1 的守門）都已修，常駐稽核排程屬另一個決定。
- [x] 258.4.1 **不動 `PriceCacheWriter.syncClosedFromDb`**。它是迴路的一環，但它本身的行為（休市時讓 Redis 與 DB 收盤一致）是 Task 111 已驗證的必要不變式；迴路由 258.1 的守門在 dump 側切斷即可。改它會波及 `refreshAll`／`warmCacheOnStartup`／`TwRadarRefreshService` 三個既有呼叫端。
- [x] 258.4.2 **不動 `backfillTwStock`／`backfillUsStock`／`backfillUkStock` 及其兩道守門**。它們的「增量 ＋ skip-if-exists」語意服務 `startupBackfill`、`/internal/backfill/stock`、`/internal/backfill/all`、`StockMasterService` 新標的回補等多個呼叫端；放寬會讓每次啟動都重灌全部歷史。
- [x] 258.4.3 **不動 schema、不新增 Liquibase changeset。**
- [x] 258.4.4 **不處理 2026 年以前那些 OHLCV 全等的列**（台股 2016／2017／2019／2020／2021 共 51 列、英股 2019 共 20 列、美股 2020 共 2 列）。它們早於本 dump 機制存在，性質是 10 年歷史回補的來源資料特性（低流動性標的的真平盤、或來源本身無 O/H/L），不是本 bug 的產物。要處理需另行查證真偽，屬另一個任務。
- [x] 258.4.5 **不對「已存在的錯誤列」做偵測式判定**。修復採「重抓權威值直接覆寫」而非「先判斷哪列是錯的再修」——後者需要一套啟發式規則，會有把真平盤誤判成腐化而覆寫掉正確資料的風險。覆寫式做法對真平盤是無害的（寫回相同的值），且順帶修好「盤中 tick 被當收盤」那一類（`1301`／`2409`／`2882`）而不需要偵測它們。
- [x] 258.4.6 **已知且接受的副作用：每日 dump 入庫檔數會下降。** 守門上線後，只有當日被盤中 cron 刷新過的代號能寫當日列，故每日 dump 檔數由 Redis index 的規模降至抓價清單的規模——實測 `SCARD price:index:{market}` 為台股 **34**／美股 **13**／英股 **4**，而 per-owner 抓價集合為台股 **19**／美股 **9**／英股 **3**，即差集 **15／4／1** 檔。實測台股差集為 `0000 006205 00642 00646 00695B 00929 1301 1616 2002 2409 2412 2606 2882 7556 9933`、美股 `AAPL AMD AMZN MSFT`、英股 `IB01`。

  **但差集裡的 `0000` 仍會被寫入**，故實際「當日不再有列」是台股 **14** 檔（15 扣掉 `0000`）／美股 4／英股 1。`0000` 確實在 `price:index:台股` 內（`SISMEMBER` 回 1）、`stock_price_history` 也確有它的 dump 產物（2026-07-21～07-29 連續 7 列、`open_price` 為 null、`volume` 為 0；**但 H/L 有值**，由 `HighLowTracker` 供給，故與個股腐化列的「O/H/L 全 null」指紋**不同型**，比對時勿混淆），所以**不能說它「不走這條 dump」**；它之所以不受守門影響，是因為 `TaiexIndexPoller` 的 `@Scheduled(cron = "0 0/2 9-13 * * MON-FRI")` 每 2 分鐘刷新 `price:台股:0000`，13:32 dump 時它的 `tradingDate`／`updatedAt` 必定通過兩條規則。

  這 14／4／1 檔正是「已不在任何 owner 最新快照、也不在 `stock_alert`，但仍在 `stock` 主檔」的曾持有／曾觀察標的。**缺列會不會自動補齊，取決於該檔的歷史長度**——`startupBackfill` 的納入條件有**三個** disjunct（`HistoricalBackfillService.java:78-79`：`maxDate == null || maxDate.isBefore(staleThreshold) || (minDate != null && since.isBefore(minDate))`），不只 stale 門檻：

  - **歷史已滿 10 年者（`minDate ≤ now−10y`，實測台股差集中的 11 檔：`006205 00642 00646 1301 1616 2002 2409 2412 2606 2882 9933`，`minDate` 皆為 2016-07-29）**：只受第二個 disjunct 管，而 stale 門檻是 `LocalDate.now().minusDays(7)`，缺 1～6 個交易日時 `maxDate` 仍在門檻內 → 整檔被跳過。**這些會累積永久缺列**，直到停滯超過 7 天且剛好有一次容器重啟。
  - **歷史不滿 10 年者（`00695B` 2017-06-08／`7556` 2019-11-15／`00929` 2023-06-09，另 `0000` 2026-07-21）**：第三個 disjunct 恆為真 → **每次容器重啟都做 10 年全區間掃描**（`start = since`），逐 bar `existsHistory` skip，故中段缺列會被自動補上。實測本次部署啟動日誌可見 `啟動補齊台股 00695B (maxDate=2026-07-29, minDate=2017-06-08)` 等三筆——`maxDate` 完全不 stale 卻仍被回補。這也解釋了為何完成報告偏差 1 那批修復 INSERT 只有 10 檔：這 3 檔的缺列早已被前一次啟動的全掃補上。

  無論哪一類，`startupBackfill` 都**只在容器重啟時**跑（`@EventListener(ApplicationReadyEvent.class)`）；唯一的每日 cron `dailyLookthroughBackfill` 只涵蓋 ETF 透視 top10 成份股，不含這些代號。需要即時補齊時以 `/internal/repair/history` 手動處理。**刻意不設常駐稽核排程**——這是明知的取捨，不是疏漏。

### 258.5 單元測試

沿用同目錄既有慣例：`mock(...)` 建構受測類、JUnit 5 `@Test`、AssertJ `assertThat`、**測試方法名用繁體中文**（見 `CrawlerExportPathQueryTest`、`TwRadarRefreshServiceTest`）。

- [x] 258.5 新增 `external-materials-service/src/test/java/com/steven/assets/externalmaterials/service/ClosePersisterDumpGuardTest.java`，直接測 `shouldDumpPayload`（package-private，同 package 可見），以固定的 `targetDate`／`now` 驅動，不依賴真實時鐘：
  - [x] 258.5.1 `tradingDate` 早於 `targetDate`（陳舊值）→ `false`。**這是 122 列腐化的主判準，必須有。**
  - [x] 258.5.2 `tradingDate` 等於 `targetDate` 但 `updatedAt` 早於 `now` 3 小時（盤中 tick，對應實測的 `1301` 10:40）→ `false`。
  - [x] 258.5.3 `tradingDate` 等於 `targetDate`、`updatedAt` 距 `now` 1 分鐘、**且 `closed` 為 `false`** → `true`。**這是回歸錨點**：釘住「不得以 `closed` 守門」，否則正常的 13:32 路徑會被整個擋掉。
  - [x] 258.5.4 `updatedAt` 距 `now` 恰為 12 分鐘 → `true`（邊界含入）；12 分 1 秒 → `false`。
  - [x] 258.5.5 `tradingDate` 缺漏／`updatedAt` 缺漏／兩者格式錯誤 → 皆 `false`，且**不擲例外**。
  - [x] 258.5.6 `updatedAt` 晚於 `now`（時鐘倒退）→ `true`。
  - [x] 258.5.6a payload **完全沒有 `closed` 欄位**時仍依 `tradingDate`／`updatedAt` 兩條規則判定（→ `true`），釘住「`closed` 不是守門條件」的另一面。
  - [x] 258.5.6b 直接斷言 `ClosePersister.MAX_PAYLOAD_STALENESS.toMinutes() == 12`，同時釘住門檻值與該常數的 package-private 可見度（改小會擋掉正常路徑、改大會放進盤中 tick）。
- [x] 258.5.7 新增 `external-materials-service/src/test/java/com/steven/assets/externalmaterials/service/HistoricalRepairRangeTest.java`：
  - [x] 258.5.8 既有列**會被覆寫**：`store.existsHistory` 回 `true` 時仍呼叫 `upsertHistory`（與 `backfillTwStock` 的 skip-if-exists 對照）。用 `verify` 斷言 `upsertHistory` 被呼叫，且 `existsHistory` **不被**當成跳過條件。
  - [x] 258.5.9 該市場當日 bar **被跳過**：來源回傳含今日的 bar 清單時，今日那筆不得進 `upsertHistory`。
  - [x] 258.5.10 來源**整檔全空** → 完全不呼叫 `upsertHistory`、亦不得呼叫任何刪除路徑（既有列保留），該檔計入 `codesWithNoSource`。
  - [x] 258.5.10a 來源**只回傳區間內部分日期** → 缺漏那幾日完全不被 `upsertHistory`（既有列保留），`rowsOverwritten` 只計來源真的有回 bar 的日期；此類 per-day 缺漏**不**計入 `codesWithNoSource`（該欄只計整區間完全無 bar 的代號）。
  - [x] 258.5.11 市場字串非三者之一 → 擲 `IllegalArgumentException`。
  - [x] 258.5.12 單檔抓取擲例外時不中斷整批，其餘檔仍被處理，且該檔計入 `codesFailed`。

## 驗證

```bash
# 1) 單元測試（Mockito 在本機 Java 25 下需 byte-buddy experimental；
#    務必用 -DextraArgLine，不可用 -DargLine——後者會覆蓋掉 pom 既有的時區設定）
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -f external-materials-service/pom.xml test \
  -DextraArgLine=-Dnet.bytebuddy.experimental=true

# 2) 只跑本任務新增的兩支
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -f external-materials-service/pom.xml test \
  -Dtest='ClosePersisterDumpGuardTest,HistoricalRepairRangeTest' \
  -DextraArgLine=-Dnet.bytebuddy.experimental=true

# 3) 重建並 recreate（JVM service 必須 --no-cache，cached build 會出 stale jar）
#    從 worktree 跑 compose 前先把主 repo 的 .env 複製進來（env_file 相對 compose 檔解析，--env-file 救不了）
cp /Users/steven/Project/asset-management/.env .
docker compose -p asset-management build --no-cache external-materials-service
docker compose -p asset-management up -d --no-deps --force-recreate external-materials-service
docker ps --filter name=asset-external-materials-service --format '{{.Names}} {{.Status}}'
```

**驗證修復真的生效**（先記錄修復前的腐化列數，修復後再比）：

```bash
# 4a) 修復前：2026 年腐化列數（下列 SQL 之後會重複使用，數字應由 122/3/3 降為 0）
docker exec asset-postgres psql -U assets -d assets -c "
WITH x AS (
  SELECT stock_code, market, trading_date, open_price, high_price, low_price, close_price, volume,
         LAG(open_price) OVER w AS p_open, LAG(high_price) OVER w AS p_high,
         LAG(low_price) OVER w AS p_low, LAG(close_price) OVER w AS p_close,
         LAG(volume) OVER w AS p_vol, LAG(trading_date) OVER w AS p_date
    FROM stock_price_history
  WINDOW w AS (PARTITION BY stock_code, market ORDER BY trading_date))
SELECT market, count(*) FROM x
 WHERE p_date IS NOT NULL AND trading_date >= '2026-01-01' AND close_price = p_close
   AND open_price IS NOT DISTINCT FROM p_open AND high_price IS NOT DISTINCT FROM p_high
   AND low_price IS NOT DISTINCT FROM p_low AND volume IS NOT DISTINCT FROM p_vol
 GROUP BY market ORDER BY market;"

# 4b) 執行修復（三個市場各一次；容器內無 curl，故起一個 curl 容器接上 compose 網路）
#     market 必須 URL-encode：Tomcat 依 RFC 7230／3986 拒絕 request target 內的原始非 ASCII
#     位元，直接寫 market=台股 會得到「Invalid character found in the request target」400，
#     且該錯誤發生在進入 controller 之前，日誌只有 Tomcat 的例外、看不到任何 repair 訊息。
for MC in "台股:%E5%8F%B0%E8%82%A1" "美股:%E7%BE%8E%E8%82%A1" "英股:%E8%8B%B1%E8%82%A1"; do
  M="${MC%%:*}"; E="${MC##*:}"
  printf '=== %s ===\n' "$M"
  docker run --rm --network asset-network curlimages/curl:latest -s -w '\nHTTP=%{http_code}\n' -X POST \
    "http://asset-external-materials-service:8080/internal/repair/history?market=$E&from=2026-06-01&to=2026-07-29"
done

# 4c) 修復後重跑 4a 的 SQL —— 三個市場皆應為 0 列（或該市場不再出現）

# 4d) 抽驗 2002 中鋼：07-16～07-29 的收盤不得再全為 19.1000，且 O/H/L 不再為 null
docker exec asset-postgres psql -U assets -d assets -c \
"SELECT trading_date, open_price, high_price, low_price, close_price, volume
   FROM stock_price_history WHERE stock_code='2002' AND market='台股'
    AND trading_date BETWEEN '2026-07-16' AND '2026-07-29' ORDER BY trading_date;"

# 4e) 與權威來源對帳（Yahoo 為獨立第三方，非修復所用的 FinMind，故可作為交叉驗證）
curl -s -A 'Mozilla/5.0' 'https://query1.finance.yahoo.com/v8/finance/chart/2002.TW?interval=1d&range=1mo' \
  | python3 -c "
import sys,json,datetime
d=json.load(sys.stdin)['chart']['result'][0]; q=d['indicators']['quote'][0]
for i,t in enumerate(d['timestamp']):
    print(datetime.datetime.fromtimestamp(t).date(),'C',q['close'][i])"

# 4f) 冪等性：再跑一次 4b，rowsOverwritten 可以非零（覆寫成相同值），
#     但 4a 的腐化列數必須仍為 0、4d 的內容必須與前一次完全相同
```

**驗證守門真的生效**（需等下一個交易日的 13:32／16:02 ET／16:32 LON dump 跑過）：

```bash
# 5a) dump 的 log 應同時出現寫入數與跳過數；跳過的代號應為那 14 檔
#     （15 檔差集扣掉大盤 0000——它由 TaiexIndexPoller 每 2 分鐘刷新，守門必過，見 258.4.6）
# 注意：寫入數與跳過數在「收盤 dump 寫入 N 檔、守門跳過 M 檔」那一行（dumpRedisToDb 內），
# 三支 wrapper 只印「Redis dump 完成：N 檔」，pattern 少了前者就永遠看不到跳過的代號
docker logs asset-external-materials-service --since 24h 2>&1 \
  | grep -E '收盤 dump 寫入|守門跳過|Redis dump 完成|Redis index 為空'

# 5b) 下一個交易日 dump 之後重跑 4a 的 SQL —— 不得再出現新的腐化列
#     （守門未生效的話，那 14 檔會各多一列）
```

> **驗證時機提醒**：5a／5b 需要真實的收盤排程跑過。若在盤外部署，先確認 1–4f 全綠，再於下一個交易日回頭核 5a／5b。**不要為了讓 4d 有值而手動 UPDATE DB 或 `redis-cli SET`** —— 那正是 Requirement 7 明令禁止的回寫充數，且會讓 4e 的對帳失去意義。

## 完成報告

**狀態：已實作、已部署、已合併 main，歷史修復已執行完畢。** 實作 commit `b975ae75`（與 Task 257 同一 commit），merge commit `3bec71af`。
本段於 2026-07-30 補寫 spec 時回填（原 commit 未回填完成報告，故 40 個 checkbox 當時全未勾選）。

**實際改的檔（5 個）**

| 檔 | 改動 |
|---|---|
| `.../service/ClosePersister.java` | 新增 `static final Duration MAX_PAYLOAD_STALENESS = Duration.ofMinutes(12)` 與 package-private `static boolean shouldDumpPayload(JsonNode, LocalDate targetDate, LocalDateTime now)`；`dumpRedisToDb` 在 `price == null` 之後、`upsertHistory` 之前套用守門，守不過即記入 `skipped` 並 `continue`；迴圈後以 `log.info` 印「寫入 N 檔、守門跳過 M 檔（payload 非本交易日 X 或距今超過 12 分鐘）：[代號]」。`now` 一律 `LocalDateTime.now(MarketClock.TW_ZONE)` |
| `.../service/HistoricalBackfillService.java` | 新增 `record RepairSummary(String market, LocalDate from, LocalDate to, int codesProcessed, int rowsOverwritten, int codesWithNoSource, int codesFailed)` 與 `repairRange(market, stockCode, from, to)`；market 白名單與 `from > to` 驗證於方法開頭擲 `IllegalArgumentException`；節流台股 600ms／美英股 2000ms；單檔例外只 `log.warn` 並計入 `codesFailed` |
| `.../controller/InternalPriceController.java` | 新增 `POST /internal/repair/history`（`market` 必填、`code` 選填、`from`／`to` 以 `@DateTimeFormat(ISO.DATE)` 綁定） |
| `src/test/.../ClosePersisterDumpGuardTest.java` | 新增，11 個 `@Test` |
| `src/test/.../HistoricalRepairRangeTest.java` | 新增，6 個 `@Test` |

**驗證輸出**（2026-07-30 05:12 CST，皆唯讀查證）

- **4a／4c 腐化列已歸零**：沿用本檔驗證段的 SQL、限 `trading_date >= '2026-01-01'` → **`(0 rows)`**，三個市場皆不再出現。對照修復前的台股 **122**／美股 **3**／英股 **3**，降為 0／0／0。
- **258.4.4「不處理 2026 年以前」未被誤觸**：同一 SQL 去掉年份限制並按年分組 → 台股 2016=19、2017=4、2019=2、2020=25、2021=1（合計 **51**）、美股 2020=**2**、英股 2019=**20**，與本檔宣稱「不處理」的 51／2／20 逐年逐市場完全相符，證明修復未越界。
- **4d 抽驗 `2002` 中鋼 2026-07-16～07-29**：收盤已不再全為 `19.1000`，O/H/L 不再為 null、volume 不再為 0。逐日為 `07-16` 18.65/18.80/18.55/**18.80**、`07-17` 18.80/18.95/18.60/**18.65**、`07-20` 18.90/18.95/18.50/**18.55**、`07-21` 18.60/18.90/18.60/**18.80**、`07-22` 18.75/19.00/18.75/**18.85**、`07-23` 19.00/19.45/19.00/**19.10**、`07-24` 18.95/19.35/18.90/**19.15**、`07-27` 19.25/19.35/19.15/**19.25**、`07-28` 19.10/19.20/18.85/**19.00**、`07-29` 19.10/19.15/18.65/**18.95**（O/H/L/**C**），與背景段列出的權威值一致。
- **部署非 stale jar**：image `sha256:b413014b1157`，built 2026-07-30 04:47:01（commit 後 92 秒）、container created 04:47:10、`RestartCount=0`、compose `working_dir=/Users/steven/Project/asset-management-main`（從 main 的 worktree 重建，符合共用 stack 規則）。容器內 `unzip -p /app/app.jar` + `strings` 確認 `ClosePersister.class` 含守門碼、`HistoricalBackfillService.class` 含 `repairRange`、controller 含 `repair/history`。

**尚未驗證的項目（非失敗，是時間窗未到）**

- **驗證段 5a／5b（守門在真實 dump 路徑上生效）尚未被執行過一次。** 容器 2026-07-30 04:47:10 才建立，而三個 dump 排程為 13:32 TW／16:02 ET／16:32 LON，07-29 的**三個都早於**容器建立時間（換算 UTC：TW `05:32Z`、LON `15:32Z`、ET `20:02Z`，容器 `20:47:10Z`；美股那個僅早 45 分鐘）。取樣當時日誌 100 行（04:47:11～04:47:51，第一行即 Spring banner 故未被截斷），`grep '收盤 dump 寫入|守門跳過|Redis dump 完成'` 零命中；DB 亦無任何 2026-07-30 列（三市場 `max(trading_date)` 皆為 07-29）。**行數與時間範圍會持續增長，不是固定值**；另 `FinMind 校正` 自 05:59:59（＝07-29 18:00 ET）起已有命中，見 258.1.7a，故該關鍵字不可用來判斷「dump 是否跑過」。
- **下一個交易日的待核基準**（已量好，可直接對）：`SCARD price:index:*` 為台股 34／美股 13／英股 4，per-owner 抓價集合為台股 19／美股 9／英股 3。守門若生效，13:32 的日誌應為「寫入 **~20** 檔、守門跳過 **~14** 檔」——寫入數是抓價集合 19 檔**再加大盤 `0000`**（它在 index 內、由 `TaiexIndexPoller` 每 2 分鐘刷新故必過守門），跳過數是 15 檔差集**扣掉 `0000`**。4a 的腐化列數必須維持 0（守門未生效的話那 14 檔會各多一列）。

**與原計畫的偏差**

1. **`repairRange` 的執行輸出已不可復原。** DB 證據顯示它確實跑過（2026 腐化列為 0；`stock_price_history` 最高的 20 個 id `157216–157235` 全是台股 07-22／07-23 的列，代號恰為 `006205 00642 00646 1301 1616 2002 2409 2412 2606 9933` 這 10 檔受害標的，且這 10 檔都不在本次啟動 `startupBackfill` 的回補清單內），但執行是在 **commit 前的一次 build／deploy** 上；該 image 已被 04:47:01 的 `--no-cache` 重 build 取代，其容器與日誌均已不存在。**注意目前這個容器（`RestartCount=0`、建立於 04:47:10）的完整日誌內查不到任何「歷史修復」行，這是預期的，不代表修復沒跑**——不要因為 `docker logs | grep 修復` 空手就下相反結論。`RepairSummary` 回應同樣已不可復原，**故本報告刻意不補一段 RepairSummary JSON**，那會是憑空編造。
2. **端點參數綁定與驗證位置**與原計畫不同：原 258.3 寫 `@RequestParam String from/to` ＋ controller 內 `LocalDate.parse`，as-built 用 `@DateTimeFormat(ISO.DATE) LocalDate` 綁定（沿用同檔 `/internal/backfill/stock` 風格），`from > to` 的檢查放在 `repairRange` 開頭。已據實更新 258.3 與 258.3.1，並補 258.3.2a 記錄「`market` 必須 URL-encode」這個實測踩過的坑。
3. **`shouldDumpPayload` 的實際語意比原計畫多一條**：`updatedAt` 晚於 `now`（容器時鐘微幅倒退）視為 0 分鐘並**通過**。原 258.1.2 已寫到這條，as-built 一致；但測試多了兩個 spec 未宣稱的案例（無 `closed` 欄位、常數值斷言），已補記為 258.5.6a／258.5.6b。
4. **`repairRange` 實際是 upsert 而非純覆寫**（缺列的日期會被 INSERT），原文只說「覆寫既有列」。已補 258.2.5a。
5. **守門連帶讓 `selfHealMissedClose` 的 Redis dump 分支實質失效**（開機時 payload 必然超過 12 分鐘）。原 258.1.7 只列三個 dump 呼叫端、未提這個第四呼叫端與後果。已補 258.1.7a，並在 design.md 守門代價段同步記載。
6. **每日 dump 入庫檔數下降 15／4／1 檔**這個副作用原本沒有明文寫出，已補 258.4.6。

