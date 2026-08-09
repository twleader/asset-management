# [t300] 近似 ROE 標度放緩（斜率 5 → 10）

**對應 Requirements:** Requirement 43／46 之 `TW_RULES_V12` 修訂第 6 條
**前置任務:** 無（同波部署，升版由 t298 執行）
**Liquibase changeset:** 無

## 背景

`FundamentalAnalysisService.roeFactor()` 現行貢獻式 `clampUnit((roe − 10.0) / 5.0)`：中心 10%、斜率 5 → ROE 15% 即 +1、ROE 5% 即 −1、**ROE 6% 即 −0.8**。而 `fundamentalDeteriorating`（`TradingRadarRuleEngine`）把「四項基本面中兩項 ≤ −0.8」判為基本面多項惡化 → **關閉買進／加碼／試單閘門**；「peLoss ＋ 任一 ≤ −0.5」同樣成立（−0.5 對應 ROE 7.5%）。

台股常態 ROE 6–10% 的傳產／金融個股會被系統性標成 severe：ROE 6%＋營收年增 −12%（revenue 貢獻 −0.8）兩項即封鎖閘門——但 ROE 6% 對這些產業是常態而非惡化。**正確行為**：severe 應對應「真正惡化」的水位。

修法：斜率 5 → 10，即 `clampUnit((roe − 10.0) / 10.0)`——±1 落在 ROE 0%／20%，−0.8 對應 **ROE ≤ 2%**（接近虧損），−0.5 對應 ROE 5%。中心維持 10%（台股全市場常態中位附近）。斜率 10 **無回測量測依據，為判斷性取值**；`fundamentalDeteriorating` 的組合門檻（兩項 ≤ −0.8；或 peLoss ＋ 任一 ≤ −0.5）**不變**。

本值進入 V12 雙軌評分（短期權重 0.03／中期 0.07），與 t297–t299 同波以 `TW_RULES_V12` 單次升版交付（升版動作在 t298，本任務不改版本字串）。

## 要做什麼

- [x] 300.1 `backend/src/main/java/com/steven/assets/service/FundamentalAnalysisService.java` 的 `roeFactor(...)`：`clampUnit((roe.doubleValue() - 10.0) / 5.0)` → `clampUnit((roe.doubleValue() - 10.0) / 10.0)`。方法 javadoc 補述：「±1 對應 ROE 0%／20%，−0.8（deteriorating severe 線）對應 ROE 2%；斜率為判斷性取值、無回測依據。」
- [x] 300.2 其餘因子標度（EPS `/20`、revenue `/15`、industry `/15`、PE 分位 `/30`）**不動**；`fundamentalDeteriorating` 門檻**不動**；近似 ROE 的計算本體（四季單季淨利合 ÷ 最新一季權益）**不動**。
- [x] 300.3 測試：`FundamentalAnalysisService` 的既有測試檔（`grep -rln "roeFactor\|approximateRoe" backend/src/test` 找到後更新；若無獨立測試檔則於既有基本面相關測試類新增）——(a) ROE 20% → +1.0；(b) ROE 10% → 0.0；(c) ROE 2% → −0.8；(d) ROE 0% → −1.0；(e) ROE 5% → −0.5（peLoss 組合門檻的新對應水位）。其他因分數變動而失敗的測試依新標度更新期望值（不得為過測試回改標度；完成報告列出更新清單）。

## 驗證

```bash
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test -DextraArgLine=-Dnet.bytebuddy.experimental=true
```

部署驗證併入 t305 完成後的整波驗證（見 t305）。

## 完成報告

**t300 完成報告：近似 ROE 標度放緩（斜率 5 → 10）**

**實作變更**
- `backend/src/main/java/com/steven/assets/service/FundamentalAnalysisService.java`：`roeFactor(...)` 的標度分母由 `5.0` 改為 `10.0`：`clampUnit((roe.doubleValue() - 10.0) / 5.0)` → `clampUnit((roe.doubleValue() - 10.0) / 10.0)`，並在方法上方補上 javadoc：「斜率 10（Task 300）：±1 對應 ROE 0%／20%，−0.8（deteriorating severe 線）對應 ROE 2%；斜率為判斷性取值、無回測依據。中心維持 10%。」除此之外未動任何其他程式碼——其餘因子標度（EPS `/20`、revenue `/15`、industry `/15`、PE 分位 `/30`）、`TradingRadarRuleEngine.fundamentalDeteriorating()` 的兩個門檻（`<=-0.8` severe、`<=-0.5` peLoss 組合）、以及近似 ROE 計算本體 `approximateRoePct()`（四季單季淨利合÷最新一季權益）皆保持原樣。全 repo 只有這一處字面量 `10.0) / 5.0`（另一處 `TradingRadarRuleEngine.java:874` 的 `/5.0` 是價格動能相關邏輯，與 ROE 無關，未觸碰）。

