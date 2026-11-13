# [t577] 交易雷達以完成 K 結構確認補強高賣低買（TW_RULES_V25）

**對應 Requirements:** Requirement 202（減碼與試單除 KD 證據外，另須各持有期完成 K 價格結構確認；支撐破位只揭露、不在低檔賣）
**前置任務:** t575（V23 高賣低買）、t576（V24 完成日 KD）、t504（SRPP Swagger 身分受控發布鏈）
**Liquibase changeset:** 無

## 背景

2026-11-13 另一個 session 在 `/Users/steven/Project/asset-management-main/.claude/worktrees/radar-v23-t575`（branch `claude/radar-v23-t575`，基於舊 base `33deca49`、未提交）做了另一套「位置＋完K轉折」設計 `RadarTurningPointPolicy`。使用者要求以 Codex（gpt-6.1-sol／high）判定，合理就照改、不合理依現行。判定為 `ADOPT_PARTIAL`：

- **不移植：** 低檔支撐跌破直接 `EXIT_CANDIDATE`（違反使用者「高點才賣、不在低點賣」與 Requirement 200）；以「近期曾高檔且目前區間位置 P>20」取代當下高檔條件（例：區間 80～120、收盤 96 即可減碼）；以「近期曾低檔且 P<80」取代當下跌深條件；把舊理由依字串比對搬到 risks；舊 28 參數建構式（會重新引入 V24 已修的盤中混用）；回測版號寫死 V22。
- **移植：** 純函數完成 K 結構判定、各軌獨立尺度、高檔轉弱（高點降低＋連兩根跌破各自確認均線）與跌深止穩（低點墊高＋突破前三根收盤＋站上確認均線）作為**額外**確認、支撐反覆測試後確認跌破只作風險揭露、線上與回測共用同一評估器、揭露可核對的日期與數值。

本任務在 V24 之上**只加嚴、不放寬**：減碼與試單都會更少、更晚。門檻為判斷性初值，未經回測校準，不宣稱提升獲利。

## 要做什麼

- [x] 577.1 新增 `backend/src/main/java/com/steven/assets/service/RadarCompletedStructure.java`（`final`、純靜態、無時鐘／IO／Spring／DB）。對外方法：
  `static TradingRadarRuleEngine.StructureFacts evaluate(List<StockPriceHistory> completedDailyDesc, List<WeeklyBarAggregator.WeeklyBar> completedWeeklyDescUnfiltered, Set<LocalDate> droppedDailyDates, boolean droppedUndatedDailyRow)`（唯一入口，不提供省略診斷的多載；production 與回測都必須傳入實際診斷，無剔除時傳空集合與 false）
  - 日 K 輸入：只含完成日（不含盤中列），依交易日嚴格降序。週 K 輸入：只含完成週，**未經** `weeklyIndicatorBars` 的缺 high／low 過濾。
  - 三軌尺度（具名常數，Javadoc 註明「判斷性初值，未經回測」）：SHORT＝日 K、確認 SMA 期數 5、支撐回看 L=5；SWING＝日 K、確認 SMA 10、L=20；MEDIUM＝週 K、確認 SMA 4、L=10。
  - 每軌所需根數 `required = max(confirm + 1, 4, L + 2)`。結構只用 high／low／close（open 不參與、不驗）。所需視窗（索引 0..required-1）內任一列：null、日期 null、日期非嚴格降序、close／high／low 為 null 或 ≤0、`low > close` 或 `high < close` → 該軌 `UNAVAILABLE`（`unavailableReason` 具名，例如 `INSUFFICIENT_BARS`、`INVALID_HLC`、`NON_DESC_DATES`、`UPSTREAM_DROPPED_ROW`、`CALENDAR_GAP`）；不得跳過壞列補齊。
  - 方法另收 `Set<LocalDate> droppedDailyDates`、`boolean droppedUndatedDailyRow`（上游剔除診斷，見 577.4a）：日 K 軌視窗日期區間 `[date(required-1), date(0)]` 內含任一剔除日期，或 `droppedUndatedDailyRow=true` → `UPSTREAM_DROPPED_ROW`；MEDIUM（週 K）軌：以所用 required 根完成週中**最舊週的週一**到**最新週的週日**為完整日期區間（含其間已消失、沒有週 K 的中間週），任一剔除日期落在此區間內，或 `droppedUndatedDailyRow=true` → `UPSTREAM_DROPPED_ROW`（同週僅剔除一天、或整週被剔除而相鄰週恰好相隔 14 天，都須阻擋）；長假容忍只適用於「沒有剔除列」的自然缺週。相鄰兩根所用 K 的日期差：日 K >10 個日曆日、週 K >14 個日曆日 → `CALENDAR_GAP`（判斷性取值，可涵蓋農曆年長假，具名常數）。
  - `SMA(i, m)` = 第 i 根起（含）往舊 m 根收盤平均；`weakened` = `close0 < close1` 且 `close0 < SMA(0,m)` 且 `close1 < SMA(1,m)` 且 `high0 < high1`。
  - `stabilized` = `low0 > low1` 且 `low1 ≥ low2` 且 `close0 > max(close1, close2, close3)` 且 `close0 > SMA(0,m)`。
  - `supportBroken`：`support = min(low[2..L+1])`；存在 `recent ∈ [2, L+1]`、`older ∈ [recent+3, L+1]` 使兩者 `low ≤ support×1.01`，且其間存在 `between` 使 `close ≥ support×1.03`；且 `close0 < support×0.99`、`close1 < support×0.99`、`close0 < close1`。
  - 比較一律用 `BigDecimal`（`MathContext.DECIMAL128`），不得轉 double。
