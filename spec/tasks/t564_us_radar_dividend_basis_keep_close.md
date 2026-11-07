# [t564] 美股配息事實不可用時，交易雷達保留完成收盤價

**對應 Requirements:** Requirements 7／43／94／99／148 補充（美股配息事實不可用時，雷達保留完成收盤價；延伸 Task 319／430）
**前置任務:** t319、t430、t552
**Liquibase changeset:** 無

## 背景

使用者回報美股收盤後交易雷達一直顯示橘字「收盤價待補」。2026-11-07 實機查證：資料庫對 14 檔追蹤美股的 2026-11-06 完成收盤列都已存在（來源 `NASDAQ_REDIS_CLOSE`／`FINMIND_US_CLOSE`），資產頁美股報價也正常；壞的是交易雷達。`GET /api/trading-radar/stock?stockCode=ASML&market=美股` 回 `risks:["讀取個股資料失敗，該檔今日不交易。"]`，堆疊為 `DistributionAdjustedPriceService.adjust ← RadarInputAssembler.prepare ← TradingRadarService.prepareTechnicalBasis ← buildDecisionCore`。

Task 552（2026-11-05，commit a465bea2）讓美股在技術序列視窗內有配息事件時，`adjust` 一律拋 `CanonicalDividendFinancialGuard.Unavailable`，兩種訊息都出現在 business 日誌：

- `US_DIVIDEND_PRICE_BASIS_UNVERIFIED`（2026-11-07 取樣，次數隨日誌增長）：ASML、AVGO、GOOGL、MRVL、MSFT、NVDA、QQQ、TSM、VOO、VT。事件已解決，但本專案沒有美股歷史價的拆股／價基證明，不能拿名目配息去還原。
- `CANONICAL_DIVIDEND_UNRESOLVED:僅有未驗證舊資料，尚無可採用的完整原始日期組`（同日取樣）：SGOV，其 2026-10-01 配息列為 `UNRESOLVED`。另有推論（非已證實）：MSFT 2026-11-19、GOOGL 2026-12-07 現為 `UNRESOLVED` 的未來列，除息日過後若仍未解決，會進視窗而拋同一種訊息。

這兩個守門決定本身保留；錯的是 `buildDecisionCore` 的整體 catch 把它們當成「讀取個股資料失敗」，連已驗證的完成收盤價一起丟掉，並讓前端以 `quoteStatus=CLOSE_PENDING` 顯示「收盤價待補」。視窗內無配息的 AMD、AMZN、COIN 正常。所以分支不可只認 `UNVERIFIED` 一種訊息，否則 SGOV 修不到。

## 要做什麼

