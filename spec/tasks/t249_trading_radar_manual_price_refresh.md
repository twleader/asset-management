# [t249] 今日交易雷達「重新整理」改為先回補台股即時行情再重算

**對應 Requirements:** Requirement 43（今日交易雷達——不呼叫 AI API 的台股規則式買賣決策輔助；本任務為其 Task 249 修訂）
**前置任務:** 無
**Liquibase changeset:** 無（本任務不動資料庫）

## 背景

**現在的行為（錯的）：** `frontend/src/views/TradingRadarView.vue` 的「重新整理」按鈕呼叫 `load(true)`，只是重打一次 `GET /api/bff/trading-radar`。該 GET 在 business 端走 `TradingRadarService.get()`，個股與大盤的最新價一律由 `PriceQueryService.getLive(code, market)` 讀 Redis key `price:{market}:{code}`，而 Redis 的內容完全由 `external-materials-service` 的背景排程決定——個股是 `PricePoller.scheduledTwIntradayUpdate()`（週一～五 09:00–13:30 Asia/Taipei，每 2 分鐘），大盤 `0000` 是 `TaiexIndexPoller.scheduledTaiexIntradayUpdate()`（同時段同頻率）。**結果是：在兩次排程之間連按十次「重新整理」，拿到的是同一個價格。** 按鈕名稱與實際效果不符。

**正確的行為：** 按下「重新整理」時，先同步回補本頁用得到的台股行情，再以回補後的值重算整份雷達。

**本任務推翻了什麼：** Requirement 43 原本明文寫死兩處禁令，本任務推翻之，原文照抄如下以免實作者誤以為仍然有效：

1. 原 AC「零 AI API」中的：「不得因重新整理而觸發公開資訊爬蟲或**外部行情抓取**。」
2. Task 228（V6）修訂前言中的：「仍不得因使用者『重新整理』頁面而觸發抓取（抓取一律由獨立背景排程驅動，頁面只讀 Redis／PostgreSQL 既有值，與個股即時價的既有模式一致）。」**同一句話另存一份於 `spec/tasks/t228_taiex_intraday_market_signal.md` 的「保留的原則不變」段，一併失效**；實作時請在該檔那一句後加註「（Task 249 起，手動按鈕已推翻此句，見 `spec/tasks/t249_trading_radar_manual_price_refresh.md`；SSE 自動更新仍適用）」，不要刪除原句（它是當時的決策記錄）。

**保留不變的原則（不得一併放寬）：** 本功能仍不得注入或呼叫 `MarketAnalysisService`、Anthropic、OpenAI 或任何 LLM client；仍不得因重新整理而觸發公開資訊（新聞）爬蟲；不新增資料表。**推翻的只有「外部行情抓取」這一項，且只限使用者明確按下按鈕的那一條路徑。**

## 要做什麼

### 鏈路總覽（實作前先讀完，尤其是「不得」的部分）

```text
TradingRadarView「重新整理」→ POST /api/bff/trading-radar/refresh
  → TradingRadarBffRoutes 既有 wildcard rewrite（不新增 route、不新增 controller method）
  → POST /api/trading-radar/refresh（business）
      → TradingRadarRefreshService
          1. 雙鍵冷卻閘門：Redis SETNX radar:refresh:cooldown:{ownerId} 與 :global，TTL 皆 30s
             ├─ 任一已存在 → 跳過步驟 2，outcome=COOLDOWN
             └─ 兩把都取得 → 步驟 2
          2. PriceQueryService.refreshTradingRadarPrices()（WebClient，30s timeout，同步等待）
             → POST /internal/refresh/tw-radar（external-materials-service）
                 ├─ Semaphore(1).tryAcquire() 失敗 → 立即回 {busy:true}，不抓
                 ├─ 開盤中 → 【併行】updatePrices(twCodes,"台股",false) ∥ TaiexIndexPoller.updateOnce()
                 │             大盤那條 Future.get(12s) 上限，逾時放棄本輪大盤
                 └─ 休市   → 逐檔守門後 syncClosedFromDb(可同步者,"台股")；大盤不抓
                              守門：isTradingDay(今日) && findMaxTradingDate(code) != 今日 → 跳過該檔
          3. TradingRadarService.get()   ← 既有純讀重算（含 Task 230 per-owner 快照寫入）
      → { radar, priceRefresh:{ outcome, twMarketOpen, elapsedMs } }
```

