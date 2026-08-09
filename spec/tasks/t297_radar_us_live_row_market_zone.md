# [t297] 交易雷達美股 live K 併入判定改用標的市場時區

**對應 Requirements:** Requirement 43（今日交易雷達——不呼叫 AI API 的規則式買賣決策輔助）之 `TW_RULES_V12` 修訂第 1 條
**前置任務:** 無（本任務可獨立實作；整波 t297–t305 一次部署，升版由 t298 執行）
**Liquibase changeset:** 無

## 背景

`TradingRadarService.buildStock()` 組裝個股技術面時，會把「當日尚未寫入完成日 K 的 live 行情」暫加到序列最前（`prepareTechnicalData` → `shouldAddLiveRow`）。**現在的錯誤行為**：`shouldAddLiveRow` 用 `decisionInstant.atZone(TAIPEI).toLocalDate()` 當「今日」，再與 live 的 `tradingDate`（**美東日期字串**，由 external-materials-service 寫入 Redis）比對相等。台北 00:00–約 05:00 正是美東前一日 11:00–16:00（美股下半場至收盤），此時美東日期＝台北日期−1，比對必定失敗 → 美股個股的盤中 live K **不併入**指標序列；但同方法上游的 `price` 取值（`buildStock` 內 `liveOpt.map(LivePrice::price).orElseGet(...)`）**沒有任何日期檢查**、直接採 live 價。結果是「現價是最新 tick、MA／KD／乖離／兩日確認全用舊序列」的 as-of 混搭，正是美股評分最關鍵的時段。

**正確行為**：live K 併入與否的「今日」，必須以**標的市場時區**解讀 `decisionInstant`。台股標的（Asia/Taipei）行為逐位不變。

這是 Task 252 已在 `TechnicalIndicatorService.computeAll` 修過的同類 bug（該處註解明載：「JVM 牆鐘在美股台北 00:00–04:00 會取到 D+1，與 tradingDate（D）比對失敗 → 今日即時價不併入 → MA/KD 用舊序列算」，修法是 `MarketZones.today(market)`）。雷達的組裝路徑（Task 294 讓美股進雷達時）漏掉了同樣的處理。

**重現條件**：`decisionInstant` 取台北日期 D 的 00:30（= 美東 D−1 的 11:30，冬令）；Redis live 的 `tradingDate = "D−1"`（美東日期）；`stock_price_history` 最新完成日 < D−1。現行程式 live K 不併入；修正後應併入。

## 要做什麼

