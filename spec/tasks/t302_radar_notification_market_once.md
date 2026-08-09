# [t302] 通知評估效能：每輪每市場只組一次大盤、市場脈絡 bounded 查詢、通知路徑不抓新聞

**對應 Requirements:** Requirement 43／44 之 `TW_RULES_V12` 波修訂第 9 條
**前置任務:** t301（同檔 `TradingRadarNotificationService`，實作順序 t301 → t302）
**Liquibase changeset:** 無

## 背景

**現在的浪費行為**（決策輸出正確，但成本結構錯誤）：

1. `TradingRadarNotificationService.flushEvaluations` 對每檔 setting 呼叫 `TradingRadarService.evaluateForNotification(code, market, held)`，後者**每檔重建一次大盤**：台股走 `marketContextService.resolve(decisionInstant)` ＋ `buildMarket`（含 `indicatorService.computeAll("0000","台股")` 與 241 筆指數查詢）、美股走 `buildUsMarket`。2 秒節拍 × N 檔 setting，同一輪的大盤被重算 N 次。
2. `marketContextService.resolve()` 內部是**全表掃描**：`twseRepo.findAllByOrderByTradingDateAsc()`（約 10 年 ×250 筆）＋ IXIC／SOX 全史（`findByIndexCodeOrderByTradingDateAsc`），而純函數 `resolveMarketFromRows` 實際只用最新一筆＋前 20 個正量能日（台股）與最新共同日＋各前一筆（美股科技）。
3. `resolve()` 同時抓 72 小時新聞（`publicInformation`）——通知路徑對 `Resolved.publicInformation()` **棄置未用**，每檔每輪白查一次。

**正確行為**：(a) 每輪 flush 對出現的每個市場只組一次大盤狀態；(b) 市場脈絡 production 入口改 bounded 查詢；(c) 通知路徑不發新聞查詢。**決策輸出（分數／動作／reasons／risks）逐位不變**——本任務是純效能重構，不升版。

## 要做什麼

- [x] 302.1 `backend/src/main/java/com/steven/assets/service/TradingRadarMarketContextService.java`：
  - 拆出 `public MarketContext resolveMarket(Instant decisionInstant)`：bounded 查詢後委派**既有純函數** `resolveMarketFromRows(decisionInstant, twRows, usRows)`（純函數本體與簽章**不動**，回測相容）。bounded 取數：
    - 台股：`twseRepo.findTopNByOrderByTradingDateDesc(60)`（repository 既有方法，`buildMarket` 已用 241 筆呼叫過）——`resolveMarketFromRows` 需要最新列＋前 20 個正量能日＋漲跌用前一列，60 筆含假日與缺量能列的餘裕；傳入前**不需**排序（純函數內部自行 asc 排序與 completion-instant 過濾）。
    - 美股：`usIndexRepo.findTopNByIndexCodeOrderByTradingDateDesc("IXIC", 15)` 與 `("SOX", 15)` 合併成一個 list（repository 既有方法）——UsTech 只需最新共同日＋各自嚴格前一筆，且超過 5 個日曆日整組 unavailable，15 筆足夠。
  - **市場讀取的 fail-soft try/catch 移入 `resolveMarket`**（bounded 查詢＋委派失敗時 log warn 並回 `MarketContext.EMPTY`，比照現行 `resolve()` 市場側 catch 的行為）——如此 302.2 的 `buildMarketSnapshot` 與 302.3 的「不會拋出」斷言才成立，DB 例外不得從 `flushEvaluations` 的 `computeIfAbsent`（位於逐檔 catch 之外）逸出。
  - `resolve(Instant)` 改為：`resolveMarket(decisionInstant)`（已自帶市場側 catch）＋ 既有 `publicInformation(decisionInstant)`（保留自己獨立的新聞側 catch），對外行為（`Resolved` 形狀、兩側 fail-soft 各自獨立）不變。原 `findAllByOrderByTradingDateAsc` 與 `findByIndexCodeOrderByTradingDateAsc` 在本服務內不再被呼叫（repository 方法若無其他呼叫端則保留不刪，避免血脈擴散；完成報告註記呼叫端現況）。