- [ ] 249.1 **external-materials-service：新增 `TwRadarRefreshService`**（`external-materials-service/src/main/java/com/steven/assets/externalmaterials/service/TwRadarRefreshService.java`，`@Service`）。**必須放在 `...externalmaterials.service` 套件內**——它要呼叫的 `PricePoller.updatePrices(Set<String>, String, boolean)`、`PricePoller.syncClosedFromDb(Set<String>, String)`、`TaiexIndexPoller.updateOnce()` 三者皆為 **package-private**，放到 `controller` 套件會編不過（**不得為此把它們改成 public**）。內容：
  - 注入 `PricePoller`、`TaiexIndexPoller`、`StockSourceQuery`、`MarketClock`。
  - 類別層級三個欄位：
    - `private final java.util.concurrent.Semaphore gate = new Semaphore(1);`
    - `private final java.util.concurrent.ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();`（**bean 生命週期存活，絕不在 `refresh()` 內建立或關閉**，理由見步驟 4）
    - `long taiexWaitSeconds = 12;` — **package-private、非 `final`**，測試要把它調小。**不得寫成 `private static final`**：`static final` 既不能在測試中賦值（Java 12 起以反射改寫 `static final` 已不可靠），`@RequiredArgsConstructor` 也不會把 `static` 欄位放進建構子，那條迴歸測試就會變成無法撰寫而被跳過——而它正是唯一能抓出「大盤逾時上限被抵銷」的測試。
  - `public Summary refresh()`：
    1. `if (!gate.tryAcquire()) return Summary.busy();`（**立即回，不排隊等待**）。
    2. `try { ... } finally { gate.release(); }`。
    3. 用 `Set<String> tw = new LinkedHashSet<>(), us = new LinkedHashSet<>(), uk = new LinkedHashSet<>();` 呼叫 `source.collectHeldStockCodes(tw, us, uk)`，**只使用 `tw`，`us`／`uk` 收完即丟**。該方法收集的是「最新 `asset_snapshot` 的 `stock_holding` ∪ `stock_alert` 全部去重」。**接著必須 `tw.remove("0000");`**——`collectHeldStockCodes` 的 `WHERE NOT (stock_code = '0000' AND market = '台股')` **只套在 `stock_alert` 那半段**，`SELECT stock_code, market FROM stock_holding WHERE snapshot_id = ?` 那半段沒有任何排除；若某份快照存有 `0000/台股` 的持股列，大盤代號會被拿去打 `mis.twse.com.tw`，而 Requirement 43 明訂大盤走 `twse_index_daily_history`／Yahoo `^TWII`、不打該 API。
    4. `boolean open = clock.isTwMarketOpen();`
       - **`open == true`（開盤中）**：個股與大盤**必須併行**，不得序列。用**類別層級的 `pool`**（不是方法內新建的）送出兩個任務：
         ```java
         Future<?> stocks = pool.submit(() -> poller.updatePrices(tw, "台股", false));
         Future<?> index  = pool.submit(taiex::updateOnce);
         // 先等大盤（有上限），再等個股 —— 順序不可顛倒：反過來會讓大盤的 12 秒疊在個股的 20 秒之後
         try { index.get(taiexWaitSeconds, TimeUnit.SECONDS); indexUpdated = 1; }
         catch (TimeoutException e) { index.cancel(true); log.warn("[tw-radar-refresh] 大盤逾時，放棄本輪"); }
         catch (Exception e) { log.warn("[tw-radar-refresh] 大盤更新失敗: {}", e.toString()); }
         stocks.get();   // 不設額外上限；其內部每檔 tse/otc 各 10 秒逾時、並行，上界 ≈ 20 秒
         ```
         > **絕對不得用 `try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor())` 包住這段。** Java 19+ 的 `ExecutorService.close()` 預設實作是 `shutdown()` 後 `while (!terminated) awaitTermination(1L, TimeUnit.DAYS)`——離開 try 區塊會**一直等到大盤那條任務跑完**，把剛剛省下的 12 秒原封不動還回去，上限形同不存在。本專案自己的 `PricePoller.updatePrices` 就把這個語意寫在 `} // executor.close() 等所有 task 完成`（`PricePoller.java:147`）。同理**不得**在 `refresh()` 內呼叫 `pool.close()`／`awaitTermination`。`index.cancel(true)` 也救不回來：`curlGetWithRetry` 阻塞在 `proc.getInputStream().readAllBytes()`（pipe 阻塞讀）時對中斷無反應，只有 `Thread.sleep` 那段會響應——所以唯一正確的做法就是**不等它**。
         > **為什麼大盤要單獨設上限**：`taiex.updateOnce()` → `MacroDataFetchClient.fetchIndexIntraday("TWSE")` → `curlGetWithRetry(url, 2)`，其重試退避為 `Thread.sleep((attempt + 1) * 10000L)`＝10 秒＋20 秒，**光睡眠最壞就 30 秒**；且它用 `new ProcessBuilder("curl", "-s", "-H", "User-Agent: Mozilla/5.0", url)` **沒有 `-m`／`--max-time`**、`proc.waitFor()` 也沒有逾時，理論上無上界。business 端的 `WebClient` 只有 30 秒，序列執行必然被打穿。**不得為此去改 `curlGetWithRetry`**——它同時服務「股市大盤查詢」頁與其他總經抓取，改它的重試或逾時是另一個變更的爆炸半徑。逾時後放棄本輪大盤是安全的：`TaiexIndexPoller.updateOnce()` 本來就有「查無有效點位則整輪不寫、保留 Redis 上一輪真實值」的慣例。（已知並接受：被放棄的 curl 子程序會在背景續跑至自然結束，佔用一條 virtual thread；不影響本次回應，下一輪排程本來就會覆寫。）
       - **`open == false`（休市）**：**守門只在「交易日且已過 13:30」時生效**，其餘情況照常整批同步。
         ```java
         LocalDate today   = LocalDate.now(MarketClock.TW_ZONE);
         LocalTime nowTw   = LocalTime.now(MarketClock.TW_ZONE);
         boolean tradingDay = clock.isTradingDay("台股", today);
         boolean afterClose = nowTw.isAfter(LocalTime.of(13, 30));
         Set<String> syncable = (tradingDay && afterClose)
                 ? tw.stream().filter(c -> source.findMaxTradingDate(c, "台股").filter(today::equals).isPresent())
                       .collect(Collectors.toCollection(LinkedHashSet::new))
                 : tw;
         if (!syncable.isEmpty()) poller.syncClosedFromDb(syncable, "台股");
         ```
         **大盤不抓。** 三個必須寫死的細節：
         - **`afterClose` 這個下界不可省**。少了它，交易日 00:00–09:00 的盤前整段都會落進守門（該時段 `isTwMarketOpen()` 為 false、`isTradingDay` 為 true、今日收盤當然還沒落 DB）→ 全數跳過 → 回 `SKIPPED_PENDING_CLOSE`、前端顯示「已保留最新成交價」，但盤前 Redis 裡根本沒有今日成交價，屬明確假陳述。而且守門的理由（「Redis 現值比 DB 新」）在盤前是**反過來**的：DB 有 16:00 `verifyTwCloseWithFinMind` 校正過的權威收盤，Redis 才是該被同步的一方（`PricePoller.warmCacheOnStartup` 在盤外做的正是這件事）。
         - **`Optional.empty()` 視為「不納入同步」**，故用 `.filter(today::equals).isPresent()` 而**不是** `.orElse(today).equals(today)`。該檔在 `stock_price_history` 完全沒有列（新掛牌／剛加入觀察清單）時回 empty；`syncClosedFromDb` 對它本來也是 no-op（`findRecentClose` 同樣 empty）。**特別注意不要照抄 `InternalPriceController.java:306` 的 `findMaxTradingDate(code, market).orElse(today)` idiom**——那會把「無任何歷史」翻轉成「等於今日 → 納入同步」，與守門意圖相反。
         - `syncable` 為空時**不呼叫** `syncClosedFromDb`。
    5. 回 `new Summary(...)`（見下方 record）。`skippedPendingClose` 為 `true` 的條件是：休市、`tradingDay && afterClose` 為真、**`tw` 非空**、而 `syncable` 為空。`tw` 本身為空（全庫無台股標的）時 `skippedPendingClose` 必須為 `false`，否則會對使用者回出「今日收盤價尚未落檔」的假訊息。
         > **為什麼要守門（不加會寫壞收盤價）**：`MarketClock.isTwMarketOpen()` 的上界是 `13:30`（`return !t.isBefore(LocalTime.of(9,0)) && !t.isAfter(LocalTime.of(13,30));`），而今日收盤價要到 `ClosePersister.dumpTwCloseFromRedis()`（`@Scheduled(cron = "0 32 13 * * MON-FRI", zone = "Asia/Taipei")`）才由 Redis 落進 `stock_price_history`。13:30–13:32 這約兩分鐘內：`syncClosedFromDb` 走的 `StockSourceQuery.findRecentClose`（`SELECT ... ORDER BY trading_date DESC LIMIT 1`）取回的是**昨日**收盤 → `PriceCacheWriter.syncClosedFromDb` **無條件** `redis.opsForValue().set(...)` 覆寫，銷毀 13:30 那輪的真實當日收盤 → 13:32 的 `ClosePersister.dumpRedisToDb` **完全不檢查 payload 的 `tradingDate`**（直接 `source.upsertHistory(code, market, tradingDate /* ＝呼叫端傳的 today */, ..., price, ...)`），把該昨收當成**今日**收盤寫進 `stock_price_history`，污染本專案宣告的收盤價唯一權威來源，雷達自己的 MA／KD、Dashboard、歷年資產、快照表單一起吃到錯的今日 K，直到 16:00 `verifyTwCloseWithFinMind` 才自癒。既有 `refreshAll` 有同樣缺口，但它沒有被任何前端按鈕呼叫；本任務是第一次把這條路徑接到顯眼按鈕，而 13:30–13:32 恰是使用者最想按的時刻。
         > **不得改動 `PricePoller.syncClosedFromDb` 或 `PriceCacheWriter.syncClosedFromDb` 本身**——它們另有 `PricePoller.refreshAll()` 與 `warmCacheOnStartup()` 兩個既有呼叫端，守門只加在本服務內。
  - `public record Summary(boolean performed, boolean busy, boolean twMarketOpen, boolean skippedPendingClose, int twStocks, int indexUpdated) {}`，並提供 `static Summary busy()` 便利建構子。
  - **log 前綴一律用 `[tw-radar-refresh]`**（進場與出場各一行 INFO，含 `twStocks`、`indexUpdated`、耗時毫秒）。前綴是驗證段賴以與既有排程 log（`PricePoller` 的「更新台股即時價格 (N 檔)」）區分的唯一依據，不得省略或改寫。