- [x] 297.1 `backend/src/main/java/com/steven/assets/service/TradingRadarService.java` 的 `shouldAddLiveRow(List<StockPriceHistory> completedRows, PriceQueryService.LivePrice live, Instant decisionInstant)` 增加 `String market` 參數，方法內 `LocalDate today = decisionInstant.atZone(TAIPEI).toLocalDate();` 改為 `LocalDate today = decisionInstant.atZone(com.steven.assets.util.MarketZones.resolve(market)).toLocalDate();`。**同時把方法可見度由 `private` 改為 package-private**（供同 package 測試以寫死的 `Instant` 直接呼叫——`assemble`／`evaluateForNotification` 內 `decisionInstant = Instant.now()` 且無 Clock 注入，服務層級測試無法控制時刻；這是本專案對純判斷方法的既有測試縫作法）。**不得改用 `LocalDate.now(...)` 或 `MarketZones.today(market)`**——`MarketZones.today` 內部是 `LocalDate.now(zone)`，會讀系統時鐘；本方法必須維持「所有時間判斷都來自顯式 `decisionInstant`」的既有純度契約（背景重算 `recomputeAndStoreForOwner` 與 HTTP 路徑共用同一 `decisionInstant` 語意）。`MarketZones.resolve(market)`：`"美股"` → America/New_York、`"英股"` → Europe/London、其餘（含台股）→ Asia/Taipei；雷達 targets 只有台股／美股，台股走 Asia/Taipei 分支 → 行為逐位不變。
- [x] 297.2 呼叫端 `prepareTechnicalData(...)` 內 `liveOpt.filter(live -> shouldAddLiveRow(completedRows, live, decisionInstant))` 改傳 `target.market()`（`prepareTechnicalData` 已有 `Target target` 參數，簽章不用改）。
- [x] 297.3 `shouldAddLiveRow` 其餘邏輯不變：`live.tradingDate()==null || live.price()==null` 回 false；`LocalDate.parse` 失敗回 false；`liveDate.equals(today) && (completedRows 為空 || liveDate != completedRows.get(0).tradingDate)` 才回 true。
- [x] 297.4 **不動** `buildStock` 的 `price` 取值（live 無日期檢查是既有刻意行為：盤前 stale live tick ≈ 前收，無害；修正 live K 併入後 as-of 混搭即消失）。**不動** `currentTwTradingDay`／`isFreshNav`（台股專用語意正確）。**不動** `TechnicalIndicatorService`（Task 252 已修）。
- [x] 297.5 測試（加入既有 `backend/src/test/java/com/steven/assets/service/TradingRadarUsStockEngineTest.java`，該檔與 `TradingRadarService` 同 package，可直接呼叫 297.1 改為 package-private 的 `shouldAddLiveRow`，以寫死的 `Instant` 消除執行時刻依賴——**不得**改走 `get()`／`evaluateForNotification` 層級驗證本項，那些入口的 `decisionInstant = Instant.now()` 無法控制，判別力會隨測試執行時刻漂移）：
  - (a) 美股標的、`decisionInstant` 用 `Instant.parse` 寫死為冬令時段 `2026-01-15T16:30:00Z`（= 台北 01-16 00:30 = 美東 01-15 11:30），live `tradingDate="2026-01-15"`（美東當日）、完成列最新為 2026-01-14 → `shouldAddLiveRow(rows, live, instant, "美股")` 斷言 `true`（修正前為 false，此即 bug 的機械重現）。
  - (b) 同一 instant、market `"台股"`、live `tradingDate="2026-01-15"`（台北日期為 01-16）→ 斷言 `false`（回歸：台股行為不變）。
  - (c) 美股標的、live `tradingDate` 等於完成列最新日期（`2026-01-15` 且 rows[0] 亦為 2026-01-15）→ 斷言 `false`（既有防重複行為不變）。
  - **禁用 `completedChangePercent` 當觀測值**：`RadarInputAssembler` 的 `firstCompleted` 位移使該欄位在 live 併入與否兩態下取的都是同一對完成日（C0 vs C1），對本 bug 是不變量，斷言它會得到恆過的假測試。若另加服務層級煙霧測試，觀測 `StockInput.changePercent`（分母隨併入由 C1 位移為 C0）或 captor `computeFromSeries` 的序列筆數／首筆日期。

## 驗證

```bash
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test -Dtest='TradingRadarUsStockEngineTest' -DextraArgLine=-Dnet.bytebuddy.experimental=true
```

```bash
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test -DextraArgLine=-Dnet.bytebuddy.experimental=true
```

部署驗證併入 t305 完成後的整波驗證（見 t305；整波一次 rebuild business-services 並確認 `/api/bff/trading-radar` 正常回應）。

## 完成報告

## t297：交易雷達美股 live K 併入判定改用標的市場時區

### 實際改了哪些檔

**`backend/src/main/java/com/steven/assets/service/TradingRadarService.java`**
- 新增 `import com.steven.assets.util.MarketZones;`。
- `shouldAddLiveRow(...)`：可見度由 `private` 改為 package-private；新增 `String market` 參數（第 4 個參數，順序為 rows／live／decisionInstant／market）；方法內 `LocalDate today = decisionInstant.atZone(TAIPEI).toLocalDate();` 改為 `decisionInstant.atZone(MarketZones.resolve(market)).toLocalDate();`。其餘判斷邏輯（null 檢查、`LocalDate.parse` 失敗回 false、`liveDate.equals(today) && (...)`）逐字不變。補上先前缺的 javadoc，說明「為何必須用標的市場時區」與「為何刻意不用 `MarketZones.today`／`LocalDate.now`」兩點約束（純度契約：所有時間判斷須來自顯式 `decisionInstant`）。
- 呼叫端 `prepareTechnicalData(...)`：`shouldAddLiveRow(completedRows, live, decisionInstant)` 改為 `shouldAddLiveRow(completedRows, live, decisionInstant, target.market())`。`prepareTechnicalData` 簽章未動（`Target target` 本已是參數）。
- 未動：`buildStock` 的 `price` 取值、`currentTwTradingDay`、`isFreshNav`、`TechnicalIndicatorService`、`db/changelog/`。