**測試變更**
- `backend/src/test/java/com/steven/assets/service/FundamentalAnalysisServiceTest.java`：新增 `roeFactorAppliesSlopeOfTenCenteredAtTenPercent()`，涵蓋任務要求的 5 個案例：(a) ROE 20% → +1.0、(b) ROE 10% → 0.0、(c) ROE 2% → −0.8（對齊 severe 門檻線）、(d) ROE 0% → −1.0、(e) ROE 5% → −0.5（對齊 peLoss 組合門檻的新對應水位），全部用 `assertEquals(expected, actual, 1e-9, message)` 帶 delta 比對（沿用 `TradingRadarRuleEngineTest` 既有的 `1e-9` delta 慣例）。另新增私有 helper `roeQuarters(quarterlyNetIncome, equity)`：建構單一年度 4 季（Q1–Q4，遞減排序）、年度累計值＝quarter×quarterlyNetIncome 的 `FinancialRow` 列表，使 `standalone()` 逐季相減後每季還原為常數 `quarterlyNetIncome`，讓 `approximateRoePct()` 的四季合精確等於 4×quarterlyNetIncome，藉此用單一參數精確控制餵入 `roeFactor()` 的 ROE 百分比（equity 固定 1000，quarterlyNetIncome 依序取 50／25／5／0／12.5 對應五個案例）。decision date 固定 `2025-12-31`，配合 `expectedFinancialPeriodIndex` 的 fallback deadline 邏輯使 2025 Q4 落在 fresh 範圍內（expected index 8103 ≤ 實際 8104）。

**與原計畫的偏差**
無實質偏差。任務檔已明確指名要改的那一行程式碼與 javadoc 措辭內容，照原文實作；5 組測試數值與任務檔所列完全一致，並額外驗證了 (c)(e) 兩個邊界值精確命中 `fundamentalDeteriorating()` 的既有門檻常數（`-0.8`／`-0.5`）。唯一的自主設計是 `roeQuarters()` helper 本身——任務檔只給了測試案例數值、未指定實作方式；既有的 `financialRows()` helper（原為 EPS 8 季連續測試設計）標度寫死（每季固定貢獻 100、equity 固定 10000，恆得 ROE 4%），無法參數化出 5 組不同 ROE 值，因此新增一支可控 helper 而非硬套舊的。

**更新過期望值的既有測試清單**
無。整套 backend 測試（612 個）在僅改動 `roeFactor()` 那一行分母＋新增上述測試後全綠、0 失敗、0 錯誤，代表沒有任何既有測試因這次標度變動而需要調整期望值。核對原因：
- `BacktestServiceTest`／`TradingRadarServiceOwnerScopeTest`／`TradingRadarUsStockEngineTest`／`TradingRadarMarketFreshnessTest` 都用 `@Mock FundamentalAnalysisService` 整支 mock 掉，不會實際執行 `roeFactor()` 公式。
- `TradingRadarRuleEngineTest` 對 `fundamentalDeteriorating()` 的既有測試是直接建構 `FundamentalInput(...)` 餵入寫死的 contribution 數值（不經過 `roeFactor()` 換算），不受標度變動影響。
- `FundamentalAnalysisServiceTest` 既有的 `approximateRoeAndThreeMonthRevenuePreserveNullSemantics` 測的是 `approximateRoePct()`（標度前的原始 ROE 百分比計算本體），本任務明確不動這段，故不受影響；其餘既有測試（provider 優先序、as-of 選版、美股市場閘門等）皆與 ROE 標度無關。

**驗證輸出摘要**
- 定向測試：`mvn -f backend/pom.xml test -DextraArgLine=-Dnet.bytebuddy.experimental=true -Dtest='FundamentalAnalysisServiceTest'` → 11 個測試（原 10 個＋新增 1 個，新增測試內含 5 個 assert）全綠、0 失敗、0 錯誤。
- 整套測試：同指令不帶 `-Dtest` 過濾 → exit code 0；彙總 `target/surefire-reports/` 下 72 個 report 檔，共 **612 個測試，Failures=0，Errors=0，Skipped=0**。log 中出現的 `GdriveOutputSupport`／`rclone exit 3`／Netty DNS provider 等 ERROR／WARN 訊息，逐一核對後確認是既有測試刻意驗證失敗路徑（如 `rclone非零退出才寫失敗`）或執行環境限制（macOS 缺 Netty native DNS lib）產生的預期噪音，非本次變更導致，且都是既有行為（未在本次 diff 範圍內）。

**未做的事**
- 未動 `spec/` 底下任何檔案（只 Read 了任務檔）。
- 未 commit／push／建分支／跑 docker。
- 未動 `db/changelog/`。
- 未動版本字串（`TW_RULES_V12` 升版動作屬 t298，任務檔已註明本任務不動）。
