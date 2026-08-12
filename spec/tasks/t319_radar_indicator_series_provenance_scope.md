# [t319] 收盤 provenance 白名單只界定 verified，不得當技術序列納入判準（台股全數「今日不交易」修正）

**對應 Requirements:** Requirement 65（交易雷達 V13——證據完整度、資料時效與樣本外校準：每一筆規則輸入都須可追溯 value／asOf／source／quality，同一決策快照的資料日期必須一致）
**前置任務:** 無（修正 t290 與 V13 observation resolver 疊加後產生的行為）
**Liquibase changeset:** 無（不動 DB schema，也不得回填任何既有資料）

## 背景

### 現在的錯誤行為

交易雷達的**台股分頁 21 檔全部**輸出「今日不交易」（`action=NO_TRADE`、`shortAction=NO_TRADE`、`dataComplete=false`），風險文案為「個股必要的 MA20／60／240、KD、241 根完成日 K 或大盤資料不足，今日不交易。」。同一次回應中美股 11 檔評分正常（6 WATCH／4 HOLD／1 AVOID），大盤 `dataComplete=true`、`regime=RISK_ON`、`score=100`。

2026-08-12 20:04 對運行中 stack 實測 `GET /api/trading-radar`（在 `asset-business-services` 容器內以 `X-User-Id: 1 / X-User-Role: ADMIN / X-User-Status: ACTIVE` 呼叫 `http://localhost:8080`）：

- 台股每一檔的 `monthlyMa`／`quarterlyMa`／`annualMa`／`kValue`／`dValue`／`weeklyMa`／`volumeRatio`／`week52Position`／`ma60BiasPercent` **全為 `null`**；
- 但同一檔的 `price` 有值、`quoteStatus=VERIFIED_CLOSE`、`asOfDate=2026-08-12`（例：0050 = 105.20）。

亦即**價格拿得到、指標全算不出來**。

### 根因

`RadarObservationResolver.isTrustedClose(row, market)`（`backend/src/main/java/com/steven/assets/service/RadarObservationResolver.java`）對 `"台股"` 要求 `close_source` 命中信任清單，其餘市場一律回 `true`：

```java
private static boolean isTrustedClose(StockPriceHistory row, String market) {
    if (!"台股".equals(market)) return true;
    return row.getCloseSource() != null
            && TRUSTED_TW_CLOSE_SOURCES.contains(row.getCloseSource());
}
// TRUSTED_TW_CLOSE_SOURCES = Set.of("TWSE_MI_INDEX", "TPEX_DAILY_CLOSE", "FINMIND_TW_CLOSE")
```

該過濾產出的 `AcceptedPrice.trustedCompletedRows` 被 `TradingRadarService.prepareTechnicalData()` 直接當成**整條技術序列**餵給 `RadarInputAssembler.assemble(...)`：

```java
List<StockPriceHistory> completedRows = acceptedPrice.trustedCompletedRows();   // ← 已被 provenance 濾過
...
return assembler.assemble(combined, ..., liveAdded, completedRows.size(), acceptedPrice.value());
```

而 Requirement 7／Task 290 引入 `close_source` 時已明文規定「**既有列一律維持 null，禁止 migration 猜來源**」。兩者疊加的結果，實測運行中 DB（`docker exec asset-postgres psql -U assets -d assets`）：

| market | close_source | 列數 |
|---|---|---|
| 台股 | `(null)` | **106,135** |
| 台股 | `TWSE_MI_INDEX` | 68 |
| 台股 | `TPEX_DAILY_CLOSE` | 16 |
| 美股 | `(null)` | 40,533 |
| 美股 | `NASDAQ_REDIS_CLOSE` | 20 |
| 英股 | `(null)` | 8,014 |
| 英股 | `YAHOO_UK_CLOSE` | 6 |
| 英股 | `YAHOO_REDIS_CLOSE` | 1 |

