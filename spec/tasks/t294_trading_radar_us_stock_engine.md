# [t294] 交易雷達規則引擎接入美股個股（IXIC 大盤情境＋移除台股限定 filter）

**對應 Requirements:** Requirement 64（交易雷達納入美股個股評分，畫面改為「台股」「美股」兩個分頁）
**前置任務:** t293（美股個股基本面資料抓取；本任務可先落地，`FundamentalAnalysisService` 對美股在 t293 完成前回傳 `unavailable`，不阻塞技術面部分先上線）
**Liquibase changeset:** 無（不新增資料表／欄位，`us_index_daily_history` 為既有表）

## 背景

`TradingRadarService.addTarget()`（`backend/src/main/java/com/steven/assets/service/TradingRadarService.java:530-544`）目前：

```java
private void addTarget(Map<String, Target> targets, Set<String> skippedNonTw,
                       String rawCode, String rawMarket, boolean held) {
    if (rawCode == null || rawMarket == null) return;
    String code = rawCode.trim().toUpperCase();
    String market = rawMarket.trim();
    if (code.isEmpty() || market.isEmpty()) return;
    String key = code + '\0' + market;
    if (!TW_MARKET.equals(market)) {
        skippedNonTw.add(key);
        return;
    }
    if (TAIEX_CODE.equals(code)) return;
    Target existing = targets.get(key);
    targets.put(key, new Target(code, market, held || (existing != null && existing.held())));
}
```

`TW_MARKET = "台股"`（`TradingRadarService.java:48`）。任何 `market="美股"` 的持股／觀察標的會被無條件收進 `skippedNonTw`、完全不進入 `assemble()` 後續的 `.filter(t -> TW_MARKET.equals(t.market()) && ...)`（`TradingRadarService.java:155`）與 `buildStock()`。

`buildMarket()`（`TradingRadarService.java:183-273`）目前只組出**一組** `MarketState`（台股 TAIEX 技術面），並在 `assemble()` 中以 `market.regime()`／`market.stale()` 統一餵給**所有**目標（`TradingRadarService.java:156`：`.map(t -> buildStock(t, market.regime(), market.stale(), decisionInstant))`）。這對美股股票不成立——美股股票的「大盤」應是那斯達克綜合指數（IXIC）自身走勢，不是台股加權指數。`TradingRadarRuleEngine.MarketInput` 已含 `nasdaqChangePercent`／`soxChangePercent`／`usTechCompositePercent`／`usTechAvailable` 三＋一欄（`TradingRadarRuleEngine.java`，經 `TradingRadarMarketContextService.MarketContext` 帶入），但這三欄目前的語意是**台股股票的跨市場領先訊號**（台股開盤晚於美股收盤），不是「美股股票自己的大盤」。

`TechnicalIndicatorService` 已有 `computeAllForTaiex()`（`backend/src/main/java/com/steven/assets/service/TechnicalIndicatorService.java:545-592`）作為「單一指數的完整技術面」既有範例：讀 `TwseIndexDailyHistoryRepository` 的降序序列（240 筆）、判斷今日完成日 K 是否已到、必要時併入 Redis 即時價（`priceQuery.getLive("0000","台股")`），MA／KD 由私有靜態方法 `taiexSimpleMa()`（:594）與 `taiexKd()`（:601）計算，兩者直接吃 `List<TwseIndexDailyHistory>`——**`computeAllForTaiex()` 全程不呼叫 `computeFromSeries()`**（後者簽名為 `computeFromSeries(List<StockPriceHistory> series)`，吃的是個股用的 `StockPriceHistory`，型別不同，不能直接餵 `UsIndexDailyHistory` 序列）。本任務要新增的 `computeAllForNasdaq()` **刻意不併入即時價**（IXIC 目前無對應的 Redis 即時報價來源，且個股自身的即時價已由既有 `PriceQueryService` 路徑處理，不影響個股本身的進出場判斷即時性），只用 `us_index_daily_history` 表 `index_code='IXIC'` 的完成日序列。