- [x] 577.2 `TradingRadarRuleEngine` 新增巢狀不可變型別：
  - `public enum StructureStatus { AVAILABLE, UNAVAILABLE, NOT_EVALUATED }`
  - `public record HorizonStructure(StructureStatus status, boolean weakened, boolean stabilized, boolean supportBroken, BigDecimal support, LocalDate asOf, int confirmationPeriod, BigDecimal confirmationSma, String unavailableReason)`，提供 `static HorizonStructure notEvaluated()`、`static HorizonStructure unavailable(String reason, int confirmationPeriod)`。
  - `public record StructureFacts(HorizonStructure shortTerm, HorizonStructure swing, HorizonStructure medium)`，`HorizonStructure of(Horizon h)`，常數 `NOT_EVALUATED`（三軌皆 notEvaluated）。
- [x] 577.3 `StockInput` 正典建構式尾端新增第 30 元件 `StructureFacts structure`。**所有**既有相容建構式（含 V24 新增的 29 參數形狀與舊 28／27／25／23／20 參數形狀）保持原參數並傳 `StructureFacts.NOT_EVALUATED`；Javadoc 明載「NOT_EVALUATED 只供既有單元測試，代表結構條件不參與，維持 V24 行為；production 與回測必須顯式傳入實算結果」。`structure` 為 null 時視同 `NOT_EVALUATED`。
- [x] 577.4 `RadarInputAssembler`：
  - `Assembled` 新增最後元件 `TradingRadarRuleEngine.StructureFacts structure`（同步 `EMPTY` 與相容建構式；`EMPTY` 為三軌 `UNAVAILABLE`）。
  - `calculate(...)` 以 `prepared.dailyContractRowsDesc().subList(firstCompletedIndex, size)` 與 `prepared.weeklyBarsDesc()`（未過濾前的完成週；若現行 `weeklyBarsDesc` 已是過濾後的列表，則改在 `prepare` 保留過濾前清單並新增 `Prepared` 元件傳遞）呼叫 `RadarCompletedStructure.evaluate`。快取（cachedIndicators）路徑同樣本地計算。