- [ ] 249.2 **external-materials-service：新增內部端點**。在既有 `external-materials-service/src/main/java/com/steven/assets/externalmaterials/controller/InternalPriceController.java`（`@RequestMapping("/internal")`）注入 `TwRadarRefreshService` 並新增：
  ```java
  /** 今日交易雷達手動重新整理專用：只抓台股個股 + 大盤 0000（Task 249）。 */
  @PostMapping("/refresh/tw-radar")
  public TwRadarRefreshService.Summary refreshTwRadar() {
      return twRadarRefresh.refresh();
  }
  ```
  **不得改動既有的 `POST /internal/refresh`**（它是 `PricePoller.refreshAll()`，會連美股／英股一起抓，是 Dashboard 用的既有路徑）。**也不得從 business 端改呼叫既有的 `/internal/refresh` 來省事**——本頁只評台股，多抓美股／英股只是把使用者等待時間拉長到含逐檔 Yahoo 查詢的長度。
- [ ] 249.3 **business：`PriceQueryService` 新增同步回補方法**（`backend/src/main/java/com/steven/assets/service/PriceQueryService.java`，該類別已持有 `private final WebClient priceServiceClient`，base-url 來自 `${external-materials.base-url:http://external-materials-service:8080}`）：
  ```java
  /** 同步觸發交易雷達專用的台股行情回補並等待完成（Task 249）。逾時／失敗由呼叫端降級處理。 */
  public JsonNode refreshTradingRadarPrices() {
      return priceServiceClient.post()
              .uri("/internal/refresh/tw-radar")
              .retrieve()
              .bodyToMono(JsonNode.class)
              .block(java.time.Duration.ofSeconds(EXTERNAL_REFRESH_TIMEOUT_SECONDS));
  }
  ```
  `EXTERNAL_REFRESH_TIMEOUT_SECONDS = 30` 為具名 `private static final long` 常數。**不得改動既有的 `triggerRefresh()`**（fire-and-forget，Dashboard 在用）。
