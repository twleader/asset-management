# [t365] 修正交易雷達估值因子的權重表標籤與風險文案——「PE 自身分位」實為 PE／PB／殖利率三者算術平均

**對應 Requirements:** Requirement 101（交易雷達估值因子的權重表標籤與風險文案誤標「PE 自身分位」，實為
PE／PB／殖利率三者算術平均，須修正標籤／文案使其如實反映內容，`RULE_VERSION` 隨之升版）
**前置任務:** 無（修正既有命名落差，不依賴任何未完成工作；Requirement 96／Task 360 與 Requirement 98／
Task 362 皆已明文預告本落差「另案處理」，本任務即該另案。編號原為 Requirement 100／Task 364，因
`Requirement 100／Task 364` 已由另一支平行 worktree（`yuanta-securities-api-093d9b`，元大證券 SPARK API
唯讀查詢服務 scaffold）佔用，依專案慣例讓號為 101／365）
**Liquibase changeset:** 無（不改資料庫，`db/changelog/` 不動）

## 背景

### 現在的錯誤行為

`FundamentalAnalysisService.valuationComposite()` 的非虧損分支（`backend/src/main/java/com/steven/assets/service/FundamentalAnalysisService.java:491-500`）：

```java
Component yield = severeFinancial ? null : rawYield;
List<Component> available = java.util.stream.Stream.of(pe, pb, yield)
        .filter(java.util.Objects::nonNull).toList();
if (available.isEmpty() && rawYield == null) return null;
Double contribution = available.isEmpty()
        ? null
        : available.stream().mapToDouble(Component::contribution).average().orElse(0.0);
```

`pe`／`pb`／`rawYield`（或 `severeFinancial` 成立時排除殖利率後的 `yield`）三個 `Component` 的
`contribution` 取**算術平均**，成為單一 `contribution` 值。`severeFinancial` 定義於同檔 `:249-250`：

```java
boolean severeFinancial = (eps != null && eps.contribution() != null && eps.contribution() <= -0.8)
        || (roe != null && roe.contribution() != null && roe.contribution() <= -0.8);
```

即 EPS 或 ROE 的貢獻 `<= -0.8` 時，殖利率被排除在分子之外——但 PE、PB 仍可能同時貢獻，**該分支不是
固定只反映 PE 一項**（`valuationComponent()`，`FundamentalAnalysisService.java:515-539`，PE／PB／殖利率
三者的可用性彼此獨立，各自依 freshness／250 筆有效歷史門檻／數值正負性判斷是否回傳 `null`，實務上仍可能
只剩一個 component 貢獻；但無論落在單因子或多因子個案，「估值（PE／PB／殖利率）」這個標籤修正皆同樣正確）。
此 composite 掛在權重 `SW_PE = 0.02`（`backend/src/main/java/com/steven/assets/service/TradingRadarRuleEngine.java:113`）、
`SWG_PE = 0.03`（`:139`）、`MW_PE = 0.04`（`:164`）上。

但目前有三處字面標籤把它標成「PE 自身分位」，如同它只反映 PE 一項：

1. **權重表標籤**——`TradingRadarRuleEngine.java:223-228`：

   ```java
   static final String[] WEIGHT_TABLE_LABELS = {
           "週線 MA5", "月線 MA20", "季線 MA60", "年線 MA240", "KD／J", "MACD", "RSI",
           "乖離率 BIAS", "個股相對量", "市場環境", "完成日漲跌", "匯率", "ETF 折溢價",
           "EPS 年增", "近似 ROE", "近三月營收年增", "PE 自身分位", "產業營收年增",
           "日K 棒", "週線趨勢", "週線動能", "週線乖離", "週K 棒與量能",
   };
   ```

   索引 `16`（對應 `WEIGHT_TABLE` 同索引列的 `{ SW_PE, SWG_PE, MW_PE }`，`:213`）寫「PE 自身分位」。

2. **風險文案**——`TradingRadarRuleEngine.java:1676-1686`（節錄）：

   ```java
   pe = fundamentalContribution(fundamental.peContribution(),
           fundamental.peLoss() ? "PE（可信來源顯示虧損）" : "PE 自身分位",
           reasons, risks);
   ```

   `fundamentalContribution()` 定義於 `:2543-2550`：

   ```java
   private Double fundamentalContribution(
           Double contribution, String label, List<String> reasons, List<String> risks) {
       if (contribution == null) return null;
       double value = clampUnit(contribution);
       if (value >= 0.5) reasons.add(label + "表現正向，已納入評分。 ");
       else if (value <= -0.5) risks.add(label + "表現偏弱，已納入評分。 ");
       return value;
   }
   ```

   `label` 字面**直接組成** `reasons`／`risks` 陣列的元素字串。凡估值 composite 因子的 `contribution`
   達 `±0.5` 門檻的個股，其「原因」或「風險」清單就會出現「PE 自身分位表現正向／偏弱，已納入評分。」——
   即便該次評分實際上是 PE／PB／殖利率三者平均的結果。