- [x] 577.4a 上游剔除診斷：`RadarObservationResolver`（約 374–379 行，過濾 null 日期／非正收盤並排序之處）與 `BacktestService`（約 715–717 行剔除無效收盤之處）在剔除列時記錄其交易日（日期為 null 則設旗標），經 `TradingRadarService.prepareTechnicalBasis` → `RadarInputAssembler.prepare` 存入 `Prepared` 新元件（同步 `Prepared.EMPTY` 與所有建構點），供 577.4 呼叫 `RadarCompletedStructure.evaluate` 時傳入。只記錄、不改既有過濾行為與其他指標計算。
- [x] 577.5 `TradingRadarService` 的 `StockInput` 唯一建構點改用 30 參數正典建構式，顯式傳入 `technical.completedKd()` 與 `technical.structure()`。`BacktestService` 兩個建構點以同一 `RadarCompletedStructure.evaluate` 由其完成日 bars（與以同一 `WeeklyBarAggregator` 聚合、未過濾的完成週）求得後顯式傳入；其版本標示一律讀 `TradingRadarRuleEngine.RULE_VERSION`，不得寫死舊版號。以 `grep -ran "new TradingRadarRuleEngine.StockInput(\|new StockInput(" backend/src/main/java` 確認 production 全走 30 參數。
- [x] 577.6 `TradingRadarRuleEngine.actionFor(...)` 新增 `Horizon horizon` 參數（由 `evaluateHorizon` 傳入；candidate／V13 路徑一併傳入其所屬軌），取 `HorizonStructure hs = structureOf(input, horizon)`：
  - **試單（兩條）**：`qualifiesForTrialBuy` 或 `qualifiesForReboundBuy` 成立後，若 `hs.status()==AVAILABLE && !hs.stabilized()` 或 `hs.status()==UNAVAILABLE` → 不輸出 `TRIAL_BUY`，reasons 加 `V25結構確認：<軌>完成K尚未止穩（需低點墊高、收盤突破前三根、站上 SMA<m>），暫不試單。 `（UNAVAILABLE 時改說明原因），並**立即回傳**持有者 `HOLD_CAUTION`、未持有者 `WAIT`——同次評估不得再走到 `buyGate` 變成 `BUY_CANDIDATE`／`ADD_CANDIDATE`，也不得變成賣出。原本未命中兩條試單的流程不受影響。`NOT_EVALUATED` → 維持 V24 行為。
  - **獲利了結分支**：原條件 `profitTaking && uptrendEndConfirmed` 再加「`hs` 為 `NOT_EVALUATED`，或 `AVAILABLE` 且 `weakened`」才回減碼／AVOID；原條件成立但只因結構受阻時，加說明並**立即回傳**持有者 `HOLD_CAUTION`、未持有者 `WAIT`（不落入分數映射，避免高分時回 HOLD／WATCH）。原條件本身不成立時沿用 V24 行為。
  - **分數映射賣出閘門**：原 `highZone && uptrendEndConfirmed` 再加同上 weakened 條件；不成立 → `HOLD_CAUTION`，reasons 說明缺「完成K結構轉弱」或 UNAVAILABLE 原因。
  - **支撐破位**：`hs.status()==AVAILABLE && hs.supportBroken()` → 該軌 risks 加 `V25結構確認：<軌>完成K已確認跌破反覆測試的支撐 <support>，屬破位風險；本雷達依高賣低買原則不在低檔給減碼，是否停損請自行評估。 `；不改動作。
  - **揭露（統一）**：AVAILABLE 時該軌 reasons 加一條 `V25結構確認：<軌>（<日K|週K>，as-of <asOf>，SMA<m>=<值>）轉弱=<是/否>、止穩=<是/否>。 `；UNAVAILABLE 原因與支撐破位風險一律放該軌 risks。測試依此斷言。
  - 不改：分數、權重、門檻、`timingOf()`、`kdHeatOf()`、`profitTakingConfirmed()` 計算、`buyGate`、evidence gate、SHORT 盤中閘門、官方 SMA20 閘門、DTO 結構；不得以字串比對改寫既有理由。