- [ ] 249.4 **business：新增 `TradingRadarRefreshService`**（`backend/src/main/java/com/steven/assets/service/TradingRadarRefreshService.java`，`@Service`）。注入 `TradingRadarService`、`PriceQueryService`、`MarketDataService`、`CurrentUserContext`（`com.steven.assets.security.CurrentUserContext`，`@RequestScope`，提供 `hasUser()` 與 Lombok `getEffectiveUserId()`）、`StringRedisTemplate`。`public TradingRadarDto.RefreshResponse refreshAndGet()`：
  1. `long t0 = System.nanoTime();`，並**立刻**求值 `boolean twOpen = marketDataService.isMarketOpenNow("台股");`。
     **`twOpen` 必須在呼叫 external 之前求值一次、全程沿用（含 `COOLDOWN` 分支），不得在 external 回應後重算。** 否則使用者在 13:29:55 按下時，external 端判定為開盤中而真的去抓了即時報價，business 卻在最長 30 秒後才求值得到 `false`，回出 `outcome=CLOSED_SYNCED`、前端顯示「台股目前休市，已同步至最新收盤價」——抓了卻說沒抓，正是本任務要消除的那類自我矛盾。
  2. **雙鍵冷卻閘門（兩把都要取得才抓）**：
     ```java
     String ownerKey  = "radar:refresh:cooldown:" + (currentUserContext.hasUser() ? currentUserContext.getEffectiveUserId() : "anonymous");
     String globalKey = "radar:refresh:cooldown:global";
     Duration ttl = Duration.ofSeconds(COOLDOWN_SECONDS);
     boolean acquired = false;
     if (Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(ownerKey, "1", ttl))) {
         acquired = Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(globalKey, "1", ttl));
         if (!acquired) redis.delete(ownerKey);   // 沒真的抓，就不燒掉自己的冷卻
     }
     ```
     `COOLDOWN_SECONDS = 30` 為具名常數。Redis 本身失敗時（拋例外）**視為取得閘門**（fail-open，不得因為 Redis 抖動就永遠不抓）。
     **不得寫成 `setIfAbsent(owner) && setIfAbsent(global)` 的短路式**：`&&` 短路後 owner 鍵已被寫入卻沒有實際抓取，該使用者的冷卻會被無故燒掉，最長要等約 60 秒（自己那把 30 秒 ＋ 全域那把的剩餘）才解除，與對使用者宣告的「30 秒冷卻」不符。`setIfAbsent` 回 `true` 即證明該鍵是本次呼叫建立的，刪除無競態。
     > **全域鍵不可省。** 抓取範圍是全庫（`collectHeldStockCodes` 無 owner filter），只有 per-owner 鍵時「A 按完 5 秒後 B 按」不會被擋，N 個使用者輪流按即可把外部請求頻率從背景排程的每 2 分鐘一輪推高數十倍，而 external 端的 `Semaphore` **只擋併發、不擋速率**。本專案已有被 Yahoo WAF 回 429 的實績。全域鍵只透露「近期有人刷新過」，而行情快取本就是跨租戶共用的市場資料，不構成租戶洩漏。`anonymous` fallback 讓所有無身分呼叫共用一把鎖，是刻意的保守設計。
  3. `acquired == false` → `outcome = "COOLDOWN"`，**完全不呼叫** `priceQueryService.refreshTradingRadarPrices()`。
  4. `acquired == true` → 呼叫 `priceQueryService.refreshTradingRadarPrices()`，包在 try/catch：
     - 正常回傳且 `busy == true` → `outcome = "BUSY"`。
     - 正常回傳且 `skippedPendingClose == true` → `outcome = "SKIPPED_PENDING_CLOSE"`。
     - 其餘正常回傳 → `outcome = twOpen ? "FETCHED" : "CLOSED_SYNCED"`，用的是**步驟 1 已求值的 `twOpen`**，**不得讀 external 回傳的 `twMarketOpen`**（見步驟 5）。
     - `catch (IllegalStateException e)`（`Mono.block(Duration)` 逾時時 Reactor 的 `BlockingSingleSubscriber.blockingGet` 拋 `IllegalStateException("Timeout on blocking read for ...")`）→ `outcome = "TIMEOUT"`。此 catch 必須排在 `catch (Exception)` **之前**。
     - `catch (Exception e)` → `outcome = "FAILED"`。
     - **兩個 catch 都只記 WARN log，一律不得往外拋、不得回 5xx。**
  5. 步驟 1 求得的 `twOpen` **是本回應中開／休市判斷的唯一來源**，`outcome` 與 `priceRefresh.twMarketOpen` 都用它，external 回傳的同名欄位只寫 log。理由：兩者由不同服務、不同 `MarketCalendar` 實例、不同時刻求值，跨 13:30 邊界時會產生 `{outcome:"FETCHED", twMarketOpen:false}` 這種自相矛盾的 payload；而 `COOLDOWN` 分支根本沒呼叫 external，本來就只有 business 的值可用。
  6. 一律接著執行 `TradingRadarDto.Response radar = tradingRadarService.get();`（既有純讀重算，內含 Task 230 的 per-owner Redis 快照寫入，行為自動一致，不要另外複製一份）。
  7. 回 `new TradingRadarDto.RefreshResponse(radar, new TradingRadarDto.PriceRefresh(outcome, twOpen, elapsedMs))`。
