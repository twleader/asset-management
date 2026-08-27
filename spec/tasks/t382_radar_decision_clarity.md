# [t382] 校正交易雷達 evidence gate 的風險分類、三軌候選／實際動作可讀性與 V18 持有期聚焦

**對應 Requirements:** Requirement 117（gate diagnostic 必列風險、三軌不可合成、使用者可選持有期聚焦、`TW_RULES_V18`）
**前置任務:** 無。Task 365 已完成 `TW_RULES_V17` 的估值標籤修正；本 Task 從已 landed 的 V17 開始，絕不可把版本或內容退回 V16。
**Liquibase changeset:** 無（不改 DB/schema/Redis/snapshot/DTO/API shape）

## 背景與已確認的矛盾

`TradingRadarEvidenceGate.apply(...)`（`backend/src/main/java/com/steven/assets/service/TradingRadarEvidenceGate.java`）回傳的
`GatedActions.reasons()` 是安全閘門診斷。例如「evidence gate 未達…」、「必要下檔風險 evidence 不完整…」及
「原本 action 已降級…」都說明候選動作**不能直接採用**。它不是支持買進或持有的正面證據。

但目前 production 組裝點 `TradingRadarService.buildStock(...)`（同檔約 `:1051-1078`）把同一份
`gated.reasons()` 加入 medium `reasons`，同時才加到 short/swing `risks`：

```java
reasons.addAll(result.reasons());
reasons.addAll(gated.reasons());       // 錯：UI 將它顯示為「1月~6月 支持訊號」
List<String> risks = new ArrayList<>(result.risks());
...
shortRisks.addAll(gated.reasons());
...
swingRisks.addAll(gated.reasons());
```

`frontend/src/views/TradingRadarView.vue` 將 `row.reasons` 置於「1月~6月 支持訊號」、`row.risks` 置於
「1月~6月 風險提醒」。於是同一種 evidence gate failure 在中期被表達成支持、在其他兩軌又被表達成風險；這是
分類與使用者指示相互矛盾，不是因子或閘門本身的錯誤。

另一方面，API 已提供三軌 `shortCandidateAction`、`swingCandidateAction`、`candidateAction` 及其 final action，
但展開列只顯示 swing／medium 的「候選／實際」，遺漏一周軌。Requirement 93 已明定三軌分歧只揭露、不合成，
因為系統不知道使用者此筆資金的持有期；本 Task 用 client-only 聚焦選擇補齊可讀性，不能造出一個「系統最終建議」。

## 不變式與範圍

- 本 Task **不調整** `TradingRadarRuleEngine` 的權重、門檻、因子、`actionFor(...)`、candidate/promotion、V13
  holdout、`BacktestService`、行情或基本面來源。`score`、三軌 candidate action、三軌 final action、regime 必須
  對既有 deterministic fixtures 逐位不變。
- Gate 的候選／final action 與每個診斷的原有文案維持；但內部輸出必由一份混合 `reasons()` 拆成
  horizon-local medium／short／swing lists。`TradingRadarEvidenceConfidenceResolver.Evidence.reasons()` 是既有未標記
  global compatibility list，絕不可再用來分配 local list；它的 direct-caller output 維持不變。某一軌專屬的 gate/risk
  failure 不得再被複製到另外兩軌；明確聲明「三軌皆關閉」的共同缺漏才可同時出現在每個有提供的 track，兩軌相容入口的
  swing list 必是空。不得對 engine 原有 reason/risk 做 sort、global dedupe、過濾、翻譯或重新產生。
- 不新增／刪除 9090、BFF、gateway、frontend route、DTO field、OpenAPI schema field、DB table/Liquibase、Redis key、
  排程、外部 HTTP 或任何 broker/order capability。既有 public API 只會於 `ruleVersion` 值上呈現 V18。
- V18 是 reason/risk 分類與 presentation 改善，**不是**獲利保證、報酬預測或已經 backtest 證明的 performance uplift。
  所有未來因子或門檻調整必另立 Requirement/Task，並先產生既有成本後、時間分割 walk-forward/holdout 證據。

## 要做什麼