- [x] 577.7 版號：`RULE_VERSION`="TW_RULES_V25"（類別 Javadoc 補 V25 一行）；`FubonRadarCompatibilityManifest.DECISION_INPUT_VERSION`="TW_RULES_V25|"+...；`TradingRadarService` 註解、DTO Javadoc 同步；`docs/openapi/docker-external-api.yaml` 所有 `TW_RULES_V24`→`TW_RULES_V25`、`info.version` 1.27.2→1.27.3；`ruby scripts/render-9090-openapi-docs.rb`（無 `--srpp-root`）重產並 `--check`；兩支 ruby 測試的 1.27.2→1.27.3；後端／BFF 測試與 fixtures 的 V24→V25（以 `grep -ran TW_RULES_V24 backend/src bff/src frontend/src docs/openapi scripts/tests` 清零；`PREVIOUS_RULE_VERSION` 不動）。
- [x] 577.8 測試：
  - 新增針對 `RadarCompletedStructure` 的單元測試類（檔名依慣例以類名加 Test 結尾）：三軌尺度各一組 weakened／stabilized／supportBroken 正反例（嚴格不等式邊界）、根數不足、日期重複／亂序、HLC 非正、`low>close`、週 K 缺 high／low（以未過濾序列傳入）→ UNAVAILABLE；HLC 合法但 open 為 null 或非正時結果不受影響；剔除日期落在 MEDIUM 所用週（同週只剔除一天、其餘日列聚合 HLC 合法）→ MEDIUM `UPSTREAM_DROPPED_ROW`；整週日列全被剔除、相鄰完成週恰好相隔 14 天 → MEDIUM `UPSTREAM_DROPPED_ROW`；無剔除列的 14 天自然缺週 → 仍可 AVAILABLE；確認不讀第 required 根以後資料（截斷後續列結果不變）。
  - `TradingRadarRuleEngineTest` 新增：(1) V24 會減碼的高檔轉弱樣本，該軌 `weakened=false` → `HOLD_CAUTION`；`weakened=true` → 仍減碼；(2) 獲利了結分支同理，且含**分數 ≥ hold 門檻**的高分樣本：結構受阻時持有者仍為 `HOLD_CAUTION`、未持有者 `WAIT`；(3) V24 會試單的跌深反彈樣本，`stabilized=false` 或 UNAVAILABLE → 持有者 `HOLD_CAUTION`／未持有者 `WAIT`，且斷言**不是** `TRIAL_BUY`、`BUY_CANDIDATE`、`ADD_CANDIDATE`；`stabilized=true` → `TRIAL_BUY`；(4) 長線試單同理，並用審查反例（價格 120、MA20／60／240＝115／110／100 皆 ABOVE、盤中 KD 60／40、完成日 KD 15／14 前期 12／16、完成日 +1%、RISK_ON、新鮮大盤）驗證結構受阻時不升級為一般買進／加碼；(5) `supportBroken` 只加 risks、動作不變（含低檔持有者仍非減碼）；(6) 三軌可因結構不同而得不同動作；(7) `NOT_EVALUATED` 時 V24 既有測試全部不變；(8) UNAVAILABLE 永不產生賣出或試單。
  - `RadarInputAssemblerWindowRegressionTest`（或既有 assembler 測試類）新增：盤中列不進入結構計算；被上游過濾的缺高低週能使 MEDIUM 變 UNAVAILABLE；經 resolver → assembler 路徑，視窗內有非正收盤列被剔除 → 日 K 軌 `UPSTREAM_DROPPED_ROW`；相鄰日 K 間距 11 天 → `CALENDAR_GAP`。`BacktestServiceTest` 新增：回測組裝路徑剔除無效收盤後，結構同樣為 `UPSTREAM_DROPPED_ROW`（日 K 軌與該週所屬的 MEDIUM 軌皆是）。resolver → assembler 路徑另測同週只剔除一天、以及整週剔除且間距恰好 14 天時，MEDIUM 皆為 `UPSTREAM_DROPPED_ROW`（回測路徑同測整週剔除）。
  - 版號 V25、通知升版首輪不寄信。
- [x] 577.9 不新增 API／路由／DB／排程／券商 I/O；只呈現建議、不下單。不合併或修改 `claude/radar-v23-t575` 分支與其 worktree。
- [ ] 577.10 發布：與 SRPP 本輪變更一起依 `/srpp-policy-release` 流程（Task 504 受控鏈：renderer `--srpp-root`→sync→兩個 `--check`→SRPP policy／runtime check→Ruby 無參數產 `d201-registration.sql`→重產 bundle 綁定 golden（只允許 `policyBundleSha256` 差異）→authority gate 與回歸→雙 repo commit／no-ff merge／push 與 ls-remote 讀回→insert-only registry→從乾淨 main 重建 business-services→9090 實測）。