- [ ] 249.5 **business：DTO**。在既有 `backend/src/main/java/com/steven/assets/dto/TradingRadarDto.java`（`public final class TradingRadarDto`，內含巢狀 `public record Response(...)`、`MarketSummary`、`StockDecision`）新增兩個巢狀 record：
  ```java
  /** 手動重新整理的行情回補結果（Task 249）。
   *  outcome ∈ FETCHED / CLOSED_SYNCED / SKIPPED_PENDING_CLOSE / COOLDOWN / BUSY / TIMEOUT / FAILED。 */
  public record PriceRefresh(String outcome, boolean twMarketOpen, long elapsedMs) {}

  public record RefreshResponse(Response radar, PriceRefresh priceRefresh) {}
  ```
  **`Response` record 本身一個欄位都不准加。** 它會被 `TradingRadarSnapshotStore` 序列化進 Redis 快照供 Requirement 48 的區間 Excel 匯出讀回，加欄位會動到既有快照的相容性。
  **`PriceRefresh` 不得包含抓取檔數**：回補清單來自全庫（`collectHeldStockCodes` 無 owner filter；Redis 行情快取本來就是跨租戶共用的市場資料，既有背景排程亦是全庫抓），回檔數等於把「全庫台股標的數」洩漏給任一使用者。檔數只寫 business／external 的 log。
- [ ] 249.6 **business：controller**。在既有 `backend/src/main/java/com/steven/assets/controller/TradingRadarController.java`（`@RestController @RequestMapping("/api/trading-radar")`）注入 `TradingRadarRefreshService` 並新增：
  ```java
  /** 手動「重新整理」：先同步回補台股行情再重算（Task 249）。逾時／失敗降級，不回 5xx。 */
  @PostMapping("/refresh")
  public TradingRadarDto.RefreshResponse refresh() {
      return refreshService.refreshAndGet();
  }
  ```
  **既有 `@GetMapping public TradingRadarDto.Response get()` 一行都不准改。**
- [ ] 249.7 **BFF：什麼都不用做，也不准做**。`bff/src/main/java/com/steven/assets/bff/tradingradar/TradingRadarBffRoutes.java` 既有 route 為 `.path("/api/bff/trading-radar", "/api/bff/trading-radar/**")` ＋ `rewritePath("/api/bff/trading-radar(?<seg>/?.*)", "/api/trading-radar${seg}")`，**無 method predicate**，故 `POST /api/bff/trading-radar/refresh` 會自動 rewrite 成 business 的 `POST /api/trading-radar/refresh`。**不得新增 gateway route**（同 order 時依宣告順序先匹配者勝出，新 route 排在 wildcard 之後不會生效）；**也不得在 `TradingRadarBffController` 新增 method**——該 controller 只為 `/export/browse`、`/export/browse-gdrive` 兩支「business 端不存在對應路徑」的例外而存在，本路徑在 business 端存在對應位置，wildcard rewrite 即正確。
- [ ] 249.8 **前端 API wrapper**。`frontend/src/api/index.js` 既有：
  ```js
  tradingRadar: {
    get: () => api.get('/bff/trading-radar'),
    ...
  ```
  新增一支。axios 實例的全域 `timeout` 為 `30000`（`frontend/src/api/index.js:6`），**必須 per-call 覆寫**，否則會比 business 端 30 秒的 WebClient 更早斷線。**寫法請照 `api/index.js:133` `assetHistory.runExportNow` 那支**（`runExportNow: () => api.post('/bff/asset-history/export-schedule/run-now', null, { timeout: 60000, skipErrorToast: true })`）——注意**同區塊的 `tradingRadar.runExportNow` 沒有 timeout 覆寫**，不要照它抄：
  ```js
  // Task 249：手動重新整理＝先回補台股行情再重算；外部抓取需時，逾時放寬至 45s（nginx /api/ 為 60s）
  refresh: () => api.post('/bff/trading-radar/refresh', null, { timeout: 45000 }),
  ```