台股非空的 84 列只分佈在 **2026-08-07／08-10／08-11／08-12 四個交易日**（每日 `TWSE_MI_INDEX` 17 ＋ `TPEX_DAILY_CLOSE` 4 = 21 檔）。也就是每檔台股餵進 assembler 的完成日 K 只有 **4 根**——連 MA20（需 20 根）都算不出，更不用說 MA240（`RadarInputAssembler.FULL_WINDOW = 240`）。指標為 null 後 `TradingRadarRuleEngine.evaluateStockInternal` 的 `complete(input)` 失敗，直接落入資料不足分支輸出 `NO_TRADE`。

DB 本身**幾乎不缺資料**。該 21 檔（`0050, 0056, 006208, 00679B, 00697B, 00713, 00719B, 00751B, 00850, 00865B, 00878, 00881, 00882, 00919, 009804, 009816, 009826, 2330, 2881, 2885, 2891`）在 `stock_price_history` 的日 K 根數實測（最舊 2016-08-12）：

```
2330 2438 | 0056 2438 | 2891 2438 | 2885 2438 | 2881 2438 | 0050 2433 | 006208 2352
00679B 2331 | 00697B 2228 | 00713 2161 | 00719B 2071 | 00751B 1911 | 00850 1694
00865B 1626 | 00878 1477 | 00881 1377 | 00882 1338 | 00919 923 | 009804 326
009816 124 | 009826 8
```

即 **19 檔 ≥ 326 根**（足以算 MA240 與其兩日確認），只有 **009816（124 根）與 009826（8 根）** 是真正的資料不足，修好後仍應為 `NO_TRADE`。美股不受影響是因為 `isTrustedClose` 第一行對非台股直接放行；大盤不受影響是因為它走 `twse_index_daily_history`，完全不經這條路徑。

### 正確行為

resolver 裡混用了兩個不同問題的判準，必須拆開：

| 用途 | 正確判準 |
|---|---|
| (a) accepted price 取 `completedSession` 當日那一列（決定 `quoteStatus=VERIFIED_CLOSE`）<br>(b) `hasTrustedCompletedDate(...)` 決定「今日已有可信完成列因此不併 live K」 | 日期 ≤ session ＋ 正價 ＋ **台股須命中信任清單**。這是 Task 290 的 verified 語意，維持不變——來源為 null 的 13:32 誤寫列仍不得算 verified |
| (c) 餵 `RadarInputAssembler` 計算 MA20／60／240、KD、兩日確認、`ma60BiasPercent` 與其分位、52 週位置、60 日報酬 σ 的**歷史序列** | 日期 ≤ session ＋ `closePrice` 為正且有限，**不看 `close_source`**。唯一例外：等於 `completedSession` 的最新一根，台股仍須命中信任清單才可納入 |

(c) 的例外是刻意的：當日尚未官方對帳完成時，那一根可能是盤中誤寫值，不得當成完成收盤 K；該日只能由 live K 併入（`shouldAddLiveRow` 路徑）或缺席。

### 這個例外會撞到一個零餘裕的 off-by-one，必須一併處理

`RadarInputAssembler.assemble()` 把 `completedCloses`（長度 = 傳入的 `completedRowCount`，`liveAdded` 時以 `completedStart = 1` 把 live 那根排除在外，見 `RadarInputAssembler.java:143-149`）交給 `ruleEngine.confirm(completedCloses, FULL_WINDOW)`，而 `TradingRadarRuleEngine.confirm(closesDesc, period)` 要求 `closesDesc.size() >= period + 1`（`TradingRadarRuleEngine.java:748-750`），亦即 **MA240 兩日確認需要 241 根完成收盤**。目前 `TradingRadarService.buildStock` 只抓 `findRecentN(code, market, 241)`（`TradingRadarService.java:750`，query 為 `ORDER BY trading_date DESC LIMIT ?3`）——**剛好 241，零餘裕**。

而序列有**兩個獨立的剔除來源**，都會吃掉這 241 個名額：