## 驗證

```bash
bash scripts/spec-check.sh
grep -ran "TW_RULES_V24" backend/src bff/src frontend/src docs/openapi scripts/tests   # 應無輸出
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -DextraArgLine=-Dnet.bytebuddy.experimental=true -Dtest='*Radar*,*Backtest*,*RuleParameters*,*OpenApi*,*TreasuryYield*,*Assembler*,*Structure*' test
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f bff/pom.xml -DextraArgLine=-Dnet.bytebuddy.experimental=true -Dtest='*TradingRadar*' test
ruby scripts/render-9090-openapi-docs.rb --check
ruby scripts/tests/docker-external-api-openapi-test.rb
ruby scripts/tests/unified-9090-openapi-renderer-test.rb
```

重建後 `GET /api/public/trading-radar/today` 的 `ruleVersion` 為 `TW_RULES_V25`，記錄前後動作分布（預期試單與減碼只會減少、不會增加）。

## 完成報告

**狀態：577.1～577.9 完成；577.10（發布）未執行。**未 commit／merge／push，未做 Docker／DB 操作。

### 變更檔

- 新增 `backend/src/main/java/com/steven/assets/service/RadarCompletedStructure.java`：`final` 純靜態，唯一入口 `evaluate(completedDailyDesc, completedWeeklyDescUnfiltered, droppedDailyDates, droppedUndatedDailyRow)`；三軌尺度與間距／支撐比例為具名常數（Javadoc 註明判斷性初值、未經回測）；只用 high／low／close、`BigDecimal`＋`DECIMAL128`；轉換階段即限制日K 最多讀 22 根（SHORT／SWING 所需較大者）、週K 最多 12 根，判定只讀索引 `0..required-1`。具名原因：`INSUFFICIENT_BARS`、`MISSING_ROW`、`MISSING_DATE`、`NON_DESC_DATES`、`INVALID_HLC`、`UPSTREAM_DROPPED_ROW`、`CALENDAR_GAP`、`NO_PRICE_SERIES`（`Assembled.EMPTY` 用）。
- `TradingRadarRuleEngine.java`：新增 `StructureStatus`／`HorizonStructure`／`StructureFacts`；`StockInput` 第 30 元件 `structure`（null→`NOT_EVALUATED`），原 29 參數形狀改為相容建構式傳 `NOT_EVALUATED`，28／27／25／23／20 參數形狀經串接同樣為 `NOT_EVALUATED`；`actionFor` 加 `Horizon` 參數，實作兩條試單、獲利了結分支、分數映射賣出閘門的結構確認與統一揭露（AVAILABLE→reasons、UNAVAILABLE／支撐破位→risks）；`RULE_VERSION`＝`TW_RULES_V25`、類別 Javadoc 補 V25。
- `RadarObservationResolver.java`：`AcceptedPrice` 新增 `droppedSeriesDates`、`droppedUndatedSeriesRow`（保留 11／10 參數相容建構式→空集合／false）；只記錄 `eligibleSeriesRows` 剔除的非正／非有限收盤列日期（日期不晚於 completed session）與 null 日期（或 null 列）旗標，過濾本身不變。
- `RadarInputAssembler.java`：`Prepared` 新增 `completedWeeklyBarsUnfilteredDesc`、`droppedDailyDates`、`droppedUndatedDailyRow`（含 `EMPTY`）；`Assembled` 新增 `structure`（舊 26 參數形狀保留為相容建構式→`NOT_EVALUATED`；`EMPTY` 為三軌 `UNAVAILABLE/NO_PRICE_SERIES`）；`prepare`／`preparePriceOnly`／`assemble` 新增帶診斷的多載（舊多載傳空集合／false）；`calculate` 以 `dailyContractRowsDesc().subList(firstCompletedIndex, size)` 與未過濾完成週本地計算結構（快取路徑同）。
- `TradingRadarService.java`：`prepareTechnicalBasis` 兩條（還原／price-only）傳入 resolver 診斷；唯一 `StockInput` 建構點走 30 參數並顯式傳 `technical.completedKd()`、`technical.structure()`；版號註解同步。
- `BacktestService.java`：`runOne` 與 `appendV13Code` 兩處剔除無效收盤時記錄日期／旗標並經 `assemble(..., dropped, undated)` 傳入；兩個 `StockInput` 建構點顯式傳 `completedKd(a)` 與 `a.structure()`；版本仍讀 `RULE_VERSION`（無寫死版號）。
- `FubonRadarCompatibilityManifest.DECISION_INPUT_VERSION`＝`TW_RULES_V25|FUBON_OVERLAY_V1`；`TradingRadarDto` Javadoc 同步。
- `docs/openapi/docker-external-api.yaml`：`TW_RULES_V24`→`TW_RULES_V25`、`info.version` 1.27.2→1.27.3；以 `ruby scripts/render-9090-openapi-docs.rb`（無 `--srpp-root`）重產 `docs/openapi/9090-api-swagger.md` 與 `backend/src/main/resources/srpp/9090-api-swagger.md`。
- 測試：新增 `RadarCompletedStructureTest`（17 案，含 get 越界即拋錯的守衛輸入證明不讀所需根數以後）；`TradingRadarStockWeeklyWiringTest` 新增 3 案（經正式 `TradingRadarService` 路徑捕捉 `StockInput`：一般計算與 LOCAL 快取命中兩種皆為實算三軌 AVAILABLE 結構＋完成日 KD；resolver 剔除非正收盤列使三軌 `UPSTREAM_DROPPED_ROW`）；`TradingRadarRuleEngineTest` 新增 9 案（`v25_*`）；`RadarInputAssemblerWindowRegressionTest` 新增 6 案；`BacktestServiceTest` 新增 2 案；版號字串同步於下列測試。