- [ ] 249.9 **前端 view**。`frontend/src/views/TradingRadarView.vue`：
  - 既有 `async function load(manual = false, silent = false)` 內是 `const next = await bffApi.tradingRadar.get()`。改法：**保留 `load()` 原樣不動**（SSE 路徑要繼續用它），另加一支 `async function manualRefresh()` 供按鈕使用，內部呼叫 `bffApi.tradingRadar.refresh()`，把 `resp.radar` 指派給 `radar.value`、依 `resp.priceRefresh.outcome` 顯示 `ElMessage`，並以 `refreshing` 控制按鈕 loading、`finally` 中復位。
  - 模板 `<el-button :icon="Refresh" :loading="refreshing" @click="load(true)">重新整理</el-button>` 改為 `@click="manualRefresh"`。
  - **`recalculateRadar()` 內的 `await load(false, true)` 一行都不准改。** 它是 SSE `price-update` 事件經 2 秒 debounce 後的背景重算；若改成呼叫 POST，會變成「抓取 → 寫 Redis → 觸發 `price-update` → 2 秒後再抓取」的自我餵食迴圈，持續對外部 API 發請求。
  - `outcome` 對應文案（**不得宣稱做了沒做的事**）：`FETCHED` →「已重新抓取即時報價並重算」（success）；`CLOSED_SYNCED` →「台股目前休市，已同步至最新收盤價並重算」（success）；`SKIPPED_PENDING_CLOSE` →「今日收盤價尚未落檔，已保留最新成交價並重算」（info）；`COOLDOWN` →「30 秒內剛更新過，已直接重算」（info）；`BUSY` →「行情更新進行中，已以現有報價重算」（info）；`TIMEOUT`／`FAILED` →「行情抓取未完成，已以現有報價重算」（warning）。未知 `outcome` 一律走 warning 分支的通用文案，不得讓訊息為 `undefined`。
  - **頁面上方 `el-alert` 的 `description` 必須改**。現值為：「本頁讀取系統既有 PostgreSQL 與 Redis 資料，並將每次結果快照寫入 Redis 供匯出；不會送出 Claude、OpenAI 或其他 AI API 請求，**也不會觸發外部行情回補**。」末句在本任務後為假陳述。改為明示：不送出任何 AI API 請求；**按下「重新整理」會觸發一次台股行情回補**；頁面自動更新（SSE）與其餘操作不會。
- [ ] 249.10 **不升規則版本**。`TradingRadarRuleEngine.RULE_VERSION` 維持 `TW_RULES_V7`，前端 `TradingRadarView.vue` 內兩處 hardcode fallback 字串（顯示 fallback `radar.ruleVersion || 'TW_RULES_V7'` 與 `radar` ref 初始值）**維持不動**。理由：Task 217.6／223／228／232 升版都是因為規則本身或其輸入語意改變；本任務不碰規則引擎、不碰 `MarketSummary.stale`／`intraday` 語意、不碰任何因子或門檻，同一份輸入在前後產生完全相同的輸出。升版會製造假的不可比性訊號，並依 Requirement 43／44 既有規定觸發通知基準（`last_action`／`last_counter_trend_state`）全面重建與首輪只建基準不寄信，代價實在而效益為零。
- [ ] 249.11 **測試（與實作同一任務，不得延後）**：
  - `backend/src/test/java/com/steven/assets/service/TradingRadarRefreshServiceTest.java`（JUnit 5 ＋ `@ExtendWith(MockitoExtension.class)` ＋ `@Mock`，與同目錄既有 `TradingRadarMarketFreshnessTest` 同風格）：
    - 開盤中且兩把冷卻鍵都取得 → 呼叫 `refreshTradingRadarPrices()` 一次，`outcome=FETCHED`。
    - 休市（`isMarketOpenNow("台股")` 為 false）→ `outcome=CLOSED_SYNCED`。
    - external 回 `skippedPendingClose=true` → `outcome=SKIPPED_PENDING_CLOSE`。
    - **owner 鍵取得但全域鍵 `setIfAbsent` 回 `false`** → `verify(priceQueryService, never()).refreshTradingRadarPrices()`、`outcome=COOLDOWN`（這條專門釘住全域鍵，只測 owner 鍵不算覆蓋）。
    - owner 鍵 `setIfAbsent` 回 `false`（冷卻中）→ 同上，`outcome=COOLDOWN`。
    - external 拋 `IllegalStateException` → 不上拋、仍回完整 `radar`、`outcome=TIMEOUT`。
    - external 拋 `RuntimeException` → 不上拋、仍回完整 `radar`、`outcome=FAILED`。
    - external 回 `busy=true` → `outcome=BUSY`。
    - **`outcome` 與 `priceRefresh.twMarketOpen` 同源**：令 `isMarketOpenNow("台股")` 回 `true` 而 external 回傳的 `twMarketOpen` 為 `false`，斷言結果為 `{outcome:"FETCHED", twMarketOpen:true}`（證明沒讀 external 那個欄位）。
    - **每一個案例都要斷言 `radar` 非 null**（降級的定義是「仍給結果」）。
  - `external-materials-service/src/test/java/com/steven/assets/externalmaterials/service/TwRadarRefreshServiceTest.java`：
    - `isTwMarketOpen()=true` → `verify(poller).updatePrices(any(), eq("台股"), eq(false))` ＋ `verify(taiex).updateOnce()`，且 `verify(poller, never()).syncClosedFromDb(any(), any())`。
    - **`0000` 過濾**：令 `collectHeldStockCodes` 塞入含 `"0000"` 的 tw set，斷言傳給 `updatePrices` 的 `Set`（用 `ArgumentCaptor`）**不含 `"0000"`**。
    - `isTwMarketOpen()=false` 且 `isTradingDay("台股", today)=false`（週末）→ `verify(poller).syncClosedFromDb(any(), eq("台股"))` ＋ `verify(taiex, never()).updateOnce()`、`skippedPendingClose=false`。
    - **13:30–13:32 空窗**：`isTwMarketOpen()=false`、`isTradingDay("台股", today)=true`、時間在 13:30 之後、所有 `findMaxTradingDate(code,"台股")` 回**昨日** → `verify(poller, never()).syncClosedFromDb(any(), any())`、`skippedPendingClose=true`。
    - **空窗已結束**：同上但 `findMaxTradingDate` 回**今日** → 照常 `syncClosedFromDb`、`skippedPendingClose=false`。
    - **盤前不得誤觸守門**：交易日、`isTwMarketOpen()=false`、時間在 13:30 **之前**（例如 08:30）、`findMaxTradingDate` 回昨日 → **照常** `syncClosedFromDb`、`skippedPendingClose=false`。這條釘住守門的時間下界；缺了它，盤前 9 小時都會回假的 `SKIPPED_PENDING_CLOSE`。（時間可比照 `today` 的注入方式讓測試可控，不得用真實時鐘決定測試結果。）
    - **無歷史列的代號不納入同步**：某代號 `findMaxTradingDate` 回 `Optional.empty()`，在守門生效時段內不得出現在傳給 `syncClosedFromDb` 的集合裡（`ArgumentCaptor` 驗證）。
    - **`tw` 為空**：`collectHeldStockCodes` 什麼都沒收到 → `skippedPendingClose=false`（不得回出「今日收盤價尚未落檔」的假訊息）。
    - `taiex.updateOnce()` 拋例外時 `refresh()` 仍正常回傳 `performed=true`（個股結果不受連累）。
    - **大盤逾時不連累個股，且逾時上限沒有被抵銷**：把 `taiexWaitSeconds` 設為 `1`，令 mock 的 `taiex.updateOnce()` 阻塞 5 秒，斷言 `refresh()` 在 **3 秒內**回傳、`performed=true`、`indexUpdated=0`，且個股 `updatePrices` 已被呼叫。**這條是「不得用 try-with-resources 包住 `ExecutorService`」的唯一迴歸守門**——若實作者用了 try-with-resources，`close()` 會 `awaitTermination` 等滿 5 秒，這條測試就會失敗。
    - 併發：先以另一執行緒佔住 `Semaphore`（或連續兩次呼叫中第一次卡住），第二次 `refresh()` 回 `busy=true` 且 `verify(poller, never())` 任何抓取方法。
  - **迴歸測試**：既有 `GET /api/trading-radar` 路徑不得因本任務產生任何外部呼叫。在 `TradingRadarRefreshServiceTest` 或新增的測試中，對 `TradingRadarService.get()` 的既有測試路徑斷言 `PriceQueryService.refreshTradingRadarPrices()` 零互動。