1. **`tradingDate > completedSession` 的未來列**。`findRecentN` 的 query 是 `SELECT h ... ORDER BY h.tradingDate DESC LIMIT ?3`（`StockPriceHistoryRepository.java:36-37`），**沒有日期上界**；而盤中（`localTime.isBefore(MarketZones.closeTime(market))`，`RadarObservationResolver.java:258-261`）`completedSession` 是**前一交易日**。此時 DB 若已有當日列，它會佔掉一個 LIMIT 名額，再被既有的 `!row.getTradingDate().isAfter(targetDate)` filter（`:284`）剔除。
2. **319.2 新增的 provenance 例外**（`completedSession` 當日列存在但 `close_source` 為 null）。

任一來源觸發，完成收盤就只剩 240 根，`ma240Confirmation` 回 `UNAVAILABLE`，而 `complete(StockInput)` 硬性要求 `available(input.ma240Confirmation())`（`TradingRadarRuleEngine.java:2105`）→ **仍然是 `NO_TRADE`**。更糟的是這個失敗會偽裝成修好：`monthlyMa`／`quarterlyMa`／`annualMa`／`kValue`／`dValue` 是從含 live 的序列算出來的，全部非 null，只看指標欄位會以為成功。故本任務必須同時把取數上限提高並對序列長度設上限（見 319.4）。

**不得改以回填歷史 `close_source` 解決**——那等於偽造 provenance，與 Requirement 7／Task 290 的「禁止 migration 猜來源」直接衝突。

## 要做什麼

- [ ] 319.1 `RadarObservationResolver.AcceptedPrice` record 把單一 `List<StockPriceHistory> trustedCompletedRows` 拆成**兩個具名欄位**，語意各自明確：
  - `verifiedCompletedRows` — 現行 `trustedCompletedRows(rows, market, completedSession)` 的輸出（含台股 provenance 白名單），供 accepted price 選列與 `hasTrustedCompletedDate` 使用；
  - `indicatorSeriesRows` — 供 `RadarInputAssembler` 的歷史序列使用。
  兩者都維持**依 `tradingDate` 由新到舊排序**（現行 `trustedCompletedRows` 以 `Comparator.comparing(StockPriceHistory::getTradingDate).reversed()` 排序，`prepareTechnicalData` 依賴 `get(0)` 為最新、`get(size-1)` 為最舊，順序不得改變）。`AcceptedPrice.missing(...)` 兩個欄位皆給 `List.of()`。
  **不得**保留「單一 list 同時餵兩種用途」的相容 overload 或 9 參數 constructor——同一判準散在兩處日後必然靜默分岔，這正是本 bug 的成因。同時改寫 `RadarObservationResolver.java:160-163` 的英文註解（現寫「Prior rows remain in trustedCompletedRows for the indicator window」，拆欄位後該敘述會指錯欄位），寫明 verified 與 indicator 兩份的分工。

- [ ] 319.2 `RadarObservationResolver` 新增 indicator 序列的組列邏輯（與既有 `trustedCompletedRows` 併存，不是取代）：納入判準為 `row != null && row.getTradingDate() != null && !row.getTradingDate().isAfter(completedSession) && positiveFinite(row.getClosePrice())`；**額外**排除「`tradingDate` 等於 `completedSession` 且該列未通過 `isTrustedClose(row, market)`」的那一根。排序同 319.1。`trustedCompletedRows(...)` 目前有三處呼叫（`RadarObservationResolver.java:145`、`:203`、`:231`），其中只有 145 行那一支所在的 `resolveAcceptedPrice(..., DecisionSessions)` 會建構 `AcceptedPrice`（`:153`、`:168` 兩處 `new`，另有工廠方法 `missing()` 於 `:83`）——這三處 `new`／工廠都必須填入兩個欄位；另兩支（`:203`、`:231`，都在回傳 `boolean` 的 `shouldAddLiveRow` overload 內）只需 verified 那一份。`hasTrustedCompletedDate(...)` 的三處呼叫（`:152`、`:207`、`:235`）**一律繼續使用 verified 那一份**，不得改讀 indicator 那份——否則今日一有未驗證列就會擋掉 live K 併入。