- [x] 302.2 `backend/src/main/java/com/steven/assets/service/TradingRadarService.java`：
  - 新增 public record `MarketSnapshot(TradingRadarRuleEngine.MarketRegime regime, boolean stale, Instant decisionInstant)`（既有 private `MarketState` 不動——它含 DTO summary，通知路徑不需要）。
  - 新增 `@Transactional(readOnly = true) public MarketSnapshot buildMarketSnapshot(String market)`：`Instant now = Instant.now()`；台股 → `marketContextService.resolveMarket(now)` ＋ `buildMarket(context, now)`（`buildMarket` 既有簽章吃 `MarketContext`，直接沿用）；美股 → `buildUsMarket(now)`；回傳 `(regime, stale, now)`。
  - 新增 overload `@Transactional(readOnly = true) public TradingRadarDto.StockDecision evaluateForNotification(String stockCode, String market, boolean held, MarketSnapshot snapshot)` → `buildStock(new Target(code, market, held), snapshot.regime(), snapshot.stale(), snapshot.decisionInstant())`。
  - 既有三參數 `evaluateForNotification(code, market, held)` 改為委派：`evaluateForNotification(code, market, held, buildMarketSnapshot(market))`——**台股路徑經此改用 `resolveMarket`（不再抓新聞）**；行為差異僅此一項，決策輸出不變。
  - 頁面路徑 `assemble()` **不動**（仍走 `resolve()`，需要新聞；經 302.1 自動享 bounded 查詢）。
- [x] 302.3 `TradingRadarNotificationService.flushEvaluations`：settings 收集後，`Map<String, TradingRadarService.MarketSnapshot> byMarket = new HashMap<>()`，對每檔 setting `byMarket.computeIfAbsent(setting.getMarket(), tradingRadarService::buildMarketSnapshot)`，`evaluateSettingSafely` 改吃 snapshot 傳四參數 overload。單檔評估失敗仍逐檔 catch（既有 fail-soft 不變）；`buildMarketSnapshot` 自身失敗會回 `DATA_INCOMPLETE + stale=true`（`buildMarket`／`buildUsMarket` 既有 catch → `incompleteMarket`），不會拋出。
- [x] 302.4 測試：
  - `TradingRadarMarketContextServiceTest`（既有純函數測試不動）新增**兩個獨立測試方法**（不得合併——`resolve()` 必經 `newsRepo`，同一方法內先呼叫 `resolve` 再 `verifyNoInteractions(newsRepo)` 必失敗）：(i) 以 Mockito stub 兩個 repo，斷言 `resolveMarket(now).equals(resolve(now).market())`（同 stub 資料下兩入口等值）；(ii) 只呼叫 `resolveMarket(now)`，`verifyNoInteractions(newsRepo)`（bounded 入口零新聞互動）。
  - `TradingRadarUsStockEngineTest` 或新檔：兩檔台股＋一檔美股 setting 的一輪 flush，`verify` `twseRepo.findTopNByOrderByTradingDateDesc(60)` 恰一次、IXIC 大盤組裝恰一次（依 mock 粒度擇一驗證「每市場一次」），且 `newsRepo` 零互動。
  - 既有 `evaluateForNotification` 三參數呼叫端測試維持通過。

## 驗證

```bash
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test -Dtest='TradingRadarMarketContextServiceTest,TradingRadarUsStockEngineTest,TradingRadarNotification*Test' -DextraArgLine=-Dnet.bytebuddy.experimental=true
```

```bash
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test -DextraArgLine=-Dnet.bytebuddy.experimental=true
```

部署驗證併入 t305 完成後的整波驗證（見 t305）。

## 完成報告

## t302 完成報告

### 實際改了哪些檔

**Production 程式碼（3 檔）**