- [ ] **382.1 後端修正 gate 與唯一組裝點。** 在 `TradingRadarEvidenceGate` 與
  `TradingRadarService.buildStock(...)`：

  1. 將 `GatedActions` 擴成三份不可為 null 的 horizon-local `mediumDiagnostics`／`shortDiagnostics`／`swingDiagnostics`
     （或等價清楚命名 lists）。現有 `reasons()` accessor 必保留為相容的 stable distinct audit union，固定順序是
     medium → short → swing，且只供既有 `RadarEvidence.actionGateReasons`／top-level audit metadata。所有現有 call
     site/兩軌相容入口都必明確提供空 swing list，不得讓 `null` 或 short fallback 冒充 swing。
  2. 在 `TradingRadarEvidenceConfidenceResolver.Evidence` 新增純 `gateReasons(Horizon)`（或等價 accessor）：固定按
     PRICE_TECHNICAL、MARKET_LIQUIDITY、VALUATION、FINANCIAL_OPERATING、ASSET_SPECIFIC 順序，僅回傳
     participates 且未達該**同一** horizon 70% coverage/freshness 的既有 group 文案；再加入該 horizon 的既有
     dividend event reason（SHORT=5 個交易日，SWING/MEDIUM=20 個交易日）。它不可讀／解析 global `reasons()`，也不得
     修改 legacy `reasons()`、confidence、risk 或 action。
  3. 在 `apply(...)` 將每一次 `downgradeBuy`／`downgradeRiskExit`、medium/short/swing evidence failure、風險單位 failure
     寫到真正被影響的 horizon list；既有 buy-candidate evidence-close branch 以相同 horizon 的 `gateReasons(...)`
     取代舊 global `evidence.reasons()`。既有 incomplete-dividend「僅列風險揭露」句、`evidence == null` 與明確
     currency/profile all-track close 各加入每個有提供 track；其餘訊息不得複製。既有 action downgrade 與 candidate
     保留行為不可改，diagnostic 不能靠文字 `contains("中期")` 在 service 層猜分類。
  4. 保留 `result.reasons()`、`result.shortReasons()`、`result.swingReasons()` 各自原樣及原順序，並移除 medium
     `reasons.addAll(gated.reasons())`。
  5. 在 `TradingRadarService.buildStock(...)` 各 `new ArrayList<>(result.*Risks())` 後，僅 append 相對應的
     `gated.mediumDiagnostics()`、`gated.shortDiagnostics()`、`gated.swingDiagnostics()`；不得把全 union 加入每一軌。
  6. 同步修正另兩個既有 `GatedActions` consumer：`TradingRadarRuleEngine.evaluateBaseline(...)` 的三軌 offline
     result 必各自 append local list；V13 candidate 的 `applyCandidateActionPolicy(...)` 已有 `Horizon track`，只可把
     medium 或 short local list加到該單軌 candidate `risks`（SWING 既有禁止進 V13 candidate，不得放寬）。
  7. `TradingRadarDto.RadarEvidence.withConfidence(..., gated.reasons(), ...)` 與 top-level
     `StockDecision.actionGateReasons` 繼續使用 stable union 作可稽核 metadata；不要新增 DTO field 或 `auditReasons()`
     API surface。
  8. `gated.mediumAction()`／`shortAction()`／`swingAction()`、三個 candidate action，以及 exception/fail-closed path
     均不得改變。

- [ ] **382.2 V18 version/notification/contract 同步。** 將
  `TradingRadarRuleEngine.RULE_VERSION` 由 `TW_RULES_V17` 改為 `TW_RULES_V18`，並只同步目前 runtime／測試／生成
  contract 的 V17 字面：

  - `backend/src/main/java/com/steven/assets/dto/TradingRadarDto.java` 的 response Javadoc；
  - `backend/src/main/java/com/steven/assets/service/TradingRadarService.java` 的現行 production version 註解；
  - `frontend/src/views/TradingRadarView.vue` 的版號 fallback、初始 `radar` 與免責文字；
  - 現有 V17 assertions/comments：`TradingRadarControllerCurrentTest`、`BacktestServiceTest`、
    `TradingRadarRuleEngineTest`、`TradingRadarV13ActionPolicyTest`、`TreasuryYieldServiceTest`，以及全樹實際命中的
    runtime/test file；`RuleParameters.V12_VERSION`／`V13_VERSION` 絕不可改；
  - `docs/openapi/docker-external-api.yaml` 的兩個 radar example `ruleVersion`，並將 `info.version` **1.8.0 → 1.9.0**；
    兩處 `actionGateReasons` property/item description 必改為「三軌動作閘門／風險稽核彙總，不是任一軌 support source 或
    允許動作依據」；
  - `scripts/tests/docker-external-api-openapi-test.rb` 的 `info.version` contract expectation **1.8.0 → 1.9.0**。

  升 version 是因為 public `reasons`／`risks` 值的位置改變。不得改 `TradingRadarEvidenceGate.ACTION_POLICY_VERSION`；
  既有 `TradingRadarNotificationService` 的 `ruleVersion` mismatch path 應自然重建 baseline，首個 V18 observation 零通知、
  設定與 recipient 不刪除。保留歷史 Requirement/Task 中的 V17 記載，不可在 `spec/` 做 global replacement。

