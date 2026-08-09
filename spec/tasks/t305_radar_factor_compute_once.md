# [t305] 雙軌因子貢獻一次計算（輸出逐位不變的引擎內重構）＋整波部署驗證

**對應 Requirements:** Requirement 43 之 `TW_RULES_V12` 波修訂第 12 條（純重構、不升版）
**前置任務:** t298、t299（引擎因子與欄位定案後才重構；整波實作順序的最後一棒）
**Liquibase changeset:** 無

## 背景

`TradingRadarRuleEngine.evaluateStock()` 對同一 `StockInput` 呼叫 `evaluateHorizon` 兩次（短期軌＋中期軌），而兩軌**只有權重不同、輸入相同**——全部因子貢獻函數（MA 位置／確認、KD/J、MACD、RSI、BIAS、量能、大盤、完成日、匯率、折溢價、基本面五項）被逐檔重算兩遍，產出兩份**內容完全相同**的因子文案（reasons／risks 與 shortReasons／shortRisks 的因子段逐句重複）。除了重算浪費，更重要的風險是：日後任何人只改其中一軌路徑上的貢獻函數呼叫，兩軌就會靜默分岔。

**正確行為**：因子貢獻與因子文案**計算一次**，兩軌各自以權重累加；horizon 專屬文案（動作閘門、`describeHeat`、t299 的時機分位揭露、`actionFor` 內產生的句子）仍逐軌附加。**輸出 DTO 的 reasons／risks／shortReasons／shortRisks 內容與順序逐位不變**——這是本任務的硬約束與驗收判準。

## 要做什麼

- [x] 305.1 `backend/src/main/java/com/steven/assets/service/TradingRadarRuleEngine.java`：新增 private record `FactorContributions`，欄位為 18 因子的 `Double` 貢獻值（ma5、ma20、ma60、ma240、kdJ、macd、rsi、bias、volume、market、dayMove、fx、etfPremium、eps、roe、revenue、pe、industry；不適用者為 null）＋兩個共用文案清單 `List<String> reasons`／`risks`。新增 private 方法 `computeFactors(StockInput input, boolean narrowBand, KdHeat kdHeat)`：**按照現行 `evaluateHorizon` 內 `acc.add` 的既有順序**逐一呼叫既有貢獻函數（一次），把值與文案收進 record。既有貢獻函數（`positionOf`／`maWithConfirmation`／`kdJContribution`／`macdContribution`／…／`fundamentalContribution`）本體不動。債券的「不套用台股大盤」reasons 句、`narrowBand` risks 句、基本面缺值 risks 句、`describeHeat` **前**的所有文案都屬共用段。
- [x] 305.2 `evaluateHorizon(input, shortTerm, narrowBand, kdHeat, timing, profitTaking)` 改簽章為吃 `FactorContributions`：建 `Accumulator`，按同一順序 `acc.add(shortTerm ? SW_x : MW_x, factors.x())`；文案初始化為 `new ArrayList<>(factors.reasons())`／`new ArrayList<>(factors.risks())`，之後 `describeHeat(...)`、t299 的時機分位揭露、`actionFor(...)` 照舊逐軌執行（這些句子與現行行為一樣出現在兩軌各自清單）。`evaluateStock` 先 `computeFactors` 一次再呼叫兩次 `evaluateHorizon`。
- [x] 305.3 **硬約束**：任何既有測試對 reasons／risks 內容或順序的斷言**不得因本任務而更新期望值**——重構後輸出必須逐位等於重構前（t298／t299 定案後的輸出）。若做不到，代表重構改變了行為，回頭修重構而不是修測試。
- [x] 305.4 新增守護測試（`TradingRadarRuleEngineTest`）：構造一組因子齊全的 `StockInput`（含基本面、外幣、ETF 欄位），斷言 (a) `reasons`／`shortReasons` 的因子段（`describeHeat` 之前的句子集合）完全相等；(b) `score` 與 `shortScore` 分別等於以 V12 權重手算的期望值（權重表見 t298 298.3；兩軌用同一組貢獻值驗證「一次計算、兩軌加權」）。
- [x] 305.5 **整波部署驗證（t297–t305 全數完成後執行一次）**：依 `.claude/skills/run-stack` 慣例 rebuild + recreate，確認 V12 上線與各任務行為。