1. `TradingRadarMarketContextService.java`
   - 新增 `public MarketContext resolveMarket(Instant decisionInstant)`：bounded 查詢（台股 `twseRepo.findTopNByOrderByTradingDateDesc(60)`；美股 `usIndexRepo.findTopNByIndexCodeOrderByTradingDateDesc("IXIC"/"SOX", 15)` 合併成一個 list）後委派既有純函數 `resolveMarketFromRows`（本體與簽章完全不動，回測相容）。fail-soft try/catch 從 `resolve()` 搬進本方法，查詢或委派失敗回 `MarketContext.EMPTY` 並記警告、不拋出。
   - `resolve(Instant)` 簡化為 `resolveMarket(decisionInstant)`（已自帶市場側 catch）＋ 既有 `publicInformation(decisionInstant)`（保留自己獨立的新聞側 catch），對外 `Resolved` 形狀與兩側 fail-soft 各自獨立不變。
   - 新增私有常數 `MARKET_TW_BOUNDED_ROWS = 60`、`MARKET_US_BOUNDED_ROWS = 15`，javadoc 附上為何選這兩個值（呼應任務背景：純函數只用最新一筆＋前 20 個正量能日／最新共同日＋各自前一筆，超過 5 個日曆日整組 unavailable）。
   - 呼叫端現況（任務要求記錄）：`TwseIndexDailyHistoryRepository.findAllByOrderByTradingDateAsc()` 與 `UsIndexDailyHistoryRepository.findByIndexCodeOrderByTradingDateAsc(String)` 在本服務內不再被呼叫，但用 `grep -ran` 確認仍分別被 `MacroHistoryService`、`BacktestService` 使用，故兩個 repository 方法維持不刪。

2. `TradingRadarService.java`
   - 新增 public record `MarketSnapshot(TradingRadarRuleEngine.MarketRegime regime, boolean stale, Instant decisionInstant)`；既有 private `MarketState` 不動。
   - 新增 `@Transactional(readOnly = true) public MarketSnapshot buildMarketSnapshot(String market)`：台股走 `marketContextService.resolveMarket(now)` ＋ 既有 `buildMarket(context, now)`；美股走既有 `buildUsMarket(now)`；回傳 `(regime, stale, now)`。
   - 新增四參數 overload `evaluateForNotification(stockCode, market, held, MarketSnapshot snapshot)` → 直接 `buildStock(...)`，不重建大盤。
   - 既有三參數 `evaluateForNotification` 改為委派 `evaluateForNotification(stockCode, market, held, buildMarketSnapshot(market))`——台股路徑經此改用 `resolveMarket`（不再抓新聞），行為差異僅此一項，決策輸出不變。
   - 頁面路徑 `assemble()` 完全未動，仍呼叫 `marketContextService.resolve()`（需要新聞，經 302.1 自動享 bounded 查詢）。

3. `TradingRadarNotificationService.java`
   - `flushEvaluations()`：settings 收集後建 `Map<String, TradingRadarService.MarketSnapshot> byMarket = new HashMap<>()`，逐檔以 `byMarket.computeIfAbsent(setting.getMarket(), tradingRadarService::buildMarketSnapshot)` 取快照，`evaluateSettingSafely(setting, snapshot)` 改吃 snapshot 呼叫四參數 overload。逐檔 try/catch 範圍不變（既有 fail-soft 保留）。

**測試（4 檔異動 + 1 新檔）**

4. `TradingRadarMarketContextServiceTest.java`：把原本內聯的 `mock(...)` 改成具名欄位（`twseRepo`／`usIndexRepo`／`exchangeRateRepo`／`newsRepo`），純為讓新測試能對同一組 mock stub／verify；既有 3 個純函數測試方法本體與斷言完全未動（實測仍全過）。新增兩個獨立測試方法（未合併，避免 `resolve()` 必經 `newsRepo` 導致 `verifyNoInteractions` 假失敗）：
   - `resolveMarket與resolve在同一組stub資料下市場數字等值`：同一組資料分別 stub 進 bounded／全表兩種查詢，斷言兩入口 `MarketContext` 相等。
   - `resolveMarket不查詢新聞`：只呼叫 `resolveMarket`，`verifyNoInteractions(newsRepo)`。

5. `TradingRadarUsStockEngineTest.java`：`stubBaseline()` 新增 `marketContextService.resolveMarket(any())` 的 stub（回傳 `MarketContext.EMPTY`），因為 `evaluateForNotification`／`buildMarketSnapshot` 的台股分支現在走 `resolveMarket` 而非 `resolve`；原本 `.resolve(any())` 的 stub 保留供 `assemble()`／`.get()` 用。既有 12 個測試（含兩個 `evaluateForNotification` 三參數呼叫端測試：驗證美股不讀台股資料、台股不讀 IXIC 資料）全數維持通過，斷言內容未改。