- [x] 564.1 `TradingRadarService.buildDecisionCore` 在 `prepareTechnicalBasis(...)` 呼叫處新增單一具名分支：捕捉 `CanonicalDividendFinancialGuard.Unavailable`，且 `US_MARKET.equals(target.market())` 時，令 `technicalBasis = RadarInputAssembler.Prepared.EMPTY`，並保留 `unavailable.getMessage()` 作為原因碼；**不比對訊息字串**。台股的 `Unavailable`、美股的非 `Unavailable` RuntimeException（含 `adjust` 內其他例外）一律原樣重拋，仍由既有整體 catch 回 `DecisionCore.failed`。不修改 `CanonicalDividendFinancialGuard`、`DistributionAdjustedPriceService`、`RadarInputAssembler`、規則引擎與證據閘門。
- [x] 564.2 技術基準不可用時**不得**改用未還原價或未驗證價基計算任何指標，**不得**由歷史列相減自行推算 `changePercent`（無 accepted live 時為 null；盤中採納 live 時沿用 live 值），也不得放寬 accepted-price 判準：完成收盤價沿雷達既有 `RadarObservationResolver`（Task 319）規則，已完成日用既有 `acceptedPrice.quoteStatus()`（盤後／週末為 `VERIFIED_CLOSE`，盤中採納 live 時為 `LIVE`）。動作由規則引擎 `complete()` 早退產生三軌 `NO_TRADE`、`dataComplete=false`；確認欄位為 `UNAVAILABLE`，MA／KD 等指標值為 null。
- [x] 564.3 旗標命中且 `includeFullDetail` 時，在 `risks`、`shortRisks`、`swingRisks` 三份各加一句揭露（沿 `missingFx` 的加法位置）。字面分兩段：`TradingRadarService` 內宣告 package-private `static final String`（非 `private`，同套件測試可引用）作**字首**「美股配息資料或還原價基尚未驗證，技術指標暫不提供」；原因與句尾「（原因：<Unavailable message>）。 」（句尾一個空白，與既有句子風格一致）由方法組裝。測試以該字首常數 `contains` 比對，不在多處手寫字串，也不期望常數本身是含佔位符的整句樣板。列表模式不帶 risks，不另外處理。
- [x] 564.4 範圍限縮：不改評分、規則版本、`EVIDENCE_GATE`、SRPP 美股報告、績效比較、回測；不新增 API／BFF／DTO／DB／前端／9090／排程；**不新增寫入程式碼**、不新增外部呼叫。執行期揭露：這些美股原本在 `prepareTechnicalBasis` 提前拋例外，從未走到 `RadarTechnicalResolver` 的 market-local 快取；修正後在 `listEntry == null` 的完整路徑（HTTP 單股明細 `getStockDetail`、公開 9090 `today`／`stock` 經 `getCurrent()`、匯出、`evaluateForNotification`）會與其他美股（如 AMD）一樣開始執行**既有**的最佳努力 market-local 快取寫入（寫入全 null 指標、綁空基準 fingerprint 的快照；`Prepared.EMPTY` 的 fingerprint 不含價列，不與非空價基碰撞）；列表與 `getStockEvaluation` 走 read-only 路徑不寫。SRPP 美股 capture 內嵌 radar 的這些美股，由失敗列變成「有價、`NO_TRADE`、`dataComplete=false`」，下游 `UsCoreSellCalculator`／`UsTacticalCalculator`／`SrppReportShortUsCalculator` 的閘門要求 `dataComplete==true`（前兩者另要 `quoteStatus==LIVE`），結論不變，仍 fail-closed。僅限美股的理由：台股同樣可能拋 `Unavailable`，但目前台股零案例（雷達列無 `CLOSE_PENDING`），刻意保守不擴大，日後出現再另立任務。前端 `isClosePending` 原判準不動（它只認 `CLOSE_PENDING`，修正後這些列不再是該值）。`DecisionCore.failed`／`incompleteStock` 對 `quoteStatus` 寫死 `CLOSE_PENDING` 的行為不在本任務。
- [x] 564.5 測試主體放進既有 `TradingRadarUsStockEngineTest`，列表案例放 `TradingRadarServiceOwnerScopeTest`（前者已有美股 `stubBaseline`、`assembleAt` 與 `adjustedPriceService` mock 可 stub 例外；它的 `adjustedPriceService` 是 `@Mock`，故以 `when(adjustedPriceService.adjust(anyList(), anyList())).thenThrow(...)` 構造 `Unavailable`——美股走到 `DistributionAdjustedPriceService.adjust` 的 `DIVIDEND_EFFECTIVE_CLOSE_UNAVAILABLE` 在真實資料下不可構造，因為任何美股正配息會先拋 `UNVERIFIED`，故必須 stub，不是端到端案例）。案例：(a) 美股、`adjust` 拋 `Unavailable("US_DIVIDEND_PRICE_BASIS_UNVERIFIED")`、DB 有已完成日收盤列、盤後時鐘（如 `2026-08-10T21:00:00Z`，確保無 live）→ `price` 等於該收盤、`quoteStatus=VERIFIED_CLOSE`、`asOfDate` 為該完成日、`dataComplete=false`、三軌 `NO_TRADE`、`changePercent` 為 null、確認欄位 `UNAVAILABLE`、MA／KD 為 null，且 `assembleAt` 取得的完整明細三軌 risks 皆含上述常數字首；(b) 同 (a) 但 `adjust` 拋 `Unavailable("CANONICAL_DIVIDEND_UNRESOLVED:僅有未驗證舊資料，尚無可採用的完整原始日期組")`（SGOV 型）→ 結果相同，risk 句含該原因碼；(c) **負向回歸釘（修正前後皆綠，不是紅轉綠）**：`rawRows` 只有早於 completedSession 的列、無 live、盤後時鐘 → `acceptedPrice` 為 MISSING，其三份序列皆空，`prepareTechnicalBasis` 於 `combined` 為空處直接回 `Prepared.EMPTY`，**不經 `assembler.prepare`、不呼叫 `adjust`**；斷言 `verify(adjustedPriceService, never()).adjust(anyList(), anyList())`、`price==null`、`quoteStatus=CLOSE_PENDING`、三軌 risks 都不含新字首常數；此案不得保留非 lenient 的 `adjust` stub（嚴格 stub 會多一個 `UnnecessaryStubbingException`）。這證明的是不變量「新分支可達 ⇒ `acceptedPrice.available()`；缺完成收盤價時無從被新分支誤救，仍是 `CLOSE_PENDING`」（不得用 `rawRows` 全空當依據之外的另一種寫法，也不得宣稱此案經過新分支）；(d) 美股無配息（`adjust` 回原列、`adjusted=false`）→ 行為與修正前逐位相同，技術欄位有值、無新增 risk 句；(e) 台股且 `adjust` 拋 `Unavailable("CANONICAL_DIVIDEND_UNRESOLVED:…")` → 仍走 `DecisionCore.failed`。fixture 三個防呆缺一不可，否則會空轉通過：台股 completedSession 當日列必須設 `RadarObservationResolver` 白名單 `closeSource`（`TWSE_MI_INDEX`、`TPEX_DAILY_CLOSE`、`FINMIND_TW_CLOSE` 之一；既有美股 fixture 全檔沒設 `closeSource`，照抄會令該列被白名單排除而 `acceptedPrice` 變 MISSING，結果同樣是 `price=null`／`CLOSE_PENDING` 而與整列失敗無法區分），並用台股時鐘使 completedSession 為該列日期（如 `2026-08-10T21:00:00Z`，台北 2026-08-11 05:00 收盤前，完成日為 08-10）；`verify(adjustedPriceService).adjust(anyList(), anyList())` 確實被呼叫；斷言 risks 含既有失敗訊息「讀取個股資料失敗，該檔今日不交易。」且不含新字首常數；(f) 美股但 `adjust` 分別拋 `IllegalArgumentException` 與純 `IllegalStateException`（`Unavailable` 是 final 類別且繼承 `IllegalStateException`；後者專門偵測實作把 catch 寫成過寬的 `IllegalStateException`，前者偵測過寬的 `RuntimeException`）兩個探針 → 都仍整列失敗：`verify(adjustedPriceService).adjust(...)` 被呼叫、risks 含既有失敗訊息且不含新字首常數、`CLOSE_PENDING`、價為 null；(g) 列表路徑（使用者實際看到橘字的畫面）：在既有 `TradingRadarServiceOwnerScopeTest` 沿其 `TradingRadarListBatchPreloader.Entry`／`getList()` fixture 加一個美股案例，`entry` 帶已完成日收盤列與會令 `adjust` 拋 `Unavailable` 的配息／stub，斷言該列 `price` 等於收盤、`quoteStatus=VERIFIED_CLOSE`、`action`／`shortAction`／`swingAction` 皆 `NO_TRADE`。