3. **回測診斷標籤**——`BacktestService.java:4293-4301`：

   ```java
   private List<String> fundamentalCoverageNotes(List<CodeRun> runs) {
       record Metric(String label, java.util.function.Function<TradingRadarRuleEngine.FundamentalInput, Double> value) {}
       List<Metric> metrics = List.of(
               new Metric("EPS 年增", TradingRadarRuleEngine.FundamentalInput::epsContribution),
               new Metric("近似 ROE", TradingRadarRuleEngine.FundamentalInput::roeContribution),
               new Metric("近三月營收年增", TradingRadarRuleEngine.FundamentalInput::revenueContribution),
               new Metric("PE 自身分位／可信虧損", TradingRadarRuleEngine.FundamentalInput::peContribution),
               new Metric("產業營收年增", TradingRadarRuleEngine.FundamentalInput::industryContribution));
   ```

   同樣的標籤問題，寫進回測報表的 `notes` 欄（管理端診斷輸出，非 production `reasons`／`risks`）。

**結果：** PB 與殖利率有進評分（本身是好事），但使用者以為只有 PE；且估值三面向合計權重僅 `0.04`（中期軌），
遠低於「產業營收年增」單一因子的 `0.10`，比例落差本身也未被揭露。

### 不受影響、維持原字面的部分（務必核對，避免誤改）

- **可信虧損分支**：`loss.loss()` 為真時，`ValuationComposite.loss(...)`（`FundamentalAnalysisService.java:901-913`）：

  ```java
  static ValuationComposite loss(Component peLoss, Component pb, Component rawYield) {
      List<Component> all = java.util.stream.Stream.of(peLoss, pb, rawYield)
              .filter(java.util.Objects::nonNull).toList();
      return new ValuationComposite(
              null, null,
              pb == null ? null : pb.value(), pb == null ? null : pb.percentile(),
              rawYield == null ? null : rawYield.value(),
              rawYield == null ? null : rawYield.percentile(),
              -1.0, all.size(), componentProvider(all), mergedComponentUrls(all),
              latestComponentAvailable(all), true, latestComponentDate(all), peLoss, pb, rawYield);
  }
  ```

  `contribution` **寫死 `-1.0`**，不論 PB／殖利率是否可得都不參與平均——PB／殖利率只被保留供揭露
  （`pbValue`／`pbPercentile`／`dividendYieldPct`／`dividendYieldPercentile` 欄），不進分子。故
  `TradingRadarRuleEngine.java:1683` 的 `fundamental.peLoss() ? "PE（可信來源顯示虧損）" : ...` 這一支
  標籤本身正確，**不得改動**。

- **匯出檔逐分量欄位**：`TradingRadarExportService.FUNDAMENTAL_HEADERS`（`TradingRadarExportService.java:403-410`）
  中的 `"PE值"`、`"PE自身分位"`、`"PE可信虧損"` 三欄，對應 `peValue`／`pePercentile`／`peLossFlag`
  （`TradingRadarExportService.java:592`：`num(f, "peValue"), num(f, "pePercentile"), boolVal(f, "peLossFlag")`），
  是 **PE 元件自身**的數值與分位，不是 composite 合成分數。這三欄名稱正確，**不得改動**。

- **前端逐分量卡片**：`frontend/src/utils/valuationEvidence.js` 的 `projectValuationEvidence()` 本就把
  PE／PB／殖利率各自的 `value`／`percentile`／`provider`／`sourceUrls`／`asOf` 分開呈現（對應
  `frontend/src/views/TradingRadarView.vue:401-425` 的 `valuation-component-grid`），標籤取自各自的
  `definition.label`（PE／PB／殖利率各自獨立），與本次落差無關，**不得改動**。

### `RULE_VERSION` 必須升版，理由與既有四條不升版先例逐一比對

`spec/design.md` 的先例權威清單記載四條互斥的不升版先例：

1. Task 249：「同一份輸入前後產生完全相同的輸出」——**不成立**。上述風險文案修正會使部分歷史決策的
   `reasons`／`risks` 陣列內容改變。
2. Task 281：「**新增欄位**純揭露」——**不成立**。本次未新增任何欄位。
3. Task 336：「顯示值本身的捨入缺陷修正」——**不成立**。本次不是捨入問題，也未改變任何數值。
4. Task 362：「規則引擎輸出（`action`／`score`／`regime`／`reasons`／`risks`）與 API payload 的每一個
   欄位值**逐位不變**，變動的僅是說明性文字——畫面文案、匯出檔中專供揭露用的儲存格文字，以及 OpenAPI
   schema 的 `description`」——**不成立**。這是與 Task 362 的關鍵區別：Task 362 修正的是匯出檔「證據
   閘門原因」欄與 OpenAPI `description`，兩者都不是 `reasons`／`risks` 本身；本次修正的
   `TradingRadarRuleEngine.java:1683` 標籤**直接組成** `reasons`／`risks` 陣列元素，不滿足「逐位不變」。