## 驗證

```bash
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test -Dtest='TradingRadarRuleEngineTest' -DextraArgLine=-Dnet.bytebuddy.experimental=true
```

```bash
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test -DextraArgLine=-Dnet.bytebuddy.experimental=true
```

整波部署驗證（本任務收尾時執行；worktree 內跑 compose build 前先確認 `.env` 已從主 repo 複製）：

```bash
docker compose -p asset-management build --no-cache business-services
```

```bash
docker compose -p asset-management up -d --no-deps --force-recreate business-services
```

```bash
sleep 20 && docker compose -p asset-management restart bff
```

```bash
curl -s http://localhost:8080/actuator/health
```

```bash
curl -s -H "X-User-Id: 1" -H "X-User-Role: ADMIN" -H "X-User-Status: ACTIVE" http://localhost:8080/api/trading-radar | head -c 400
```

```bash
curl -s -H "X-User-Id: 1" -H "X-User-Role: ADMIN" -H "X-User-Status: ACTIVE" http://localhost:8080/api/bff/trading-radar | head -c 400
```

（第二條走 BFF 路徑，順帶覆蓋「recreate business 後 bff 握舊 IP 回 500」的已知故障模式；異常時 `docker compose -p asset-management restart bff` 後重試。）

（回應 JSON 的 `ruleVersion` 應為 `TW_RULES_V12`；`docker logs asset-business-services` 應無 Liquibase ValidationFailed。）

## 完成報告

## t305 完成報告

### 實際改了哪些檔

**`backend/src/main/java/com/steven/assets/service/TradingRadarRuleEngine.java`**

1. 新增 private record `FactorContributions`：18 因子貢獻值（`ma5、ma20、ma60、ma240、kdJ、macd、rsi、bias、volume、market、dayMove、fx、etfPremium、eps、roe、revenue、pe、industry`，皆 `Double`，不適用者為 `null`）＋共用文案清單 `reasons`／`risks`，欄位順序即求值順序。

2. 新增 private 方法 `computeFactors(StockInput input, boolean narrowBand, KdHeat kdHeat)`：把原本 `evaluateHorizon` 內 `acc.add` 的既有呼叫序列（`positionOf` → `maWithConfirmation`×3 → narrowBand risks 句 → `kdJContribution` → `macdContribution` → `rsiContribution` → `biasContribution` → `volumeContribution` → `marketContribution`／債券「不套用台股大盤」reasons 句 → `completedDayContribution` → `fxContribution` → `etfPremiumContribution` → 基本面五項＋缺值 risks 句）原封不動搬進來、只呼叫一次，回傳值與文案收進 `FactorContributions`。**貢獻函數本體完全未動**，只是呼叫點從「執行兩次的 `evaluateHorizon`」搬到「執行一次的 `computeFactors`」。`kdHeat` 參數依任務檔字面簽章保留，但函式體目前未消費它——`describeHeat` 仍在 `evaluateHorizon` 內逐軌呼叫、不屬共用段；已加 javadoc 說明，避免被誤讀為疏漏死碼。

3. `evaluateHorizon` 改簽章為 `(StockInput input, boolean shortTerm, FactorContributions factors, KdHeat kdHeat, TimingState timing, boolean profitTaking)`（拿掉 `narrowBand`，其唯一用途已搬進 `computeFactors`）。內部改為 `acc.add(shortTerm?SW_x:MW_x, factors.x())` 依同一順序讀 18 欄位；`reasons`／`risks` 以 `new ArrayList<>(factors.reasons())`／`new ArrayList<>(factors.risks())` 起始，之後 `describeHeat`、t299 的時機分位揭露（`describeBiasPercentileExtreme`）、`actionFor` 仍逐軌各自執行、各自附加句子，行為與重構前一致。