`us_index_daily_history` 為既有表（`UsIndexDailyHistory` model，`backend/src/main/java/com/steven/assets/model/UsIndexDailyHistory.java`），複合主鍵 `(index_code, trading_date)`，欄位 `open_point`／`high_point`／`low_point`／`close_point`／`volume`，IXIC 實測有真實成交量（表註解：「SOX 恆為 0（純計算型指數無成交量）」暗示 IXIC 非零）。`UsIndexDailyHistoryRepository` 已有 `findByIndexCodeOrderByTradingDateAsc(String indexCode)`（`TradingRadarMarketContextService.java:98` 已在用），需確認是否已有降序＋限筆數的查詢方法，沒有則新增（比照 `TwseIndexDailyHistoryRepository.findTopNByOrderByTradingDateDesc(int n)` 的既有命名慣例）。

`FundamentalAnalysisService.resolve(code, name, market, decisionInstant)` 呼叫（`TradingRadarService.java:326-327`）本身不需要改——t293 完成後它會依 market 自動回傳美股或台股的結果；t293 未完成前對美股回傳 `unavailable(false)`，`TradingRadarRuleEngine` 既有的 optional-contribution 缺值重分配機制會吸收，**不阻塞**本任務的技術面部分獨立驗收。

`underlyingCurrencyOf()`（`TradingRadarService.java:562-570`）已對 `"美股"` 回傳 `"USD"`，`resolveFx()` 呼叫（`TradingRadarService.java:317-319`）對非 TWD 幣別無條件計算，**本任務不修改這段**——它會在美股 filter 移除後自然對美股個股生效。

## 要做什麼