6. `TradingRadarNotificationServiceTest.java`（Task 301 建立、先前未 track 進 git）：`setUp()` 改為 stub `tradingRadarService.buildMarketSnapshot(MARKET)` 回傳固定 `MarketSnapshot`，並把 `evaluateForNotification` 的 stub 從三參數改成四參數（帶 `eq(SNAPSHOT)`）——純粹的 mock 呼叫路徑調整，3 個冷卻測試的情境與斷言完全未改，皆維持通過。

7. `TradingRadarNotificationMarketBatchTest.java`（新檔，符合驗證指令的 `TradingRadarNotification*Test` wildcard）：驗證 302.4 第二項——兩檔台股 setting＋一檔美股 setting 同一輪 flush，`verify` `twseRepo.findTopNByOrderByTradingDateDesc(60)` 恰一次、`usIndexRepo.findTopNByIndexCodeOrderByTradingDateDesc("IXIC", 241)` 恰一次、`newsRepo` 零互動。刻意讓 `TradingRadarMarketContextService` 用真實物件（只 mock 底層 repo），才量得到 `resolveMarket` 內部真正的呼叫次數；`TradingRadarService` 其餘依賴刻意 bare mock 不 stub，靠 `buildMarket`／`buildUsMarket`／`buildStock` 既有 fail-soft catch 吸收由此而生的 NPE，讓 flush 平順跑完而不影響呼叫次數斷言（測試輸出可見對應 WARN log，屬預期、非失敗）。

### 與原計畫的偏差及原因

1. **`queueEvaluation()` 只收台股，公開 API 無法讓一輪 flush 同時出現美股 setting。** 這是既有限制（`market != "台股"` 直接 early return），本任務未變更、任務檔也未要求變更。為了讓 302.4 第二項測試真能同時涵蓋「兩檔台股＋一檔美股」，`TradingRadarNotificationMarketBatchTest` 改用反射直接寫入 `TradingRadarNotificationService` 的私有欄位 `pending`，繞過 `queueEvaluation` 的市場閘門——測的是 `flushEvaluations()` 本身的跨市場批次邏輯，不依賴（也不測試）該既有閘門，對 production 行為無任何影響。codebase 內 `ProcessRcloneClientRateLimitTest`／`ExcelImportServiceTest` 已有反射存取私有成員的先例，故沿用同一慣例。
2. **302.4 第二項的位置選了「新檔」而非加進 `TradingRadarUsStockEngineTest`。** 任務檔允許擇一（"或新檔"）；`TradingRadarUsStockEngineTest` 的既有 class javadoc 明確定調主題為 Task 294 美股規則引擎接入，混進通知批次驗證會模糊其既有範圍，故另立新檔。
3. 其餘實作與任務檔逐項一致，未發現需要偏離之處。

### 更新過期望值的測試清單

**無。** 本任務是純效能重構，決策輸出（分數／動作／reasons／risks）依任務背景要求逐位不變，未升版 `RULE_VERSION`。上面列出的既有測試檔異動全部屬於「呼叫路徑／mock 接線調整」（新增或改參數的 stub），沒有任何測試的斷言期望值因規則語意變化而修改。

### 驗證輸出摘要

```
mvn -f backend/pom.xml test -Dtest='TradingRadarMarketContextServiceTest,TradingRadarUsStockEngineTest,TradingRadarNotification*Test' -DextraArgLine=-Dnet.bytebuddy.experimental=true
→ Tests run: 30, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS
  （TradingRadarMarketContextServiceTest 5、TradingRadarUsStockEngineTest 12、
    TradingRadarNotificationServiceTest 3、TradingRadarNotificationTransitionTest 9、
    TradingRadarNotificationMarketBatchTest 1）

mvn -f backend/pom.xml test -DextraArgLine=-Dnet.bytebuddy.experimental=true（整套 backend）
→ Tests run: 622, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS
```

全程使用 `-DextraArgLine`，未使用 `-DargLine`；未寫入 `spec/`、未動 `db/changelog/`、未執行 `git commit`／`push`／`docker`。