4. `evaluateStock` 改為 `computeFactors` 一次、`evaluateHorizon` 兩次（皆傳同一 `factors` 實例）。

**`backend/src/test/java/com/steven/assets/service/TradingRadarRuleEngineTest.java`**

新增守護測試 `factorContributions_areSharedAcrossBothHorizons()` 與 fixture `factorCompleteStock()`（對應 305.4）：
- fixture 讓 18 因子全部有值（含基本面 EPS／ROE／營收／PE／產業、外幣 `fxPercentile`、ETF 折溢價與其分位），並刻意讓 `kdHeat=NORMAL`、`timing=NEUTRAL`、TRIAL_BUY／profitTaking／基本面惡化閘門皆不成立、兩軌分數都落在 HOLD／WATCH 純分數映射區，使 `describeHeat`／時機分位揭露／`actionFor` 對兩軌都不再附加任何文案——此時因子段即兩軌輸出全部內容，不必再自行切分子字串邊界。
- (a) 斷言 `reasons()`/`shortReasons()` 逐位相等、`risks()`/`shortRisks()` 逐位相等（比任務要求多驗證了 risks／shortRisks，加強「同一份 computeFactors 結果」的驗證力道）。
- (b) 用 V12 權重表（t298 298.3）對同一組 18 個貢獻值手算：短期 Σ(w×c)=0.429 → score=71；中期 Σ(w×c)=0.5165 → score=76（18 因子全數有值使兩軌有效權重總和皆為 1.0，故 sigma=Σ(w×c)）。此手算先用獨立 Python script 交叉驗證，再實跑測試，結果與引擎輸出一次到位完全一致（71／76），未需調整輸入或期望值。

### 驗證輸出摘要

- 編譯：`mvn -f backend/pom.xml compile` 乾淨通過（僅 sun.misc.Unsafe 相關、與本次變更無關的 deprecation warning）。
- 定向測試：`TradingRadarRuleEngineTest` 62 個測試（61 既有＋1 新增）全綠，0 Failures，0 Errors。
- 整套 backend 測試：626 個測試、0 Failures、0 Errors、0 Skipped（75 個測試類別全數綠燈，surefire 報告時間戳確認為本次執行結果）。

### 與原計畫的偏差

無實質偏差。唯一值得記錄的設計決策：`computeFactors` 依任務檔字面簽章保留 `KdHeat kdHeat` 參數，但目前函式體未使用它（原因如上，已於程式碼中以 javadoc 說明）。

### 更新過期望值的測試清單

**無**。305.3 為硬約束——本任務沒有更新任何既有測試的 reasons／risks／score 期望值；`TradingRadarRuleEngineTest` 原有 61 個測試在重構後逐位通過、無需改動任何斷言（含所有整數分數斷言，以及對 reasons／risks 內容的 `anyMatch`／`noneMatch`／`count` 斷言）。這直接證明重構前後輸出逐位相同，滿足硬約束。

> 305.5 部署補記（整波驗證，2026-08-09 主 agent 執行）：`.env` 自主 repo 複製後，`docker compose -p asset-management build --no-cache business-services frontend` 重建（兩映像 SHA 皆更新、與重建前基準線不同）、`up -d --no-deps --force-recreate` 後 business-services 約 20 秒 healthy、隨即 `restart bff`。驗證結果——jar 內含 `MarketSnapshot`／`FactorContributions` 兩個新類別；`databasechangelog` 最新一筆為 `v1.91.0-trading-radar-notification-cooldown`、`trading_radar_notification_state.last_notified_at`（timestamptz）已存在、日誌零 ValidationFailed；business 容器內帶 X-User 標頭直打 `/api/trading-radar` 回 `ruleVersion=TW_RULES_V12`、32 檔個股、大盤 regime RISK_ON；`/actuator/health` UP；bff 重啟後日誌零 Connection refused／500；`/api/bff/trading-radar` 未登入回 401（OAuth 閘門的預期行為，非故障——X-User 標頭模擬僅在 business 容器內有效）；前端 bundle `TradingRadarView-Srsur4yF.js` 含 `TW_RULES_V12` 字串。