## 驗證

> **`-DargLine` 不可省。** 本機 maven 跑在 JDK 25 上（`pom.xml` 的 `<java.version>21</java.version>` 只是編譯目標），Mockito 的 inline mock-maker 會以
> `MockitoException: Could not modify all classes [class ...PricePoller, class java.lang.Object]` 讓**每一個** `@Mock` 測試爆掉。
> 必須用 `-DargLine`（surefire 會 fork JVM，直接 `-Dnet.bytebuddy.experimental=true` 傳不進去）。

```bash
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -DargLine="-Dnet.bytebuddy.experimental=true" test
```

```bash
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f external-materials-service/pom.xml -DargLine="-Dnet.bytebuddy.experimental=true" test
```

```bash
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f bff/pom.xml package -DskipTests
```

前端建置。**worktree 內沒有 `node_modules`**（它在主 repo 且被 gitignore），`npm run build` 會 `vite: command not found`；先連結過去，建完移除：

```bash
ln -s /Users/steven/Project/asset-management/frontend/node_modules frontend/node_modules && /Users/steven/.nvm/versions/node/v22.21.0/bin/node frontend/node_modules/.bin/vite build --root frontend; rm frontend/node_modules
```

部署（本專案沒有 dev server，「改好」＝ image rebuild ＋ container recreate；JVM service 一律 `--no-cache`，否則 cached layer 會產出不含本次變更的 stale jar）：

```bash
docker compose -p asset-management build --no-cache business-services external-materials-service
```

```bash
docker compose -p asset-management build --no-cache frontend
```

```bash
docker compose -p asset-management up -d --no-deps --force-recreate business-services external-materials-service frontend
```

重建 business-services 會換 IP，BFF 會握著舊 IP 回 500 且因 Docker DNS TTL 600s 不會自癒，故必須接著重啟 BFF：

```bash
docker compose -p asset-management restart bff
```

驗證運行中的 jar 真的含本次變更（不是 stale image）：

```bash
docker exec asset-business-services sh -c 'unzip -l /app/app.jar | grep -i TradingRadarRefreshService'
```

```bash
docker exec asset-external-materials-service sh -c 'unzip -l /app/app.jar | grep -i TwRadarRefreshService'
```

免 OAuth 的端到端驗證（在 business 容器內直接打，帶租戶 header 模擬使用者）：

```bash
docker exec asset-business-services sh -c 'time curl -s -X POST -H "X-User-Id: 1" -H "X-User-Role: ROLE_ADMIN" -H "X-User-Status: ACTIVE" http://localhost:8080/api/trading-radar/refresh | head -c 600'
```