- [x] 294.1 **`TechnicalIndicatorService` 新增 `computeAllForNasdaq()`**：比照 `computeAllForTaiex()`（`TechnicalIndicatorService.java:545-592`）的同一套結構，資料來源改為 `usIndexDailyHistoryRepo`（需新增依賴注入，若既有 repository 名稱不同以現行程式碼為準）依 `index_code='IXIC'` 取最新 240 筆降序序列（沒有現成方法就新增 `findTopNByIndexCodeOrderByTradingDateDesc(String indexCode, int n)`，比照既有 `TwseIndexDailyHistoryRepository` 命名）。**不併入即時價**（即不做 `computeAllForTaiex()` 那段 Redis live 併入邏輯），純完成日序列。MA／KD 計算**不得**呼叫 `computeFromSeries(List<StockPriceHistory>)`——該方法吃的是個股用的 `StockPriceHistory` 型別，`us_index_daily_history` 對應的是 `UsIndexDailyHistory`，型別不同。正確做法是比照既有 `taiexSimpleMa()`（`TechnicalIndicatorService.java:594`）／`taiexKd()`（:601）的既有寫法，新增對應的 `nasdaqSimpleMa(List<UsIndexDailyHistory>, int window)`／`nasdaqKd(List<UsIndexDailyHistory>)`（或將既有兩支方法泛型化以同時支援兩種序列型別，兩種做法皆可，取決於既有方法簽名的封閉程度，由實作者決定）；序列不足以撐滿 MA240／KD 視窗時該欄位回 `null`（沿用 `taiexSimpleMa()`／`taiexKd()` 既有的缺值行為）。`computeAll(String stockCode, String market)` 的既有 `isTaiex(...)` 分支模式不動，本方法為新增的獨立入口，由 `TradingRadarService` 直接呼叫（不透過 `computeAll` 的 stockCode/market 判斷分支，因為 IXIC 不是「個股」，沒有 stockCode）。**新增 `usIndexDailyHistoryRepo` 欄位會使 `@RequiredArgsConstructor` 產生的建構子從 3 參數變 4 參數**（現行 `TechnicalIndicatorService.java:27-31` 為 `historyRepo`／`priceQuery`／`twseDailyRepo` 三個 `final` 欄位），以下 4 個既有測試檔以位置參數直接呼叫該建構子，**必須同步補上第 4 個參數**（不需要 US 資料的既有測試可傳 `null`）否則編譯失敗：`backend/src/test/java/com/steven/assets/service/BacktestServiceTest.java:67`、`TechnicalIndicatorSeriesAlignmentTest.java:44`、`TechnicalIndicatorTaiexLiveBlendTest.java:37`、`WeeklyMaTest.java:24`。
- [x] 294.2 **`TradingRadarService.buildMarket()` 拆為可重用邏輯，新增美股組**：現行 `buildMarket(TradingRadarMarketContextService.MarketContext context, Instant decisionInstant)` 回傳單一 `MarketState`，改為新增第二個方法 `buildUsMarket(Instant decisionInstant)`：
  - 用 `indicatorService.computeAllForNasdaq()` 取得 IXIC 的 `FullIndicators`。
  - 用 IXIC 序列（同一批 `us_index_daily_history` 列）算 `c60`／`c240`（`ruleEngine.confirm(closes, 60)`／`ruleEngine.confirm(closes, 240)`，比照現行台股組寫法）。
  - 呼叫 `ruleEngine.evaluateMarket(new TradingRadarRuleEngine.MarketInput(price, changePercent, indicators(ind), c60, c240, /* completedMarketChangePercent */ null, /* marketVolumeRatio */ null, /* marketTurnoverRatio */ null, /* nasdaqChangePercent */ null, /* soxChangePercent */ null, /* usTechCompositePercent */ null, /* usTechAvailable */ false))`——**後六個參數全部傳 null／false**，理由見背景段「避免與『本身即 IXIC』的資訊重複計分」。
  - `price`／`changePercent` 取 IXIC 最新完成日收盤與前一日收盤（比照台股組 `closes.get(0)`／`changePercent(closes.get(0), closes.get(1))` 的既有寫法，**不做**台股組那段 Redis 即時價併入的 `liveFreshToday` 分支）。
  - `stale` 判定：比照台股組「完成日 K 未到當前交易日」的既有概念，但改用美東時區判斷（`ZoneId.of("America/New_York")`，比照 `TradingRadarMarketContextService.java:50` 既有的 `NEW_YORK` 常數）；若 `us_index_daily_history` 最新一筆 `trading_date` 早於「已完成的最近一個美股交易日」則 `stale=true`。
  - 回傳的 `MarketState.summary()` 是否需要出現在 `TradingRadarDto.MarketSummary`（畫面「大盤總覽」卡片）由 Task 295 決定顯示方式；本任務只需確保 `TradingRadarService` 內部能拿到美股組的 `MarketRegime` 與 `stale`，**不強制**要求新增回應 DTO 欄位——若 Task 295 設計需要在美股分頁顯示「美股大盤」卡片，屆時在 `TradingRadarDto` 新增對應欄位（本任務先把 regime 計算做出來，DTO 揭露交給 295 依實際畫面需求決定，避免本任務未定案就綁死 DTO 形狀）。
  - 例外處理比照台股組：讀取失敗時回退到「不完整」狀態（`MarketRegime.DATA_INCOMPLETE` 或既有等效常數），**不得**讓美股組例外拖垮台股組（兩者各自 try/catch，互不影響）。
- [x] 294.3 **`assemble()` 依 target.market() 選對應 regime**：`assemble()`（`TradingRadarService.java:145-170`）改為：
  1. 呼叫 `buildMarket(...)` 得台股 `MarketState`（**不變**）。
  2. 呼叫 `buildUsMarket(decisionInstant)` 得美股 `MarketState`（新增，**不論本輪有沒有美股標的都計算**——比照台股組現行行為，即使 `skippedNonTw` 曾經全空也照算大盤，維持「大盤資料與個股清單解耦」的既有設計）。
  3. `.filter(...)` 的白名單擴大為 `Set.of("台股","美股")`（新增常數 `private static final String US_MARKET = "美股";`，命名比照既有 `TW_MARKET`），**英股等其餘市場值不在白名單內、繼續被排除**。
  4. `.map(t -> buildStock(t, regimeFor(t.market(), twMarket, usMarket), staleFor(t.market(), twMarket, usMarket), decisionInstant))`——新增私有方法 `regimeFor`／`staleFor`（或等效寫法）依 `t.market()` 選對應組別，**不得**用同一個變數餵給兩種市場的股票。