- [ ] 319.3 `TradingRadarService.prepareTechnicalData()`（`backend/src/main/java/com/steven/assets/service/TradingRadarService.java:1157-1182`）把 `List<StockPriceHistory> completedRows = acceptedPrice.trustedCompletedRows();` 改讀 `acceptedPrice.indicatorSeriesRows()`。後續 `combined`、`fromDate`／`toDate`、傳給 `assembler.assemble(...)` 的 `completedRows.size()` 全部沿用同一份 indicator 序列，不得混用兩份。同步改寫 `TradingRadarService.java:762-764` 的註解（現寫「accepted price 與技術序列必須同源：拒絕 future／不可信 closeSource 列」——`不可信 closeSource` 這半句正是本任務推翻的部分）。

- [ ] 319.4 解掉上述 off-by-one：`buildStock` 的取數改為 `priceHistoryRepo.findRecentN(target.code(), target.market(), 250)`，**且 indicator 序列組完後截斷為最多 241 列**。兩者必須同時做，缺一不可：
  - **契約是「序列上限 241」，250 只是取數緩衝，不是精算出來的餘裕。** 不要把它改回「241 + 預期剔除幾根」——本 bug 第一版修法就是這樣算的，算漏了未來列那一路（見背景段的兩個剔除來源），日後任何人重算都會再算錯一次。留 9 根緩衝的成本是每檔多讀 9 列，可忽略。
  - 只放大取數而不截斷，會讓 `ma60BiasPercentile`（`RadarInputAssembler.java:362-379`，觀測數為 `n - MA60_WINDOW`，隨整份 `adjustedRows` 長度增加）在**沒有任何剔除發生**的常態情況下也改變分母，靜默改掉既有分位值。截斷後常態行為與現行 **bit-identical**（250 抓回 → 截斷 241 → 與現行 241 相同），多出來的列只在實際發生剔除時才遞補。
  - 截斷上限取 241 是因為 `confirm(closes, 240)` 需要 241 根完成收盤（`size >= period + 1`）。
  `RadarInputAssembler` 的 `FULL_WINDOW`、內部區域變數 `indicatorRows = min(size, liveAdded ? 241 : 240)`（`RadarInputAssembler.java:151`，與 319.1 新增的 record 欄位同名但語意不同：前者是 int 上限、後者是列的清單）與任何門檻**一律不得修改**；截斷後兩者不衝突也不產生死碼（`liveAdded` 時 `adjustedRows.size()=242`、`min(242,241)=241`，與現行一致）。

- [ ] 319.5 同步所有寫死 `241` 取數的既有測試與註解，否則改完必定紅燈或留下錯誤敘述：
  - `backend/src/test/java/com/steven/assets/service/TradingRadarUsStockEngineTest.java:261` 的 `when(priceHistoryRepo.findRecentN("AAPL", "美股", 241)).thenReturn(stockRows);` 須改為新的取數值。該類是 `@ExtendWith(MockitoExtension.class)`（`:56`）＝ STRICT_STUBS，參數不符會拋 `UnnecessaryStubbingException`；更麻煩的是 `:187` 有 `lenient().when(priceHistoryRepo.findRecentN(anyString(), anyString(), anyInt())).thenReturn(List.of())` 會頂上，使 AAPL 變成零歷史列、相關斷言以錯誤原因失敗。
  - `backend/src/main/java/com/steven/assets/service/BacktestService.java:697` 註解「與 production 的 `findRecentN(…, 241)` 同形狀」、`backend/src/test/java/com/steven/assets/service/BacktestServiceTest.java:589` 的 `@DisplayName("(g) 回測在 t=最新日切出的視窗，與 production 的 findRecentN(241) 逐筆相同")` 與 `:594` 註解，一律改述為「production 取 N 筆後截斷為 241」。回測本身切 241 筆視窗的行為**不變**（production 截斷後也是 241），只是敘述要跟上。
  - `backend/src/test/java/com/steven/assets/service/TechnicalIndicatorSeriesAlignmentTest.java:76` 用 `anyInt()`，不受影響、不需改。