預期：HTTP 200，body 含 `"priceRefresh":{"outcome":"FETCHED"...}`（開盤中）或 `"CLOSED_SYNCED"`（休市），且 `radar.stocks` 非空。**緊接著再打一次同一指令**，預期 `outcome` 變為 `COOLDOWN`（30 秒冷卻生效），耗時應明顯短於第一次。

確認 external 端真的被呼叫（`[tw-radar-refresh]` 前綴是與既有排程 log「更新台股即時價格 (N 檔)」區分的唯一依據；一律用 `--since` 而非 `--tail`，否則會撈到背景排程的舊行）：

```bash
docker logs --since 60s asset-external-materials-service | grep "\[tw-radar-refresh\]"
```

確認 **GET 路徑未被汙染**——先打一次 GET，再確認這 20 秒內 external 端**零** `[tw-radar-refresh]` log（下面第二條必須輸出 `0`）：

```bash
docker exec asset-business-services sh -c 'curl -s -o /dev/null -w "%{http_code}\n" -H "X-User-Id: 1" -H "X-User-Role: ROLE_ADMIN" -H "X-User-Status: ACTIVE" http://localhost:8080/api/trading-radar'
```

```bash
docker logs --since 20s asset-external-materials-service | grep -c "\[tw-radar-refresh\]"
```

前端 bundle 確認含新端點、且 SSE 重算仍走 GET：

```bash
docker exec asset-frontend sh -c 'grep -l "bff/trading-radar/refresh" /usr/share/nginx/html/assets/*.js'
```

## 完成報告

**實作日期：** 2026-07-29

**新增檔案（4）**
- `external-materials-service/src/main/java/com/steven/assets/externalmaterials/service/TwRadarRefreshService.java`
- `external-materials-service/src/test/java/com/steven/assets/externalmaterials/service/TwRadarRefreshServiceTest.java`（11 案例）
- `backend/src/main/java/com/steven/assets/service/TradingRadarRefreshService.java`
- `backend/src/test/java/com/steven/assets/service/TradingRadarRefreshServiceTest.java`（10 案例）

**修改檔案（6）**
- `external-materials-service/.../controller/InternalPriceController.java`：新增 `POST /internal/refresh/tw-radar`
- `backend/.../service/PriceQueryService.java`：新增 `refreshTradingRadarPrices()` ＋ `EXTERNAL_REFRESH_TIMEOUT_SECONDS=30`
- `backend/.../dto/TradingRadarDto.java`：新增 `PriceRefresh`／`RefreshResponse`（`Response` 未動）
- `backend/.../controller/TradingRadarController.java`：新增 `POST /refresh`（`@GetMapping get()` 未動）
- `frontend/src/api/index.js`：新增 `tradingRadar.refresh()`，per-call `timeout: 45000`
- `frontend/src/views/TradingRadarView.vue`：按鈕改 `@click="manualRefresh"`、新增 `manualRefresh()` 與 `REFRESH_MESSAGES`、改寫 `el-alert` 文案（`load()`／`recalculateRadar()` 一行未動）

**與原計畫的偏差（2 處，皆為實作時發現的必要調整）**

1. **`TwRadarRefreshService` 多一個 package-private 欄位 `java.time.Clock timeSource`。** 原計畫直接用 `LocalDate.now(MarketClock.TW_ZONE)`／`LocalTime.now(...)`，但那會讓「13:30 前後」的守門測試取決於**跑測試的真實時刻**——盤前守門那條測試只有在真實時間介於 00:00–13:30 時才會通過，是典型的 flaky。改為可注入時鐘後，`盤前照常同步不得誤判為空窗` 與 `收盤未落檔的空窗不得同步` 兩條才能穩定釘住分界。生產不覆寫。
2. **`Summary.busy()` 改為 `Summary.busy(boolean twMarketOpen)`。** 原計畫寫無參數版，但 busy 分支仍應回報當下市場狀態供 log 使用。business 端本來就不讀 external 的 `twMarketOpen`，此改動不影響契約。

**驗證輸出**

- `backend` 全套：`Tests run: 249, Failures: 0, Errors: 0, Skipped: 0` — BUILD SUCCESS
- `external-materials-service` 全套：`Tests run: 116, Failures: 0, Errors: 0, Skipped: 0` — BUILD SUCCESS
- 兩支新測試：`TradingRadarRefreshServiceTest` 10 tests / 0 errors / 0 failures；`TwRadarRefreshServiceTest` 11 tests / 0 errors / 0 failures
- **大盤逾時上限實測生效**：`大盤逾時不得拖住整體` 以 `taiexWaitSeconds=1` 對上阻塞 5 秒的 mock，實測 `耗時 1006 ms` 回傳（若誤用 try-with-resources 包住 executor，`close()` 會 awaitTermination 等滿 5 秒而使此案例失敗）
- `bff` package：BUILD SUCCESS
- 前端 `vite build`：`✓ built in 4.50s`；bundle 驗證 `index-sqajz9F6.js` 同時含 `bff/trading-radar/refresh`（新 POST）與 `get:()=>he.get("/bff/trading-radar")`（GET wrapper 未被取代，SSE 路徑安全）
- `scripts/spec-check.sh`：0 BLOCK / 0 CHECK

**尚未執行**：Docker image rebuild ＋ container recreate ＋ 容器內端到端 curl 驗證（本專案共用同一套 stack，須依「merge 後從 main 的 worktree 重建」規則進行，待使用者指示）。