## 驗證

```bash
bash scripts/spec-check.sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home /usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -Dnet.bytebuddy.experimental=true -DextraArgLine=-Dnet.bytebuddy.experimental=true -Dtest='TradingRadarUsStockEngineTest,TradingRadarDistributionDisclosureTest,TradingRadarServiceOwnerScopeTest,CanonicalDividendFinancial*,DistributionAdjusted*,RadarObservation*,UsReportCapture*' test
git diff --check
```

依 run-stack 先比對 main 有效環境（`.env`、secrets、mount），已 merge 後從乾淨 main 以 `-p asset-management` 重建，JVM 一律 `--no-cache`，並用 `unzip -l` 驗 jar 內含新常數所在的 class：

```bash
docker compose -p asset-management build --no-cache business-services
docker compose -p asset-management up -d --no-deps --force-recreate business-services
docker restart asset-bff
curl -fsS --max-time 30 'http://127.0.0.1:9090/api/public/trading-radar/stock?stockCode=ASML&market=%E7%BE%8E%E8%82%A1'
curl -fsS --max-time 30 'http://127.0.0.1:9090/api/public/trading-radar/stock?stockCode=SGOV&market=%E7%BE%8E%E8%82%A1'
curl -fsS --max-time 30 'http://127.0.0.1:9090/api/public/trading-radar/today'
```