- [x] 294.4 **`addTarget()` 的排除邏輯改為白名單判斷**：`TradingRadarService.java:537-538` 的 `if (!TW_MARKET.equals(market))` 改為 `if (!TW_MARKET.equals(market) && !US_MARKET.equals(market))`（或等效的 `Set.contains` 寫法），其餘邏輯（`skippedNonTw.add(key); return;`）不變。`TAIEX_CODE.equals(code)` 那行排除只對台股大盤代碼 `"0000"` 有意義，維持原樣（美股沒有代碼 `0000`，不受影響）。
- [x] 294.5 **ETF 折溢價因子須明確排除美股，不得依賴「查無資料自然為 null」的假設**：`etf_nav_history` 表**現況已有美股列**——既有 `EtfNavPoller`（`external-materials-service/src/main/java/com/steven/assets/externalmaterials/service/EtfNavPoller.java`，Task 214／215，與本次 Requirement 64 無關的既有功能）會對使用者持有／觀察的美股 ETF（如 `VOO`／`QQQ`）逐檔打 Yahoo `quoteSummary` 取淨值，寫入 `etf_nav_history` 的 `market='美股'` 列；`MarketDataFetchService.getUsEtfNav()`（**`external-materials-service/src/main/java/com/steven/assets/externalmaterials/service/MarketDataFetchService.java:656`**——注意此 `MarketDataFetchService` 位於 `external-materials-service`，`backend` 沒有同名類別，不得混淆兩個模組）與 Redis `price:etfnav:美股:{code}` 同樣已存在。故 `TradingRadarService.etfPremiumPct(code, market, decisionInstant)`（`grep -n "etfPremiumPct" backend/src/main/java/com/steven/assets/service/TradingRadarService.java` 確認現行行號，背景段落寫的 `:581-621` 為概略範圍）若不加排除，移除 294.4 的 filter 後會對持有美股 ETF 的使用者回傳**非 null** 的折溢價值，`TradingRadarRuleEngine.etfPremiumContribution()`／`ETF_PREMIUM_EXPENSIVE` 硬否決因此對美股 ETF 生效，直接違反 Requirement 64 AC「美股 ETF 個股本次仍以一般 EQUITY 規則評分，折溢價因子恆為不適用」。**須在 `etfPremiumPct()`（或其呼叫端 `buildStock()`）明確加一條 `market` 短路**：`market="美股"` 時直接回傳 `null`（不查 `etf_nav_history`、不查 Redis），與台股既有查詢路徑完全不重疊。`etfPremiumPercentile()` 依賴 `etfPremiumPct()` 的輸出，只要前者對美股恆為 `null`，後者自然恆為 `null`，不需另外修改。
- [x] 294.6 **`evaluateForNotification()` 同步支援美股**：`TradingRadarService.java:172-181` 的通知評估入口目前呼叫 `buildMarket(...)` 取單一台股 `MarketState`；需同步依傳入的 `market` 參數選擇正確的 regime（比照 294.3 的選擇邏輯），確保背景通知評估與前景頁面對同一檔美股股票算出一致的結果。
- [x] 294.7 **`recomputeAndStoreForOwner()` 沿用 `assemble()` 的既有修改**：本方法（`TradingRadarService.java:131-136`）已委派 `assemble(ownerId)`，294.3 改完後自動涵蓋美股，**不需要額外修改**，但驗證時須確認背景重算路徑（Task 260 既有機制）對美股持股同樣正確計入。
- [x] 294.8 **匯率因子與 ETF 判斷以外，不修改任何既有台股計分邏輯**：`TradingRadarRuleEngine` 本身**不修改**（本任務只是讓更多 `StockInput` 流進同一套既有規則），`buildStock()` 內對 `underlyingCurrencyOf`／`resolveFx`／`fundamentalAnalysisService.resolve()` 的既有呼叫**不修改程式碼**——移除 294.4 的 filter 後這些既有呼叫會自然對美股個股生效。