- [ ] 319.6 `BacktestService`（`backend/src/main/java/com/steven/assets/service/BacktestService.java:1144-1146`）直接以 `new RadarObservationResolver.AcceptedPrice(price, signalDate, null, "BACKTEST_COMPLETED_CLOSE", Quality.COMPLETED_CLOSE, false, null, windowDesc, null)` 建構，須同步改為顯式傳入兩份——回測的 `windowDesc` 本來就是完整還原視窗、不具 provenance 概念，兩個欄位皆傳 `windowDesc` 語意正確。漏改會使 production 與回測分岔。

- [ ] 319.7 同步其餘 `AcceptedPrice` 建構點與 accessor 呼叫端。record 換掉第 8 個 component 並加到 10 個之後，以下**全部會編譯失敗**（建構點以 `grep -ran "new RadarObservationResolver.AcceptedPrice(\|new AcceptedPrice("`、accessor 以 `grep -ran "trustedCompletedRows"` **分兩次**確認——只用建構點那個 pattern 會漏掉 accessor；普通 `grep -r` 又會靜默跳過被 `file(1)` 判為 data 的 `.java`）：
  - 建構點，兩個欄位都要填：`RadarObservationResolver.java:83`（`missing()` 工廠，兩者皆 `List.of()`）、`:153`、`:168`；`BacktestService.java:1144`（見 319.6）；`backend/src/test/java/com/steven/assets/service/TradingRadarEvidenceConfidenceResolverTest.java` 共 **8 處**（59、82、112、140、168、275、311、475 行，皆為 `List.of()` 佔位，兩欄都填 `List.of()`）。
  - accessor 呼叫端，**一律改讀 `verifiedCompletedRows()`，不得改讀 indicator 那份**：`backend/src/test/java/com/steven/assets/service/RadarObservationResolverTest.java:35`、`:60`、`:61`。這三處正是在驗證白名單語意（`:35` 斷言只取到命中信任清單的那一筆、`:60`／`:61` 斷言取到 2026-08-06 那列）；改讀 indicator 會把 319.8(iii) 想保住的回歸保護自己拆掉。
  - 純消費端 `TradingRadarEvidenceConfidenceResolver`（只讀 `value`／`tradingDate`／`quality`，見 `:107`／`:143`／`:170`／`:197`／`:464`／`:479`／`:1042`／`:1062`）不需改動。`TradingRadarMarketContextService` 與 `TradingRadarMarketFeatureResolver` 完全未參照 `AcceptedPrice`，不在範圍內。
  - 改完仍須用上述兩個 pattern 各重新 grep 一次，確認沒有遺漏。