recreate business-services 後必須重啟 bff（舊 IP 快取，見專案記憶）。等待 healthy 後讀實際 JSON：ASML、AVGO、GOOGL、MRVL、MSFT、NVDA、QQQ、SGOV、TSM、VOO、VT 皆回正確的最近完成日收盤價（與 `stock_price_history` 逐檔比對），週末或盤後 `quoteStatus=VERIFIED_CLOSE`（美股盤中採納 live 時為 `LIVE`）；AMD／AMZN／COIN 與台股列內容與修正前一致。不以容器 healthy 取代功能驗收。

## 完成報告

2026-11-07 完成。

- **根因：** Task 552 的美股配息守門例外（`US_DIVIDEND_PRICE_BASIS_UNVERIFIED`、`CANONICAL_DIVIDEND_UNRESOLVED:…`）被 `buildDecisionCore` 的整體 catch 當成整列失敗，連已存在的完成收盤價一起丟掉，雷達顯示「收盤價待補」。資料庫 14 檔追蹤美股的 2026-11-06 完成收盤列原本就齊全，沒有缺資料要回補；JPM、TSLA 的歷史停在 2026-09-14／2026-07-15，但它們不在任何持股或警示清單，不屬於雷達標的，未處理。
- **修正：** 僅改 `TradingRadarService`：`prepareTechnicalBasis` 對美股拋 `CanonicalDividendFinancialGuard.Unavailable`（只認型別、不比對訊息）時改用 `Prepared.EMPTY`，保留完成收盤價與 `VERIFIED_CLOSE`，三軌 `NO_TRADE`，完整明細三軌 risks 加揭露句（package-private 字首常數 `US_DIVIDEND_BASIS_UNVERIFIED_RISK_PREFIX`＋原因碼）。台股與美股其他例外原樣重拋。Guard／`DistributionAdjustedPriceService`／`RadarInputAssembler`／規則引擎／證據閘門／前端／BFF／DB／9090／SRPP 皆未動。
- **測試：** 新增 `TradingRadarUsStockEngineTest` 7 案（(a) UNVERIFIED、(b) SGOV 型 UNRESOLVED、(c) 缺完成日列負向回歸釘、(d) 無配息行為不變、(e) 台股仍整列失敗、(f) 兩個探針）與 `TradingRadarServiceOwnerScopeTest` 列表路徑 1 案。實作者實測：(a)(b)(g) 在修正前紅（price 為 null）、修正後綠，(c)(d)(e)(f) 前後皆綠。主 agent 獨立重跑核心 5 類 57 測全綠；實作者另跑指定 7 類 94 測、`TradingRadar*,RadarInputAssembler*,RadarTechnicalResolver*` 521 測全綠，皆 0 skipped；`git diff --check` 通過。
- **審查：** spec-check BLOCK 0／CHECK 0；spec 獨立審查兩輪（第一輪 1 critical＋4 major 已修，含 SGOV 的 UNRESOLVED 分支缺口；第二輪 0 critical、2 major 為測試設計已修並由主 agent 對照實碼確認，第三輪未再跑）；arch-auditor critical 0／major 0／minor 0，兩個 gate 皆已記錄。
- **部署驗收：** 從本 worktree 以 `--no-cache` 重建並 force-recreate 僅 `business-services`（映像 sha256:d0972f22… → ed232348…，驗收前後再 inspect 皆為新版）；jar 內 class 含新字串；`.env` 由 main 同步後比對相同，未印任何值。`/api/public/trading-radar/today`：14 檔美股皆 `VERIFIED_CLOSE`、`asOfDate=2026-11-06`、price 與 `stock_price_history` 逐檔相同；ASML、SGOV、NVDA 明細三軌 risks 含揭露句，原因碼各為 UNVERIFIED／UNRESOLVED／UNVERIFIED；business 日誌無美股「組裝失敗」。經 skill 規則 bff 未重啟，recreate 後 gateway 安全 GET 首次重試即恢復（驗證段的 `docker restart asset-bff` 與 run-stack 現行規則不同，以 skill 為準）。
- **仍保留的行為：** 這 11 檔現在顯示收盤價，但技術指標與買賣訊號仍不可用（`NO_TRADE`），這是 Task 552 守門的既有決定。要恢復訊號，需另立任務提供美股拆股／價基證明與已解決的配息事實；MSFT 2026-11-19、GOOGL 2026-12-07 的 UNRESOLVED 未來列已由本修正涵蓋（任何美股 `Unavailable` 都走同一分支）。
- 未新增測試警示、假登入、通知、寄信、排程或任何券商操作。