四條先例一條都不適用，故套用本檔判準的預設規則：「使用者可觀察行為有實質變化」須升版——與 Task 228
（V6）／Task 232（V7）先例相同，純文字／標籤變化只要反映到 `reasons`／`risks`，就構成使用者可觀察
行為變化。**`RULE_VERSION` 由 `TW_RULES_V16` 升為 `TW_RULES_V17`**。`score`／`action`／`regime`／
`candidateAction` 對所有標的逐位不變，變的只有 `reasons`／`risks` 陣列中對應那一則文字的內容（不是
陣列長度、不是其他因子的文字、不是分數）。

升版會觸發 `TradingRadarNotificationService` 的通知基準重建（`trading_radar_notification_setting` 表
`ruleVersion` 欄，Requirement 83／Task 342 更正後的行為）：首輪不寄信、不刪訂閱狀態列、不刪收件人，
唯一影響是跨部署邊界那一次狀態轉換被吞掉，與既有升版行為一致，非本次新增風險，**不需額外處理**。

## 要做什麼

- [ ] **365.1** `TradingRadarRuleEngine.WEIGHT_TABLE_LABELS[16]`（`:226`）由 `"PE 自身分位"` 改為
  `"估值（PE／PB／殖利率）"`。此陣列 package-private，僅供測試失敗訊息指出是哪一列，本身不經任何
  API／DTO／匯出消費端輸出。

- [ ] **365.2** `TradingRadarRuleEngine.java:1683` 的非虧損分支標籤由 `"PE 自身分位"` 改為
  `"估值（PE／PB／殖利率）"`；`fundamental.peLoss()` 為真的分支標籤 `"PE（可信來源顯示虧損）"`
  **維持不變、不得改動**（理由見上方背景）。

- [ ] **365.3** `BacktestService.java:4300` 的診斷 metric 標籤由 `"PE 自身分位／可信虧損"` 改為
  `"估值（PE／PB／殖利率）／可信虧損"`，與 365.1／365.2 用語一致。

- [ ] **365.4** `TradingRadarExportService.FUNDAMENTAL_HEADERS` 的 `"PE值"`／`"PE自身分位"`／
  `"PE可信虧損"` 三欄**不得變動**——對應 PE 元件自身的 `peValue`／`pePercentile`／`peLossFlag`，
  與 composite 合成分數無關，欄名本身正確。`frontend/src/utils/valuationEvidence.js` 的逐分量卡片
  同樣**不得變動**。

- [ ] **365.5** `RULE_VERSION`（`TradingRadarRuleEngine.java:61`）由 `"TW_RULES_V16"` 改為
  `"TW_RULES_V17"`。連帶同步以下六處版號 hardcode 與 OpenAPI 契約（`grep -ran "TW_RULES_V16"` 逐一核對）：
  - `backend/src/main/java/com/steven/assets/dto/TradingRadarDto.java:9`（Javadoc 註解）
  - `backend/src/main/java/com/steven/assets/service/TradingRadarService.java:752`（註解）
  - `frontend/src/views/TradingRadarView.vue:28`（`radar.ruleVersion || 'TW_RULES_V16'` fallback）
  - `frontend/src/views/TradingRadarView.vue:823`（`description` 屬性內「TW_RULES_V15 與 TW_RULES_V16
    為不同規則版本」須同步改為「TW_RULES_V16 與 TW_RULES_V17 為不同規則版本」）
  - `frontend/src/views/TradingRadarView.vue:1144`（`radar` ref 初始值 `ruleVersion: 'TW_RULES_V16'`）
  - `docs/openapi/docker-external-api.yaml:579`（`ruleVersion: TW_RULES_V16` 範例值）；`info.version`
    依既有慣例遞增**中間碼**（minor 版號，末碼維持 `0`，例如 `1.x.0` → `1.(x+1).0`；查該檔案現有
    `info.version` 數值後套用同一慣例，不得只加最後一碼）

  執行前後都須各跑一次 `grep -ran "TW_RULES_V16" backend/src frontend/src docs/openapi` 確認：實作前
  只命中上述六處＋主檔常數（共七處）與下方 365.6 的測試檔，實作後**零命中**（測試檔已同步改為 V17）。