- [ ] 319.8 測試（與實作同一支任務，不得延後）：
  - `backend/src/test/java/com/steven/assets/service/RadarObservationResolverTest.java`（既有檔）新增案例：(i) 台股歷史列 `close_source` 全為 null、只有最近一個交易日有 `TWSE_MI_INDEX` 時，`indicatorRows` 須包含全部歷史列，而 `verifiedCompletedRows` 只有那一列；(ii) 台股 `completedSession` 當日列 `close_source` 為 null 時，該列**不得**出現在 `indicatorRows`，但更早的 null 來源歷史列仍須納入；(iii) `verifiedCompletedRows` 的內容與現行 `trustedCompletedRows` 逐列相同（回歸保護，確保 verified 判準沒被順手放寬）；(iv) 美股（非台股）兩個欄位內容相同。
  - 新增一支服務層迴歸測試（放在 `backend/src/test/java/com/steven/assets/service/`，類名由實作者定，須明確表達「indicator 序列不受 close_source provenance 影響」之意）：以「241 根台股歷史列、其中僅最近 4 個交易日帶 `TWSE_MI_INDEX`、其餘 `close_source=null`」的 fixture 走 `TradingRadarService` 個股組裝路徑，斷言結果的 `monthlyMa`／`quarterlyMa`／`annualMa`／`kValue`／`dValue` 皆非 null，且資料不足分支未被觸發（`risks` 不含「個股必要的 MA20／60／240、KD、241 根完成日 K 或大盤資料不足，今日不交易。」）。實作完成後須把該類名回填到本檔的「完成報告」段。
  - **off-by-one 專屬案例（漏了這條，319.4 等於沒驗）**：兩個剔除來源各一個案例，都斷言 `ma240Confirmation != UNAVAILABLE` 且結果不落入資料不足分支——
    (a) `completedSession` 當日 DB 列存在但 `close_source=null`（provenance 剔除）；
    (b) 盤中 decisionInstant（`completedSession` 為前一交易日）＋ DB 已有當日列（未來列剔除），且前一交易日的列 `close_source=null`，兩個剔除來源同時發生。
    兩案例都**必須同時提供 `tradingDate` 等於當日、價格為正的 Redis live**：verified 那份沒有當日完成列時，`resolveAcceptedPrice` 的完成列查找（`RadarObservationResolver.java:164-166`）會落空並回 `AcceptedPrice.missing(...)`（`:174-179`），而 `missing()`（`:83`）兩個欄位都是 `List.of()` → `prepareTechnicalData` 拿到空序列 → `Assembled.EMPTY` → 指標全 null，測試會以**與 off-by-one 無關**的原因失敗。production 的 off-by-one 也只在 live 被併入（`completedStart=1`）時才顯形，含 live 才是真情境。
    此情境下五個指標欄位本來就非 null，**只斷言指標非 null 會漏掉這個 bug**，必須直接斷言確認狀態與最終 `action`。
  - 通知路徑與頁面路徑共用 `TradingRadarService.buildStock`（`evaluateForNotification` → `buildStock`），故不需另建通知測試，但實作**不得**只在頁面路徑上修補。

- [ ] 319.9 不得做的事：不得回填或修改 `stock_price_history.close_source` 任何既有列；不得放寬 `PriceQueryService.isTrustedClose`／display gate／`quoteStatus` 的 verified 判準（Requirement 7／Task 290 的可見價語意完全不變）；不得為此修改 `RadarInputAssembler` 的 `FULL_WINDOW`、最小樣本數或任何門檻；不得調整 `TradingRadarRuleEngine.complete(...)` 讓資料不足也能出建議。

## 驗證

本任務的工作目錄是 worktree `/Users/steven/Project/asset-management/.claude/worktrees/trading-radar-no-trades-a47b9f`。**下列每一條指令的路徑都不可改成 `/Users/steven/Project/asset-management`**——那是主 clone，實測目前 HEAD 停在別的分支（`codex/public-market-index-api-v2`），在那裡 build 出來的 jar 不含本次修正，接著跑 API 驗證會看到台股仍全是 `NO_TRADE`，而誤判修正無效。

```bash
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f /Users/steven/Project/asset-management/.claude/worktrees/trading-radar-no-trades-a47b9f/backend/pom.xml test -Dtest=RadarObservationResolverTest -DextraArgLine=-Dnet.bytebuddy.experimental=true
```

跑完整回歸（新增的服務層測試會一併執行；不得只跑單一測試類就收工）：

```bash
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f /Users/steven/Project/asset-management/.claude/worktrees/trading-radar-no-trades-a47b9f/backend/pom.xml test -DextraArgLine=-Dnet.bytebuddy.experimental=true
```

worktree 只有 `.env.example`、沒有 `.env`，而 compose 的 `env_file` 是相對 compose 檔解析、`--env-file` 救不了，故 build 前必須先複製一份：