## 驗證

```bash
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test
docker compose -p asset-management build --no-cache business-services
docker compose -p asset-management up -d --no-deps --force-recreate business-services
curl -s http://localhost:8080/actuator/health
curl -s http://localhost:8080/api/trading-radar | python3 -c "import json,sys; d=json.load(sys.stdin); print([s['market'] for s in d['stocks']][:20])"
```

測試須至少涵蓋：(a) `addTarget` 對 `market="美股"` 不再進 `skippedNonTw`、對 `market="英股"` 或其他任意字串行為不變（回歸）；(b) `computeAllForNasdaq()` 對序列不足 60／240 筆時對應欄位回 `null` 而非擲例外；(c) 構造「台股 regime=RISK_OFF、IXIC regime≠RISK_OFF」的資料情境，斷言美股個股的 `marketAllowsBuy` 不受台股 regime 拖累（反之亦然）——這是本任務最容易因為變數傳錯而silent 出錯的地方，**必須**有這條測試；(d) 美股組 `MarketInput` 的 `nasdaqChangePercent`／`soxChangePercent`／`usTechCompositePercent` 三欄斷言為 `null`、`usTechAvailable` 為 `false`；(e) `buildUsMarket()` 例外時不影響台股組 `buildMarket()` 的正常回傳（互相隔離）；(f) 美股個股確實算出非 null 的 `fxPercentile`（走到既有 FX 路徑）；(g) `evaluateForNotification()` 對美股股票走美股 regime 而非台股 regime；(h) 構造一筆 `etf_nav_history` 已有列的美股 ETF 代碼（比照既有 `EtfNavPoller` 會寫入的既有資料形狀），斷言該檔美股個股的 `etfPremiumPct`／`etfPremiumPercentile` 仍為 `null`、且不觸發 `ETF_PREMIUM_EXPENSIVE` 硬否決——這條測試若不加，294.5 的排除邏輯即使漏寫也不會被任何既有測試發現；(i) `BacktestServiceTest`／`TechnicalIndicatorSeriesAlignmentTest`／`TechnicalIndicatorTaiexLiveBlendTest`／`WeeklyMaTest` 四支既有測試在 `TechnicalIndicatorService` 建構子新增第 4 參數後仍可編譯並通過（回歸）。部署後於實機呼叫 `GET /api/trading-radar`，確認回應中出現 `market="美股"` 的 `StockDecision` 列（前提是該環境的持股或觀察清單本就含美股代碼；若無則以 `POST /api/stock-alert` 或既有觀察清單新增功能先加一檔美股觀察標的再驗證）。

## 完成報告

**實際改動的檔案**

- `backend/src/main/java/com/steven/assets/repository/UsIndexDailyHistoryRepository.java`
  新增 `findTopNByIndexCodeOrderByTradingDateDesc(String indexCode, PageRequest page)` ＋
  `findTopNByIndexCodeOrderByTradingDateDesc(String indexCode, int n)` default 方法，比照
  `TwseIndexDailyHistoryRepository.findTopNByOrderByTradingDateDesc(int n)` 的既有命名慣例。
- `backend/src/main/java/com/steven/assets/service/TechnicalIndicatorService.java`
  新增 `usIndexDailyHistoryRepo` 欄位（`@RequiredArgsConstructor` 建構子第 4 參數）、
  `computeAllForNasdaq()` 公開入口、`nasdaqSimpleMa()`／`nasdaqKd()`／
  `toRow(UsIndexDailyHistory, String, String)` 私有方法。全程不呼叫 `computeFromSeries()`，
  比照 `computeAllForTaiex()`／`taiexSimpleMa()`／`taiexKd()` 的既有寫法，刻意不併入即時價。