- [ ] **365.6** 同步既有版號測試斷言（改為 `"TW_RULES_V17"`）：
  - `backend/src/test/java/com/steven/assets/controller/TradingRadarControllerCurrentTest.java:27`
  - `backend/src/test/java/com/steven/assets/service/TradingRadarV13ActionPolicyTest.java:112`（註解）
  - `backend/src/test/java/com/steven/assets/service/BacktestServiceTest.java:806`（`@DisplayName`）／
    `:808`／`:821`／`:837`
  - `backend/src/test/java/com/steven/assets/service/TradingRadarRuleEngineTest.java:562`
  - `backend/src/test/java/com/steven/assets/service/TreasuryYieldServiceTest.java:178`（註解）／`:189`

  以下兩個測試**方法名稱**本身含 `V16` 字樣，一併改名，否則會出現「方法名稱說 V16、斷言內容是 V17」的
  自我矛盾——恰是本任務要修正的那種「命名與內容不符」問題：
  - `BacktestServiceTest.java:807`：`ruleVersionIsV16()` → `ruleVersionIsV17()`
  - `TradingRadarRuleEngineTest.java:561`：`ruleVersion_isV16()` → `ruleVersion_isV17()`

- [ ] **365.7** 新增或修改測試：非虧損分支下，`reasons`／`risks` 陣列中若含估值 composite 因子的文字，
  斷言字面須以 `"估值（PE／PB／殖利率）"` 開頭，且**不得**再出現獨立的舊字串 `"PE 自身分位"`（可用
  `assertThat(reasons/risks 內容).doesNotContain("PE 自身分位")` 之類的否定斷言釘住，避免舊字串殘留）；
  `peLoss()` 為真分支的 `"PE（可信來源顯示虧損）"` 既有測試斷言維持不變。若既有測試檔案（如
  `TradingRadarRuleEngineTest`）已有覆蓋 `fundamentalContribution()` 標籤字面的測試，直接修改該測試的
  期望字串即可，不必新增檔案。

- [ ] **365.8** **不得調整任何權重值**——`SW_PE`／`SWG_PE`／`MW_PE`（`0.02`／`0.03`／`0.04`）維持不變；
  **不得**把 PE／PB／殖利率拆成三個獨立因子；**不得**調整因子在權重表中的欄位順序或其他任一因子的
  權重；`valuationComposite()` 的計算邏輯（`available.stream().mapToDouble(Component::contribution)
  .average()` 及 `severeFinancial` 排除規則）**一個字元不得變動**——本任務只改標籤字面，不改計算。

- [ ] **365.9** 全 repo 執行 `grep -ran "PE 自身分位" backend/src frontend/src` 確認**零命中**（除
  365.6 所列測試檔的版號字串 `TW_RULES_V1x` 本身不含此片語，不受影響）；`grep -ran "PE 自身分位／可信虧損"
  backend/src` 亦須零命中。

## 驗證

**後端建置與單元測試：**

```bash
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -DextraArgLine=-Dnet.bytebuddy.experimental=true test
```

**確認零殘留舊字串：**

```bash
grep -ran "PE 自身分位" backend/src frontend/src
grep -ran "TW_RULES_V16" backend/src frontend/src docs/openapi
```

兩者皆須為空（第二條指令零命中，因為所有既有 V16 字面都已改為 V17）。

**Docker image 重建與 container recreate（本專案無 dev server，「改好」以此為準）：**

```bash
cp .env "$(git rev-parse --show-toplevel)/.env" 2>/dev/null || true
docker compose -p asset-management build --no-cache business-services frontend
docker compose -p asset-management up -d --no-deps --force-recreate business-services frontend
docker compose -p asset-management restart bff
curl -s http://localhost:8080/actuator/health
```

**實機驗證（登入後於「今日交易雷達」頁）：**

1. 頁面版號 tag 顯示 `TW_RULES_V17`。
2. 展開任一估值 composite 因子達 `±0.5` 門檻、且非可信虧損的個股，「原因」或「風險」清單中對應文字
   已改為「估值（PE／PB／殖利率）…」，不再出現「PE 自身分位…」。
3. 若能找到 `peLoss()` 為真（可信來源顯示虧損）的個股，其文字仍為「PE（可信來源顯示虧損）…」。
4. 抽查數檔的三軌 `score`／`action`／`regime` 與變更前（改動前的一次匯出或 API 回應快照）逐位一致
   ——本次不得改動任何分數。
5. `curl -s http://localhost:9090/api/public/trading-radar/today | jq -r .ruleVersion` 回
   `TW_RULES_V17`（Requirement 86 第九條公開路由）。
6. 觸發一次匯出，確認「基本面與產業」區塊的 `PE值`／`PE自身分位`／`PE可信虧損` 三欄仍在、欄名未變
   （365.4 的不變式）。

## 完成報告

（實作者做完後回填：實際改了哪些檔、`grep` 驗證輸出、`mvn test` 結果、`/run-stack` 驗收結果、與原計畫
的偏差及原因。）