### 測試摘要（實際執行結果）

- `bash scripts/spec-check.sh`：BLOCK 0、CHECK 0。
- `grep -ran "TW_RULES_V24" backend/src bff/src frontend/src docs/openapi scripts/tests`：無輸出。
- backend `-Dtest='*Radar*,*Backtest*,*RuleParameters*,*OpenApi*,*TreasuryYield*,*Assembler*,*Structure*'`：exit 0，751 tests、0 failure／error（Codex 第 1 輪程式審查修正後重跑）。
- bff `-Dtest='*TradingRadar*'`：exit 0，151 tests、0 failure／error。
- `ruby scripts/render-9090-openapi-docs.rb --check`：PASS；`ruby scripts/tests/docker-external-api-openapi-test.rb`：PASS；`ruby scripts/tests/unified-9090-openapi-renderer-test.rb`：5 runs、0 failures。
- 額外：backend `-Dtest='*Swagger*,*Srpp*'` exit 0（Swagger 重產未弄壞本 repo 內 SRPP 相關測試；SRPP policy／registry／hash 屬 577.10）。
- `grep -ran "new TradingRadarRuleEngine.StockInput(\|new StockInput(" backend/src/main/java`：只剩 `TradingRadarService`（1 處）與 `BacktestService`（2 處），三處皆 30 參數並顯式傳 `completedKd` 與 `structure`。

### 被改期望的既有測試與理由

只改版號字串，沒有任何動作／分數／理由期望被改：`TW_RULES_V24`→`TW_RULES_V25`（`TradingRadarRuleEngineTest` 兩處版號斷言、`BacktestServiceTest`、`TreasuryYieldServiceTest`、`TradingRadarNotificationServiceTest`、`TradingRadarPanelServiceTest`、`TradingRadarControllerCurrentTest`、`TradingRadarOpenApiSchemaContractTest`、`RedisRadarTechnicalCacheRepositoryRedisIntegrationTest`、`RadarTechnicalResolverFubonOverlayTest`、BFF `TradingRadarPanelFixtures`／`TradingRadarPanelServiceTest`），以及兩支 ruby 測試的 1.27.2→1.27.3——均為 Requirement 202 規定的版號升級。`PREVIOUS_RULE_VERSION`（V17）未動，通知升版首輪不寄信由既有測試以 V17→V25 覆蓋。既有 V24 測試因使用相容建構式（`NOT_EVALUATED`）全部原樣通過，`v25_notEvaluated_keepsV24Behaviour` 另釘住等價性。`TradingRadarRuleEngineTest` 中 Task 576 區段標題註解改寫為「V24 規則」，避免殘留舊版號字串。