- `backend/src/main/java/com/steven/assets/service/TradingRadarService.java`
  新增 `usIndexDailyHistoryRepo` 欄位（建構子第 7 個位置，緊接在 `twseRepo` 之後）、
  常數 `US_MARKET`／`IXIC_CODE`／`NEW_YORK`／`US_MARKET_CLOSE`；新增
  `buildUsMarket(Instant)`、`mostRecentCompletedUsTradingDay(Instant)`、
  `regimeFor(String, MarketState, MarketState)`、`staleFor(String, MarketState, MarketState)`
  四個私有方法；`assemble()` 改為同時組裝 `twMarket`／`usMarket` 兩組、filter 白名單擴大為
  `{台股, 美股}`、依 `target.market()` 選對應 regime／stale；`addTarget()` 排除條件改為
  `!TW_MARKET.equals(market) && !US_MARKET.equals(market)`；`evaluateForNotification()`
  依傳入 market 只組裝需要的那一組（美股不再連帶組裝台股組，反之亦然）；`etfPremiumPct()`
  開頭新增 `if (US_MARKET.equals(market)) return null;` 短路，不查 `etf_nav_history` 也不查 Redis。
- 既有測試建構子同步修正（294.1 指定的 4 支 + 另外查出的 2 支）：
  `BacktestServiceTest.java`（重用既有的 `usIndexRepo` mock 當第 4 參數）、
  `TechnicalIndicatorSeriesAlignmentTest.java`／`TechnicalIndicatorTaiexLiveBlendTest.java`／
  `WeeklyMaTest.java`（第 4 參數傳 `null`，這三檔不涉及 IXIC）、
  `TradingRadarServiceOwnerScopeTest.java`／`TradingRadarMarketFreshnessTest.java`
  （新增 `@Mock UsIndexDailyHistoryRepository usIndexDailyHistoryRepo` 並補建構子位置＋stub，
  因為 `buildUsMarket()` 每輪都會執行，不補 stub 會靠隱性的 NPE→catch 兜底，故明確 stub 較穩）。
- 新增測試檔：
  `backend/src/test/java/com/steven/assets/service/TechnicalIndicatorNasdaqTest.java`（5 案例，對應 (b)）、
  `backend/src/test/java/com/steven/assets/service/TradingRadarUsStockEngineTest.java`（9 案例，對應 (a)(c)(d)(e)(f)(g)(h)）。

**與原計畫的偏差及原因**

1. **294.1 只點名 4 支既有測試，實際受影響的是 6 支。** `TechnicalIndicatorService` 的建構子新增
   參數只影響那 4 支（已在任務檔中列出）。但 `TradingRadarService` 本身也因為新增
   `usIndexDailyHistoryRepo` 欄位而多一個建構子參數（buildUsMarket 需要直接讀 IXIC 序列算
   price／changePercent／confirm(60/240)，不能只靠 `computeAllForNasdaq()` 回傳的
   `FullIndicators`，因為那個方法不回傳原始 close 序列——這點任務檔背景段落沒有明講，是實作時
   推導出的必然結果）。用 `grep -rn "new TradingRadarService("` 找出另外 2 支既有測試檔
   （`TradingRadarServiceOwnerScopeTest.java`／`TradingRadarMarketFreshnessTest.java`）一併修正。
   任務檔正文最後一句「請務必仔細找出所有受影響處」已預期這種情況，故視為在授權範圍內，非擅自擴大範圍。
2. **`etfPremiumPct(code, market, decisionInstant)` 現行行號為 613-627（含新增的短路判斷後）**，
   與任務檔背景段落寫的概略行號 `:581-621` 有落差，屬正常行號漂移（前面新增了大盤／IXIC 相關程式碼），
   內容與任務要求一致。