```bash
cp /Users/steven/Project/asset-management/.env /Users/steven/Project/asset-management/.claude/worktrees/trading-radar-no-trades-a47b9f/.env
```

改 JVM service 後必須 `--no-cache` 重 build（cached build 會產出不含本次變更的 stale jar），且全機共用同一套 `asset-management-*:latest`，`-p asset-management` 不可省（省略會建到以目錄名為前綴、沒人使用的 tag，且照樣印 Built、exit 0）：

```bash
cd /Users/steven/Project/asset-management/.claude/worktrees/trading-radar-no-trades-a47b9f && docker compose -p asset-management build --no-cache business-services
```

```bash
cd /Users/steven/Project/asset-management/.claude/worktrees/trading-radar-no-trades-a47b9f && docker compose -p asset-management up -d --no-deps --force-recreate business-services
```

recreate 會換 IP，BFF 會握著舊 IP 回 500 且 ≥3 分鐘不自癒（Docker DNS TTL 600s），必須一併重啟：

```bash
cd /Users/steven/Project/asset-management/.claude/worktrees/trading-radar-no-trades-a47b9f && docker compose -p asset-management restart bff
```

> 本次變更 merge 進 main 之後，須依既有慣例**從 main 的 worktree（`/Users/steven/Project/asset-management-main`）重建一次**，否則會被其他並行 worktree 的 build 洗掉。

```bash
docker exec asset-business-services sh -lc 'curl -s -H "X-User-Id: 1" -H "X-User-Role: ADMIN" -H "X-User-Status: ACTIVE" http://localhost:8080/api/trading-radar' | python3 -c "import json,sys; from collections import Counter; SHORT={'009816','009826'}; d=json.load(sys.stdin); tw=[s for s in d['stocks'] if s['market']=='台股']; bad=[s['stockCode'] for s in tw if s['stockCode'] not in SHORT and (s['annualMa'] is None or s['kValue'] is None)]; print('台股檔數', len(tw)); print('action 分布', Counter(s['action'] for s in tw)); print('歷史足夠卻仍缺指標的檔', bad); print('仍 NO_TRADE 的檔', [s['stockCode'] for s in tw if s['action']=='NO_TRADE'])"
```

驗收標準：`歷史足夠卻仍缺指標的檔` 為**空 list**；`仍 NO_TRADE 的檔` 最多只剩 `009816`（124 根）與 `009826`（8 根）——這兩檔的歷史根數本來就不足 240，是**正確**的資料不足，不在本任務修正範圍。其餘 19 檔（含只有 326 根的 009804）都必須有非 null 的 MA240／KD 並得到實質建議。

**只看指標欄位不足以驗收**：本任務背景段落所述的 off-by-one 會讓五個指標欄位全部非 null 卻仍輸出 `NO_TRADE`，故 `仍 NO_TRADE 的檔` 這一項必須一起看。同時確認美股 11 檔的 `action` 與修改前逐檔相同（本變更對非台股應為 no-op），修改前實測為 6 `WATCH`／4 `HOLD`／1 `AVOID`。

```bash
docker exec asset-postgres psql -U assets -d assets -c "select coalesce(close_source,'(null)') src, count(*) from stock_price_history where market='台股' and trading_date <= '2026-08-12' group by 1 order by 2 desc;"
```

驗收標準：`(null)` 須仍為 **106,135**、`TWSE_MI_INDEX` **68**、`TPEX_DAILY_CLOSE` **16**——證明沒有回填任何既有列。查詢**必須**帶 `trading_date <= '2026-08-12'` 切齊：`stock_price_history` 是持續寫入的表（`asset-external-materials-service` 每個台股交易日盤後對帳會新增 21 列 verified），不切日期的話只要跨到隔天就必然對不上，那不是回填。

## 完成報告

（實作者做完後回填：實際改了哪些檔、驗證輸出、與原計畫的偏差及原因。）