### 偏差與備註

- Codex 第 1 輪程式審查（CHANGES_REQUESTED）兩項已修：補 production 路徑結構接線回歸（一般／LOCAL 快取命中／剔除診斷）；`RadarCompletedStructure` 轉換階段限制讀取根數並以守衛輸入測試證明。

- 577.4a 規格只點名 `BacktestService` 約 715 行；V13 路徑（`appendV13Code`）同樣有剔除無效收盤的過濾且也建 `StockInput`，故兩處都記錄診斷，否則 V13 候選回測會漏判 `UPSTREAM_DROPPED_ROW`。
- 美股大盤 regime 回測（`buildUsMarketRegimes`）仍走不帶診斷的 `assemble` 舊多載：它只產生 `MarketInput`，不使用結構事實。
- resolver 不記錄「completed session 當日台股 provenance 不可信」的那一根：那不是剔除壞列，而是由盤中列取代或使整筆 accepted price 缺席。
- `Assembled`／`Prepared`／`AcceptedPrice` 相容建構式均保留，供既有測試與舊 fixture；production 與回測一律走新多載／正典形狀。
- 公開 DTO／API 結構未變；結構事實只反映在既有 `reasons`／`risks` 字串與動作上。
- 577.10 未做：Swagger 內容（版號）已變，SRPP 端 Swagger identity、policy manifest、registry SQL 與 bundle hash 需依 Task 504 受控鏈一併發布；發布前若 business-services 以本分支 jar 執行，SRPP capture／evaluate 可能回 409 `SWAGGER_MISMATCH`。
- 重建後 `ruleVersion`＝`TW_RULES_V25` 的線上驗證與前後動作分布比對屬發布後步驟，尚未執行。

### 577.10 發布（`/srpp-policy-release`，主 agent，2026-11-14 00:2x 台北）

- 本輪一起發布 SRPP「交易候選與阻擋原因分類」補強：另一 session 的原版經 Codex（gpt-6.1-sol／high）判定 `ADOPT_WITH_FIXES`，由本 session 在 SRPP feature worktree `/Users/steven/Project/SRPP/.claude/worktrees/trade-blocker-fixes`（分支 `claude/trade-blocker-policy-fixes`）接手並修完 Codex 三輪共 13 項必須修。發布途中對方於 00:16:55 把未修正原版推上 SRPP main（`6c3d6d8`，內容與本 session 取樣時相同，另加 `finish_trade_blocker_push.sh`）；本分支合併該 main，衝突一律以修正版為準並保留其腳本與 README 列，contract／manifest 以工具重產（bundle `cb835766…`），SRPP 43 支 `test_*.py` 與 node 測試全過。
- 資產端先同步凍結的 SRPP manifest 原始 bytes（model 不變），再走 Task 504 鏈：renderer／sync／兩個 `--check` exit 0。新 `swaggerSha256=792adf4a1a5c4cbcb7677ac4ed0b693cd95a1f95f7991fcb214d50b43e20bb2e`（1.27.3）；最終 policy bundle **`29bb675c76c83656f22e513c270d94e81efedd85055fce530cc6bfd884b5c850`**；SRPP manifest 檔 SHA-256 `35f796e3cfc644e6ad2be9042addebe611340e01751353264b398ab93d1916b2`；model `89148de0…` 不變。Codex／Claude daily、simulation model、runtime contract 檢查 PASS（180 檔）。
- `d201-registration.sql` 以 Ruby 無參數 stdout 重產（差異僅 bundle hash）；report-same-input／long-short-exit golden 重產，結構化差異僅 `policyBundleSha256`；state-topup `--check` 通過。authority gate、generator `--check`、Ruby／Python 回歸 exit 0；後端 SRPP＋雷達相關 1281 測試、BFF 151 測試全過，`package` exit 0。