- [ ] **382.3 後端 regression tests。** 新增或擴充最貼近 `TradingRadarService` 完整組裝的 deterministic test（可擴充
  `TradingRadarUsStockEngineTest` 或新增語意明確的同 package test），用一個確實關閉 evidence/risk gate 的 fixture，
  逐字取得 horizon-local gate lists 與其 audit union 後斷言：

  1. `Evidence.gateReasons(SHORT|SWING|MEDIUM)` 對真正跨軌不同的 fixture 逐字回各自 group/event 文案，legacy
     `Evidence.reasons()` 維持原 output 且 gate 不再用它作 local routing；
  2. 每個 gate diagnostic 都**不在** `StockDecision.reasons()`、`shortReasons()`、`swingReasons()`；
  3. single-horizon failure 只在對應 `risks()`／`shortRisks()`／`swingRisks()`，明確三軌共同缺漏才在每個有提供的 track；
  4. `actionGateReasons` 是 medium→short→swing stable distinct union，包含全部 horizon-local diagnostic；
  5. 引擎原本的 support reason 仍在原屬的 support list，原本 risk 仍在原屬 risk list，沒有用 empty fixture 假裝
     證明分類；
  6. 此 fixture 的 score、三個 candidate action、三個 final action 與變更前既有期望值相同；
  7. `evaluateBaseline(...)` 三軌與 V13 medium/short candidate output 也只各帶 local diagnostic，不得保留 union-to-all
     或修改 candidate action；
  8. notification setting 的舊 `ruleVersion=TW_RULES_V17` 遇 V18 時只寫 V18 baseline、零 dispatch，且保留設定／
     recipient。

  版號 assertions、OpenAPI contract tests、snapshot/export compatibility tests 必全部改到 V18 並通過；不可只測
  `TradingRadarEvidenceGate`，因本 bug 出在 service-to-DTO mapping。

- [ ] **382.4 前端三軌閱讀與聚焦。** 新增純 ES module
  `frontend/src/utils/tradingRadarDecisionPresentation.js` 及同名 `*.test.js`，並把 test 納入
  `frontend/package.json` 的既有 `npm test` command。helper 必固定宣告四種 states：`null`、`SHORT`、`SWING`、`MEDIUM`，
  並精確映射如下（不接受 fallback 到另一軌）：

  ```text
  SHORT  = 一周       : shortCandidateAction → shortAction
  SWING  = 1周~1月    : swingCandidateAction → swingAction
  MEDIUM = 1月~6月    : candidateAction      → action
  ```

  在 `TradingRadarView.vue`：

  1. 加一個只存 `ref`、初始 `null` 的「本次資金預定持有期」選擇器；不得寫 localStorage、query、store、後端或快照。
     未選擇時顯示「請先依資金安排選擇持有期；系統不會替你合成單一建議」的提示。
  2. selected state 只決定聚焦卡片／tag/說明的視覺樣式；三軌 table columns、分數、action、support/risk lists 與
     展開內容永遠全部存在，不得因選擇而 filter、排序、fetch 或重算。
  3. 展開 `evidence-summary` 中新增「一周 候選／實際動作」；三條都以一致的時間標籤、候選／實際名稱與 `—`
     缺值呈現。可用既有 action color/type，但不得把 candidate enum 當成 final label 或自行中文猜填。
  4. `row.evidence.actionGateReasons` 必移到三軌 action cards 外的獨立區塊，標題為「動作閘門／風險」，並明示它是
     medium→short→swing 的稽核彙總，不是任一軌支持訊號或允許動作依據。
  5. 聚焦卡片與展開列顯示：「止跌與避免追價使用完成日 K（`dailyCandle.asOfDate`）判定；盤中股價／漲跌僅供展示，不重算
     訊號。」必以 `row.dailyCandle?.asOfDate` 取日期；top-level `row.asOfDate` 是 accepted quote date、盤中可為當日，
     不得稱為完成日 K。`dailyCandle?.asOfDate` 缺失時顯示資料不足。這只是現有 `completedChangePercent` 事實的揭露，
     不得改前端或後端計分。
  6. 免責文字需改為 V17/V18 不可直接比較，並明示 V18 是訊號分類／閱讀改善，不保證獲利；不能出現「最大化獲利」、
     「保證」或報酬機率語句。

  Node tests 至少覆蓋：未選擇提示；每一 selected state 的 label/field pair；缺 candidate/final 不跨軌補值；三軌均
  保留；`dailyCandle.asOfDate` 的 completed-K disclosure（live quote `asOfDate` 不同不影響文案）及 `dailyCandle`/日期缺失的
  資料不足。`npm run build` 必驗 Vue template 可編譯。