**`backend/src/test/java/com/steven/assets/service/TradingRadarUsStockEngineTest.java`**
- 新增 import：`com.steven.assets.model.StockPriceHistory`、`java.time.Instant`。
- 新增區段 `(i) shouldAddLiveRow 用標的市場時區判斷「今日」（Task 297）`，內含 297.5 要求的 3 個測試，皆透過同 package 直接呼叫 297.1 改為 package-private 的 `shouldAddLiveRow`，以 `Instant.parse("2026-01-15T16:30:00Z")`（冬令、= 台北 01-16 00:30 = 美東 01-15 11:30）寫死時刻，消除執行時刻依賴：
  - (a) `shouldAddLiveRow美股用美東日期判斷今日跨夜情境應併入`：美股、live `tradingDate="2026-01-15"`、完成列最新 `2026-01-14` → 斷言 `true`（bug 的機械重現：修正前此案例為 `false`）。
  - (b) `shouldAddLiveRow台股仍用台北日期判斷今日行為不變`：同一 instant、market 改「台股」、live 仍 `"2026-01-15"`（台北日期為 01-16）→ 斷言 `false`（回歸：台股行為逐位不變）。
  - (c) `shouldAddLiveRow美股live等於完成列最新日期時不重複併入`：美股、live 與完成列最新日期同為 `2026-01-15` → 斷言 `false`（既有防重複行為不變）。
  - 未使用 `completedChangePercent` 當觀測值（任務檔警示其為本 bug 的不變量，斷言會恆過），也未另加服務層級煙霧測試——297.5 該段落為「若另加」的選用建議，非必做項。

### 驗證輸出摘要

- 定向測試：`mvn test -Dtest='TradingRadarUsStockEngineTest'` → `Tests run: 12, Failures: 0, Errors: 0, Skipped: 0`（既有 9 個＋新增 3 個全綠）。
- 整套 backend 測試：`mvn test`（全套，無 `-Dtest`）→ 彙總 72 個測試報告檔，`Tests run: 599, Failures: 0, Errors: 0, Skipped: 0`。exit code 皆為 0。日誌中出現的 WARN／ERROR（DNS resolver fallback、GdriveOutputSupport 上傳失敗／逾時模擬、TAIEX 指標計算失敗等）均為既有測試刻意觸發的 fail-soft 路徑輸出，非本次變更造成、非測試失敗。
- 手動覆算三個測試案例的時區換算與短路邏輯，與程式碼行為一致（美股分支 `today=2026-01-15`、台股分支 `today=2026-01-16`），與 surefire 綠燈結果吻合。

### 與原計畫的偏差及原因

僅一處風格決定、無邏輯偏差：任務檔 297.1 的異動片段字面上寫 `com.steven.assets.util.MarketZones.resolve(market)`（完整限定名），實作改為新增 `import com.steven.assets.util.MarketZones;` 後直接寫 `MarketZones.resolve(market)`。這是為了與本檔案及 `PriceQueryService`／`TechnicalIndicatorService`／`MarketDataService`／`AlertChartRenderer` 等多數既有檔案的 import 慣例一致（多數用 import，只有 `StockAlertService` 混用完全限定名）；功能與行為完全等價。另外補上了 `shouldAddLiveRow` 先前缺漏的 javadoc（任務檔 297.1 有要求說明「為什麼」與「不得做什麼」的約束，判斷屬於程式風格規範「javadoc 寫為什麼與約束」的自然延伸，非額外新增行為）。

### 更新過期望值的測試清單

無。本任務未修改任何既有測試的期望值——`shouldAddLiveRow` 由 `private` 變 package-private 且新增參數，但production 內唯一呼叫點已同步更新，其餘 9 個既有 `TradingRadarUsStockEngineTest` 測試與其他測試檔皆未直接呼叫該方法，故無需回改任何既有斷言。