3. **294.2 的 `buildUsMarket()` 例外回退訊息與 `MarketState.summary()` 建構**：任務檔說「不強制要求
   新增回應 DTO 欄位」，但 `MarketState` 這個私有 record 本身要求非 null 的
   `TradingRadarDto.MarketSummary summary` 欄位，所以仍然組出一份完整 `MarketSummary`（只是目前
   `assemble()` 回應中只揭露 `twMarket.summary()`，`usMarket.summary()` 尚未接到任何 DTO 欄位）——
   這是照任務檔的原意執行，`Response.market` 維持只回台股組，Task 295 屆時可直接取用
   `usMarket.summary()` 不必回頭改本任務的程式碼。
4. **驗證段落的 `mvn` 指令需額外加 `-DextraArgLine=-Dnet.bytebuddy.experimental=true`**：
   Java 25 + Mockito 的已知相容性問題（見專案記憶 `feedback_mockito_java25_bytebuddy`），
   不加此旗標時任何用到 `@Mock`／`Mockito.spy()` 的測試（含新增與既有的）會在 mock 建立階段
   直接報 `MockitoException`，屬環境層級既有限制、與本次程式碼改動無關；已用該旗標完整驗證通過。
   `docker compose build/up` 與實機 `curl` 驗證依指示略過，留給後續統一部署驗證。
5. **`TAIEX_CODE.equals(code)` 排除只對台股 "0000" 生效，未新增對 `IXIC_CODE` 的對稱排除。**
   任務檔 294.4 已明確指示「維持原樣，美股沒有代碼 0000，不受影響」，故未新增。經 arch-auditor
   查證，這不構成架構違規，只是一個「若使用者手動把 IXIC／美股加進觀察清單，會被當成一般個股流入
   `buildStock()`」的潛在業務邊角案例（IXIC 目前不是任何使用者可交易的個股代碼，實務上不會發生），
   在此記錄供後續參考，未在本任務動作。

**測試結果**

```
mvn -o -f backend/pom.xml test -DextraArgLine=-Dnet.bytebuddy.experimental=true
[INFO] Tests run: 596, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

新增的兩支測試檔（`TechnicalIndicatorNasdaqTest` 5 案例、`TradingRadarUsStockEngineTest` 9 案例）
與既有 6 支受影響測試檔（`BacktestServiceTest` 15、`TechnicalIndicatorSeriesAlignmentTest` 21、
`TechnicalIndicatorTaiexLiveBlendTest` 4、`WeeklyMaTest` 3、`TradingRadarServiceOwnerScopeTest` 2、
`TradingRadarMarketFreshnessTest` 4）全部通過，涵蓋驗證段落 (a)–(i) 全部案例。

> **spec-review 對帳更正（t295 實作前重審時發現）**：原文標示 592，與同批次 t293 完成報告
> 的 backend 596 互相矛盾——t293 對 backend 唯一異動是 `FundamentalAnalysisServiceTest`
> 新增 4 條測試，t294 另新增 2 支測試檔共 14 條、並修改 6 支既有測試檔的建構子簽名（未新增
> `@Test`）；若兩者皆已落地於同一份 working tree，理應是遞增關係、不應互相矛盾。已對合併後
> 的最終 working tree 重新完整跑一次
> `mvn -f backend/pom.xml -DextraArgLine=-Dnet.bytebuddy.experimental=true test`，
> 權威結果為 **596**（exit code 0、BUILD SUCCESS，Failures/Errors 均為 0），已回填取代原本
> 記錄的 592；判斷原 592 應是量測時機早於 t293 的部分變更落地於同一 working tree 所致。

**架構符規查證**：已派 `arch-auditor`（diff-scoped，僅餵本次變更的 tracked diff ＋ 2 支新檔全文，
排除同 worktree 中平行進行的 t293 變更）。判定 critical/major/minor 均為 0，已用
`bash .claude/hooks/arch-review-pass.sh` 記錄通過。