- [ ] **382.5 OpenAPI renderer 與文件。** YAML 修改後只可執行
  `ruby scripts/render-9090-openapi-docs.rb` 生成 `docs/openapi/9090-api-swagger.md` 與
  `/Users/steven/Project/SRPP/docs/9090 Port API Swagger.md`；不得手編任一 Markdown。之後執行
  `ruby scripts/render-9090-openapi-docs.rb --check` 及
  `ruby scripts/tests/docker-external-api-openapi-test.rb`。該 contract test 必驗 `info.version=1.9.0`；兩處
  `actionGateReasons` property/item description 必明定它是三軌 adverse gate/risk audit union、不是 support source 或
  允許動作依據。沒有新 schema field 的理由是 V18 已由既有
  `ruleVersion` 表達；現有 12 paths/11 GET/1 POST 的 manifest 一律不動。

- [ ] **382.6 刻意不做的事。** 不加入「推薦的唯一持有期」、個人風險屬性、成本價、部位、可用資金、交易數量、
  限價、下單、email 改發條件、V13 promotion 或回測調參。不把 9090 的匿名 loopback GET 誤當授權任何 POST；
  本 Task 完全沒有外部副作用 API call。

## 驗證

```bash
set -euo pipefail

bash scripts/spec-check.sh

/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q \
  -f backend/pom.xml \
  -DextraArgLine=-Dnet.bytebuddy.experimental=true test

cd frontend
npm test
npm run build
cd ..

ruby scripts/render-9090-openapi-docs.rb --check
ruby scripts/tests/docker-external-api-openapi-test.rb

# 歷史 spec 可保留 V17；只查 runtime/test/generated-contract 範圍。
rg -n 'TW_RULES_V17' backend/src/main backend/src/test frontend/src docs/openapi
```

最後一條在本 Task 後應無命中；若新增了其他 runtime/test 位置，必先同步而非略過。
`TW_RULES_V16` 等歷史字面可留在過往 Requirement/Task，不能以全 repo zero-match 當錯誤驗收。

使用 `/run-stack` 的實際 Compose worktree 重建與 recreate `business-services`、`frontend`，並重啟 BFF。服務可用後：

```bash
curl -fsS --max-time 20 http://127.0.0.1:9090/api/public/trading-radar/today \
  | jq '{ruleVersion, generatedAt, stocks: (.stocks | length)}'
curl -fsS --max-time 20 http://127.0.0.1:9090/api/public/trading-radar/stock?stockCode=2330\&market=台股 \
  | jq '{ruleVersion, stockCode: .stock.stockCode, action: .stock.action}'
```

第一個 read 必回 `ruleVersion: "TW_RULES_V18"`；第二個只在該 configured-admin current result 確有 2330 時做
shape check，沒有該標的則回預期 sanitized 404，不可改用任意股票或 fabricated API response。以瀏覽器開啟「今日交易雷達」時，確認：

1. rule tag 是 V18；
2. gate diagnostics 只出現在三軌風險提醒／明確的「動作閘門／風險」位置，不在任何支持訊號；
3. 三軌皆有候選→實際動作，選擇任一持有期只聚焦而不合成／隱藏其他軌；
4. `dailyCandle.asOfDate` completed-K / intraday-display 說明可見，沒有把 top-level quote `asOfDate` 說成完成日 K；
5. 既有 9090 routes、BFF read 和健康檢查可用，沒有新增 API 或 broker side effect。

若 Docker daemon、Compose stack 或所需 market data 不可用，報告精確阻礙與已通過的 deterministic test evidence；不得聲稱
runtime/API 已驗收。

## 完成報告

（實作者完成後回填：實際修改檔案、gate fixture 證據、V18 notification baseline 結果、frontend/build/OpenAPI checks、
Compose container provenance、9090 readback 或其精確阻礙，以及任何刻意未做的調參。）
