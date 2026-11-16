# Task 597 架構審查材料
只同步 SRPP D221 authority bytes 與原 generator 產物；無 Java/BFF/前端/DB 程式變更。

## 驗證
- check-srpp-report-authority PASS 727be98b…；formula V3 PASS；golden 111/15/22 match；runtime export --check PASS 23 檔

## diff（大型 JSON 只列 stat，model_inputs 列語意 diff）
 CLAUDE.md                                          |   2 +-
 .../main/resources/srpp/decision/model_inputs.json | 104 ++++++++++++---------
 .../resources/srpp/decision/policy_manifest.json   |  97 +++++++++----------
 .../resources/srpp/report/d201-registration.sql    |   2 +-
 .../srpp/report-long-short-exit-golden.json        |   2 +-
 .../resources/srpp/report-same-input-golden.json   |   2 +-
 spec/design.md                                     |   4 +
 spec/requirements.md                               |  10 ++
 spec/steering/structure.md                         |   2 +-
 .../runtime/assumptions/model_inputs.json          | 104 ++++++++++++---------
 .../runtime/assumptions/policy_manifest.json       |  97 +++++++++----------
 srpp-simulator/runtime/runtime-manifest.json       |   6 +-
 12 files changed, 242 insertions(+), 190 deletions(-)
```diff
diff --git a/CLAUDE.md b/CLAUDE.md
index 7a2fdd56..04af577a 100644
--- a/CLAUDE.md
+++ b/CLAUDE.md
@@ -462,7 +462,7 @@ cd frontend
 
 | 文件 | 說明 |
 |------|------|
-| `spec/requirements.md` | User Stories + Acceptance Criteria（198 個 Requirements；最新編號為 210，136–140 間為並行 worktree 保留跳號） |
+| `spec/requirements.md` | User Stories + Acceptance Criteria（199 個 Requirements；最新編號為 211，136–140 間為並行 worktree 保留跳號） |
 | `spec/design.md` | 架構圖、ERD、API 端點、關鍵業務邏輯 |
 | `spec/tasks.md` | 任務索引（Task 1–228、264–267、269–292、297–309、311–342、344–390、393–398、409–410、416、429、464）＋尚未歸檔的 Task 201 起區段；Task 229–263、268、293–296、417、459、460 以各自自足任務檔為準，不追加至索引。 |
 | `spec/tasks/README.md` | 自足任務檔規範（新任務寫這裡，不再追加 `tasks.md`） |
diff --git a/backend/src/main/resources/srpp/report/d201-registration.sql b/backend/src/main/resources/srpp/report/d201-registration.sql
index d96b089b..896c7fd4 100644
--- a/backend/src/main/resources/srpp/report/d201-registration.sql
+++ b/backend/src/main/resources/srpp/report/d201-registration.sql
@@ -1 +1 @@
-INSERT INTO srpp_policy_registry (policy_bundle_sha256, formula_version, policy_document, formula_manifest) VALUES ('b71151fefc58dfbf2f1c129037ad1ac3ea3c1d547ce66b5cb0be9b5ff9d97c11', 'ASSET_MGMT_SRPP_V3', '{"schema":"SRPP_POLICY_DOCUMENT_V1","targets":{"CASH:TWD:DEMAND":"0.006","CASH:TWD:TERM":"0.164","STOCK:台股:0050":"0.12","STOCK:台股:0056":"0.0325","STOCK:台股:00679B":"0","STOCK:台股:00697B":"0.02","STOCK:台股:00713":"0.0433333333","STOCK:台股:00719B":"0.1997","STOCK:台股:00751B":"0","STOCK:台股:00850":"0","STOCK:台股:00865B":"0.025","STOCK:台股:00878":"0.0325","STOCK:台股:00881":"0.0653","STOCK:台股:00919":"0.0216666667","STOCK:台股:009826":"0.025","STOCK:美股:SGOV":"0.02","STOCK:美股:VOO":"0.2","STOCK:美股:VT":"0.025"}}', '{"calculations":{"allocation":{"assetKey":"ASSET_KEY_V1","calculationId":"ASSET_MGMT_TOTAL_EXPOSURE_V1","division":"MathContext(34,HALF_EVEN)"},"assets":{"calculationId":"ASSET_MGMT_ASSETS_RECON_V2","dataAsOf":"MIN_LIVE_STOCK_UPDATED_AT_V1","depositInterest":"SnapshotAggregateCalculator.depositEstimatedInterest","depositNullRatePolicy":"ORDINARY_UNAVAILABLE_TRANSIT_ZERO_V2","inputs":"LatestAssetsDto.Response(snapshot,liveAssets,targetPriceComplete)","revisionProjection":"SNAPSHOT_DETAIL_EXCLUDING_DISPLAY_FIELDS_V1","tolerance":"max(0.01,max(abs(detail),abs(reported))*0.0001)"},"cashIncome":{"calculationId":"ASSET_MGMT_GROSS_INCOME_V2","depositNullRatePolicy":"ORDINARY_UNAVAILABLE_TRANSIT_ZERO_V2","netCalculation":"NOT_VERIFIED"},"completedTechnicals":{"calculationId":"ASSET_MGMT_TECHNICALS_UNAVAILABLE_V1","status":"CALCULATOR_NOT_VERIFIED"},"dividendStabilization":{"calculationId":"DIVIDEND_TOPUP_D218_COMPLETED_TWO_SESSIONS_FIXED_WAVE_CUMULATIVE_BUDGET","sourceSha256":{"SRPP/scripts/dividend_stabilization.py":"2a5c47e261eb83d560ace9a37591d5e958423e0447ec9c9dd896823972a19f5e","asset-management/backend/src/main/java/com/steven/assets/service/srpp/report/SrppDividendStabilization.java":"d3fe87bab027b27f9ac52d420bf7e3a76d90120983a9bae802bc6749afd1a591","asset-management/backend/src/main/java/com/steven/assets/service/srpp/report/SrppReportModuleCalculator.java":"c318166e411aa00922a5754284137cdf39f2a307538f6fbd0f7de7d7ea9428ac","asset-management/backend/src/main/java/com/steven/assets/service/srpp/report/SrppReportTopupSizing.java":"d150a37d072b519990b3a7da91cfc9b2bee3804adee1b194f29aebb92ac3f4f8"}},"funding":{"calculationId":"ASSET_MGMT_FUNDING_UNAVAILABLE_V1","status":"CALCULATOR_NOT_VERIFIED"}},"decimal":"canonical-string","formulaVersion":"ASSET_MGMT_SRPP_V3","hash":"RFC8785-JCS+SHA-256","offsetLessTimezone":"Asia/Taipei","schema":"SRPP_FORMULA_MANIFEST_V3","timezone":"Asia/Taipei"}') ON CONFLICT (policy_bundle_sha256) DO NOTHING;
+INSERT INTO srpp_policy_registry (policy_bundle_sha256, formula_version, policy_document, formula_manifest) VALUES ('727be98b36a55a1127af02f4002c6797ab12cae56e603d466e7060e6f7d62d27', 'ASSET_MGMT_SRPP_V3', '{"schema":"SRPP_POLICY_DOCUMENT_V1","targets":{"CASH:TWD:DEMAND":"0.006","CASH:TWD:TERM":"0.164","STOCK:台股:0050":"0.12","STOCK:台股:0056":"0.0325","STOCK:台股:00679B":"0","STOCK:台股:00697B":"0.02","STOCK:台股:00713":"0.0433333333","STOCK:台股:00719B":"0.1997","STOCK:台股:00751B":"0","STOCK:台股:00850":"0","STOCK:台股:00865B":"0.025","STOCK:台股:00878":"0.0325","STOCK:台股:00881":"0.0653","STOCK:台股:00919":"0.0216666667","STOCK:台股:009826":"0.025","STOCK:美股:SGOV":"0.02","STOCK:美股:VOO":"0.2","STOCK:美股:VT":"0.025"}}', '{"calculations":{"allocation":{"assetKey":"ASSET_KEY_V1","calculationId":"ASSET_MGMT_TOTAL_EXPOSURE_V1","division":"MathContext(34,HALF_EVEN)"},"assets":{"calculationId":"ASSET_MGMT_ASSETS_RECON_V2","dataAsOf":"MIN_LIVE_STOCK_UPDATED_AT_V1","depositInterest":"SnapshotAggregateCalculator.depositEstimatedInterest","depositNullRatePolicy":"ORDINARY_UNAVAILABLE_TRANSIT_ZERO_V2","inputs":"LatestAssetsDto.Response(snapshot,liveAssets,targetPriceComplete)","revisionProjection":"SNAPSHOT_DETAIL_EXCLUDING_DISPLAY_FIELDS_V1","tolerance":"max(0.01,max(abs(detail),abs(reported))*0.0001)"},"cashIncome":{"calculationId":"ASSET_MGMT_GROSS_INCOME_V2","depositNullRatePolicy":"ORDINARY_UNAVAILABLE_TRANSIT_ZERO_V2","netCalculation":"NOT_VERIFIED"},"completedTechnicals":{"calculationId":"ASSET_MGMT_TECHNICALS_UNAVAILABLE_V1","status":"CALCULATOR_NOT_VERIFIED"},"dividendStabilization":{"calculationId":"DIVIDEND_TOPUP_D218_COMPLETED_TWO_SESSIONS_FIXED_WAVE_CUMULATIVE_BUDGET","sourceSha256":{"SRPP/scripts/dividend_stabilization.py":"2a5c47e261eb83d560ace9a37591d5e958423e0447ec9c9dd896823972a19f5e","asset-management/backend/src/main/java/com/steven/assets/service/srpp/report/SrppDividendStabilization.java":"d3fe87bab027b27f9ac52d420bf7e3a76d90120983a9bae802bc6749afd1a591","asset-management/backend/src/main/java/com/steven/assets/service/srpp/report/SrppReportModuleCalculator.java":"c318166e411aa00922a5754284137cdf39f2a307538f6fbd0f7de7d7ea9428ac","asset-management/backend/src/main/java/com/steven/assets/service/srpp/report/SrppReportTopupSizing.java":"d150a37d072b519990b3a7da91cfc9b2bee3804adee1b194f29aebb92ac3f4f8"}},"funding":{"calculationId":"ASSET_MGMT_FUNDING_UNAVAILABLE_V1","status":"CALCULATOR_NOT_VERIFIED"}},"decimal":"canonical-string","formulaVersion":"ASSET_MGMT_SRPP_V3","hash":"RFC8785-JCS+SHA-256","offsetLessTimezone":"Asia/Taipei","schema":"SRPP_FORMULA_MANIFEST_V3","timezone":"Asia/Taipei"}') ON CONFLICT (policy_bundle_sha256) DO NOTHING;
diff --git a/backend/src/test/resources/srpp/report-long-short-exit-golden.json b/backend/src/test/resources/srpp/report-long-short-exit-golden.json
index e8a640db..b354207b 100644
--- a/backend/src/test/resources/srpp/report-long-short-exit-golden.json
+++ b/backend/src/test/resources/srpp/report-long-short-exit-golden.json
@@ -12491,6 +12491,6 @@
     "test_long_short_term_exit.py": "47661e96d8a3b47fe1071a073f1b37d4d362c743f40807bb740fef6400e40658",
     "test_short_term_sleeve.py": "1ecc80296e97c20793d24c47a386bbdf6f5274c789d0ce10a47c4a5cd9337a02"
   },
-  "policyBundleSha256": "b71151fefc58dfbf2f1c129037ad1ac3ea3c1d547ce66b5cb0be9b5ff9d97c11",
+  "policyBundleSha256": "727be98b36a55a1127af02f4002c6797ab12cae56e603d466e7060e6f7d62d27",
   "schema": "SRPP_LONG_SHORT_EXIT_GOLDEN_V1"
 }
diff --git a/backend/src/test/resources/srpp/report-same-input-golden.json b/backend/src/test/resources/srpp/report-same-input-golden.json
index e2b8c127..339d7c9e 100644
--- a/backend/src/test/resources/srpp/report-same-input-golden.json
+++ b/backend/src/test/resources/srpp/report-same-input-golden.json
@@ -39276,6 +39276,6 @@
     "strategic_buy_gate.py": "e2312e870d97ceccbe547a334e721bbfecc1624f818998b2efbb704577954b69",
     "us_tactical_order_check.py": "931aedbef8b7970a7996af1af80dfd70448a4c24e64c16fd5b3b495a801fb86d"
   },
-  "policyBundleSha256": "b71151fefc58dfbf2f1c129037ad1ac3ea3c1d547ce66b5cb0be9b5ff9d97c11",
+  "policyBundleSha256": "727be98b36a55a1127af02f4002c6797ab12cae56e603d466e7060e6f7d62d27",
   "schema": "SRPP_REPORT_SAME_INPUT_GOLDEN_V1"
 }
diff --git a/spec/design.md b/spec/design.md
index e81bfd0b..2cf5b04c 100644
--- a/spec/design.md
+++ b/spec/design.md
@@ -14042,6 +14042,10 @@ VOO暫時替代規則仍是SRPP saved-evidence budget diagnostics：尚無正式
 本階段不修改Swagger/路由/DB/排程，不執行正式registry SQL或部署；最終source hash凍結且主代理驗收後才以獨立後續release步驟處理雙repoGit、insert-only登錄、受控服務重建與精確hash業務API回讀，任何缺口必須保留原狀態。
 
 
+### Task 597：D221 半導體龍頭事件下限的精確權威同步
+
+資產端僅同步 SRPP 最終來源 bytes（D221／policy 2.69.0／model 3.24.0）與由原工具產生的衍生產物：`backend/src/main/resources/srpp/decision/{model_inputs,policy_manifest}.json`、`srpp/report/d201-policy-document.json`、`srpp/report/d201-registration.sql`、四組 report golden 及 `srpp-simulator/runtime`。D221 修改的是 SRPP 報告端的語意評分（半導體類別下限、D204 美股→台股取 IXIC/SOX 較低分），由 SRPP 本機 `leader_shock.py`／`cross_market_context.py` 在保存證據上計算；9090 不計算這兩項，也不新增 wire。`SRPP_RISK_RUBRIC_V2` 只雜湊類別代碼、上限與門檻，本次未變，Java `SrppRiskRubricV1`／V2 驗證與 formula V3 保持原狀。發布採 Task 504 順序：雙 repo 落地後 insert-only 登錄，從乾淨 main 重建 business-services 與 srpp-simulator，回讀 JAR authority 與 runtime bytes，再從 SRPP host 做三 GET 連線驗收；report-calculations 正式採用待合法 09:05–13:30 時窗，其他時間記 NOT_YET_EXECUTED。
+
 ## Task 592：訂閱／本機 AI 解讀架構
 
 既有 SrppSettingsAiPort 改為 capabilities、validateSelection 與 interpret(frozenResult,selection)，由 SrppSettingsLocalAi HTTP adapter 取代 SrppSettingsAnthropicAi；不影響 PortfolioAdviceService 的其他功能。backend與BFF的CenterResponse均增加 typed aiTools 與 aiCatalogRevision，RunResponse均增加 aiSelection、aiExecution，SrppSettingsPresentation.center/run必須完整攜帶新欄位與舊列null；部署重建business-services、bff、frontend。舊 aiAvailable 保留為工具至少一組可用的摘要。simulate body 增 aiSelection，其他CAS／owner／deterministic engine 契約不變。選擇先驗證，再排隊；執行時再次驗證。LOCAL path 不探測 AI。
diff --git a/spec/requirements.md b/spec/requirements.md
index 86138ca1..d9c31e86 100644
--- a/spec/requirements.md
+++ b/spec/requirements.md
@@ -7011,6 +7011,16 @@ Task552 Quota接口補充：QUOTA_EXHAUSTED與由同一全域quota退避衍生
 - [ ] 210.5 不新增HTTP路由、DTO、DB schema、行情來源、排程、報告、交易或郵件；本階段僅在獨立feature worktree準備具體可審閱結果，commit/merge/push、正式registry與部署待主代理凍結最終來源後另行協調。
 
 
+### Requirement 211：D221 半導體龍頭事件下限與 D204 費半連動的 SRPP 權威同步（Task 597）
+
+使用者於 2026-11-16 核准 SRPP D221（policy 2.69.0／model 3.24.0）：六項風險中「半導體景氣與先進製程」新增龍頭重大事件下限（十家固定名單、三種事件、5 個台股交易日窗口，SOX 已完成日確認可升至 2 分，由 SRPP `scripts/leader_shock.py` 計算），D204 美股→台股連動改評 IXIC 與 SOX 取較低分。資產管理必須採同一最終來源，不另造規則。
+
+- [ ] 211.1 精確同步最終 SRPP `model_inputs.json`、`policy_manifest.json` 原 bytes，以原工具產生 canonical `d201-policy-document.json`、insert-only `d201-registration.sql` 與四組原計算器 golden；`check-srpp-report-authority.py --check` 對最終來源通過，不放寬任何檢查。
+- [ ] 211.2 `SRPP_RISK_RUBRIC_V2` 的類別代碼、上限與 5／8／3／4 門檻不變，`rubricSha256` 必須與發布前相同；Java 風險 rubric、formula V3 與計算器 hashes 不得改變。
+- [ ] 211.3 以既有 SRPP `export_settings_runtime.py` 同步 `srpp-simulator/runtime` 共 23 檔（含 `runtime-manifest.json`），`--check` 通過；不手抄。
+- [ ] 211.4 D221 龍頭事件下限與 D204 SOX 連動屬 SRPP 報告端 LLM／本機計算器的評分範圍；本次不新增 9090 HTTP 路由、DTO、DB schema、行情來源、排程、報告、交易或郵件，也不由 9090 計算該下限。
+- [ ] 211.5 相關 Java 離線回歸（SrppReport*、SrppSettings*、DecisionPolicyAuthorityTest）須通過；若有斷言綁定舊 decision／policy version，只更新為同版來源取值，不改正式演算法。
+
 ### Task 592：訂閱／本機 AI 工具、模型與思考程度（Requirement 208 修訂）
 
 使用者希望情境模擬可選可擴充的 AI 執行工具、model 與 effort，沿用既有訂閱或本機模型，禁止額外 API 計費。本修訂取代原 AI 直接 Anthropic SDK／共用 PortfolioAdvice 模型設定；其他功能的既有 AI 設定不在本次變更範圍。
diff --git a/spec/steering/structure.md b/spec/steering/structure.md
index 623f96cc..d26b73e0 100644
--- a/spec/steering/structure.md
+++ b/spec/steering/structure.md
@@ -576,7 +576,7 @@ frontend/
 
 ```
 spec/
-├── requirements.md       # 198 個 Requirements（最新編號為 210，136–140 間為並行 worktree 保留跳號）
+├── requirements.md       # 199 個 Requirements（最新編號為 211，136–140 間為並行 worktree 保留跳號）
 ├── design.md             # 架構圖、ERD、Service 職責、Sequence
 ├── tasks.md              # 索引（Task 1–228、264–267、269–292、297–309、311–342、344–390、393–398、416）＋尚未歸檔的 201 起區段；Task 417、459、460、466、467 僅有獨立任務檔，不追加索引
 ├── tasks/                # 任務檔
diff --git a/srpp-simulator/runtime/runtime-manifest.json b/srpp-simulator/runtime/runtime-manifest.json
index 38974fd2..3e3b1949 100644
--- a/srpp-simulator/runtime/runtime-manifest.json
+++ b/srpp-simulator/runtime/runtime-manifest.json
@@ -1,8 +1,8 @@
 {
-  "baseBundleSha256": "b71151fefc58dfbf2f1c129037ad1ac3ea3c1d547ce66b5cb0be9b5ff9d97c11",
+  "baseBundleSha256": "727be98b36a55a1127af02f4002c6797ab12cae56e603d466e7060e6f7d62d27",
   "files": {
-    "assumptions/model_inputs.json": "0e8a30a19cfeca49b5781a865e6ef0bbdb3be6c64f9d9d16b679d035d4899500",
-    "assumptions/policy_manifest.json": "13d77e7507bfbd8ca76a308efc7272323d938ec1e52343f47f33b8c3a7037d78",
+    "assumptions/model_inputs.json": "4fe09a11ec9161e589b4fa5393919efe5d9ccb24bacd8b0ebdd702312e7aaa61",
+    "assumptions/policy_manifest.json": "240c713a0663c6252e4dfdbd7505922232d8d6e0f6b4bb20935425956cae76c5",
     "assumptions/pre_retirement_payroll.json": "c146cb1f0fb9c19778075a376bb7e08a7f501e4e5839da594c19e5c3948637e5",
     "assumptions/settings_catalog.json": "730db3b0743b0b71df5e20274951c91eab1ca3325b964b67c939b9aada3bb3e3",
     "scripts/bear_ladder.py": "fd32d2655df96bcff2a006db136c711bd7830c004403abf2565e8e65bc40eb81",
```
## model_inputs.json 語意 diff
```diff
diff --git a/backend/src/main/resources/srpp/decision/model_inputs.json b/backend/src/main/resources/srpp/decision/model_inputs.json
index 721b7725..8e8bee98 100644
--- a/backend/src/main/resources/srpp/decision/model_inputs.json
+++ b/backend/src/main/resources/srpp/decision/model_inputs.json
@@ -2,3 +2,3 @@
   "model_version": "3.24.0",
-  "policy_version": "2.68.0",
+  "policy_version": "2.69.0",
   "base_currency": "TWD at August 2026 purchasing power",
@@ -690,3 +690,3 @@
     "two_track_consistency_d144": {
-      "decision": "D-220",
+      "decision": "D-221",
       "purpose": "Three under-specified rules let the Claude and Codex tracks produce different lot counts from an identical policy bundle on 2026-09-04 09:30. Each is now a boolean the rule scanner asserts, so neither track can reintroduce its own reading.",
@@ -744,3 +744,5 @@
           "source_index": "IXIC",
-          "use": "Most recent completed Nasdaq Composite close is an advisory input for Taiwan-listed securities."
+          "additional_source_indices": ["SOX"],
+          "combine": "LOWEST_SCORE",
+          "use": "Most recent completed Nasdaq Composite and Philadelphia Semiconductor closes are advisory inputs for Taiwan-listed securities (D-221). Each is scored with the same bands and the lower score is used; if only one is verified, use it and name the missing one. The D-216 crash warning still uses IXIC only."
         },
@@ -982,3 +984,3 @@
     "market_spend_adjustment_d193": {
-      "decision": "D-220",
+      "decision": "D-221",
       "annual_adjustment_twd_2026_real": 50000,
@@ -1051,3 +1053,3 @@
   "execution_policy": {
-    "decision": "D-220",
+    "decision": "D-221",
     "retirement_cash_ratio": 0.17,
@@ -1084,3 +1086,3 @@
     "stock_purchase_funding_policy_d150": {
-      "decision": "D-220",
+      "decision": "D-221",
       "twd_term_deposit_floor_twd": 800000,
@@ -1342,4 +1344,4 @@
           "0": "可比主要指標未顯示週期收縮或競爭惡化",
-          "1": "一項主要週期指標收縮或可驗證的競爭壓力升高",
-          "2": "至少兩項主要週期指標收縮、重大展望下修或先進製程延誤",
+          "1": "一項主要週期指標收縮或可驗證的競爭壓力升高；或D-221窗口內有產業龍頭重大事件（LEADER_SHOCK）",
+          "2": "至少兩項主要週期指標收縮、重大展望下修或先進製程延誤；或D-221窗口內有LEADER_SHOCK且事件後SOX任一完成交易日跌幅≤-3%或連續兩個完成交易日累計跌幅≤-5%",
           "3": "最新可比資料顯示台積電先進製程市占低於60%或已證實重大供應中斷"
@@ -1361,3 +1363,17 @@
     "risk_score_override": "Use only the six canonical risk_score_categories and their rubric. Within each category take the highest applicable score. Missing, incomparable or contradictory evidence is EVIDENCE_INSUFFICIENT and does not count as evaluated. Defensive mode applies to risk-increasing equity buys when any evaluated category is 3, the total score is 8 or more, or fewer than four categories are evaluated; the cautious total threshold is 5 and is disclosure only. Defensive mode never delays a mandatory risk reduction and never by itself blocks D-130 defensive bond-ETF buying. Current total thresholds are defined by risk_score_decision_policy and SRPP_RISK_RUBRIC_V2; no trading authority.",
-    "risk_score_evidence_policy": "D-168: a category is evaluated only when the round cites at least one traceable, in-window permitted source: an official primary source retrieved this round; a specific item in the latest permitted public_info_*.json whose generatedAt is no older than three calendar days before the run date; for oil also the round's oil/gold JSON or commodity API; for semiconductor also the round's Philadelphia Semiconductor and KOSPI inputs and semiconductor_risk_inputs. If permitted sources were read and no item meets rubric level 1 or higher, the category is score 0 with VERIFIED_NO_RUBRIC_EVENT and counts as evaluated. Only when no permitted source for that category could be read, or the sources are incomparable or contradictory, is it score 0 with EVIDENCE_INSUFFICIENT and not evaluated. All Taiwan place names treat 臺/台 as equivalent, including 台灣/臺灣, 台北/臺北, 台中/臺中, 台南/臺南, 台東/臺東 and 台西/臺西; preserve source spelling and normalize only for comparison. Category labels also treat full-width/half-width slashes as identical. D-169: the evaluated cell uses an exact yes/no status; UNVERIFIED never means VERIFIED. Facts/source/period and reason must be nonempty, with an explicit source period for evaluated rows. Insufficient or contradictory evidence anywhere in the row cannot be scored or counted as evaluated. D-170: a failed secondary source (SOURCE_UNAVAILABLE, UNVERIFIED or unreachable) disclosed in the facts/source cell does not negate an evaluated row that also cites a successfully read, dated permitted source; only when every source failed is the category EVIDENCE_INSUFFICIENT. A unique summary row must state the mode computed from risk_score_decision_policy, with matching totals and evaluated counts; presentation checks do not certify the underlying source. D-171: separate source records with semicolons or |, not commas. When failures and successes coexist, mark the successful record as 已讀: source plus its year-month/date. A detached date or period is not a readable source; all unavailable categories remain unevaluated with zero points and the appropriate defensive mode. 2026-11-11 evidence-adoption clarification: before declaring a category unassessed, inspect the same-round latest permitted public_info items and the category-specific permitted inputs, recording item/URL, publication date, actual data period and adoption/rejection reasons. Do not require an additional official full-text fetch or every sub-indicator when applicable permitted evidence already supports the rubric classification. Source existence alone does not prove a score; titles and summaries support only their explicit facts. Fed assessment includes latest guidance, not only the previous rate decision. Geopolitics includes non-Taiwan conflicts and their evidenced energy, shipping or technology impacts; do not import the D-204 official Taiwan-Strait hard-gate restriction into this category. Semiconductor permits assessed zero with applicable permitted inputs and no rubric event, without demanding every cycle/competition/market-share field; index gains alone do not prove operating improvement. Preserve actual data dates, comparability and conflicting-evidence failures. CPI comparability requirements and all numeric risk thresholds remain unchanged. Full operating interpretation: docs/DAILY_TRADING_AUTOMATION.md section 6 item 6. Both consumers use this standard independently; no fixed scores or forced BUY.",
+    "risk_score_evidence_policy": "D-168: a category is evaluated only when the round cites at least one traceable, in-window permitted source: an official primary source retrieved this round; a specific item in the latest permitted public_info_*.json whose generatedAt is no older than three calendar days before the run date; for oil also the round's oil/gold JSON or commodity API; for semiconductor also the round's Philadelphia Semiconductor and KOSPI inputs and semiconductor_risk_inputs. If permitted sources were read and no item meets rubric level 1 or higher, the category is score 0 with VERIFIED_NO_RUBRIC_EVENT and counts as evaluated. Only when no permitted source for that category could be read, or the sources are incomparable or contradictory, is it score 0 with EVIDENCE_INSUFFICIENT and not evaluated. All Taiwan place names treat 臺/台 as equivalent, including 台灣/臺灣, 台北/臺北, 台中/臺中, 台南/臺南, 台東/臺東 and 台西/臺西; preserve source spelling and normalize only for comparison. Category labels also treat full-width/half-width slashes as identical. D-169: the evaluated cell uses an exact yes/no status; UNVERIFIED never means VERIFIED. Facts/source/period and reason must be nonempty, with an explicit source period for evaluated rows. Insufficient or contradictory evidence anywhere in the row cannot be scored or counted as evaluated. D-170: a failed secondary source (SOURCE_UNAVAILABLE, UNVERIFIED or unreachable) disclosed in the facts/source cell does not negate an evaluated row that also cites a successfully read, dated permitted source; only when every source failed is the category EVIDENCE_INSUFFICIENT. A unique summary row must state the mode computed from risk_score_decision_policy, with matching totals and evaluated counts; presentation checks do not certify the underlying source. D-171: separate source records with semicolons or |, not commas. When failures and successes coexist, mark the successful record as 已讀: source plus its year-month/date. A detached date or period is not a readable source; all unavailable categories remain unevaluated with zero points and the appropriate defensive mode. 2026-11-11 evidence-adoption clarification: before declaring a category unassessed, inspect the same-round latest permitted public_info items and the category-specific permitted inputs, recording item/URL, publication date, actual data period and adoption/rejection reasons. Do not require an additional official full-text fetch or every sub-indicator when applicable permitted evidence already supports the rubric classification. Source existence alone does not prove a score; titles and summaries support only their explicit facts. Fed assessment includes latest guidance, not only the previous rate decision. Geopolitics includes non-Taiwan conflicts and their evidenced energy, shipping or technology impacts; do not import the D-204 official Taiwan-Strait hard-gate restriction into this category. Semiconductor permits assessed zero with applicable permitted inputs and no rubric event, without demanding every cycle/competition/market-share field; index gains alone do not prove operating improvement. Preserve actual data dates, comparability and conflicting-evidence failures. CPI comparability requirements and all numeric risk thresholds remain unchanged. Full operating interpretation: docs/DAILY_TRADING_AUTOMATION.md section 6 item 6. Both consumers use this standard independently; no fixed scores or forced BUY. D-221 semiconductor leader shock: run scripts/leader_shock.py on the round's saved permitted event sources and completed SOX series; its floorScore is a minimum for the semiconductor category (take the higher of it and the ordinary rubric reading). Only the event itself is adopted from a title or announcement; never infer revenue, capex or supply-chain impact from it. The fixed company list, event types, five-Taiwan-session window and SOX thresholds are in semiconductor_leader_shock_d221.",
+    "semiconductor_leader_shock_d221": {
+      "decision": "D-221",
+      "calculator": "scripts/leader_shock.py",
+      "category": "semiconductor_cycle_and_advanced_process",
+      "companies": ["NVDA", "TSMC", "SAMSUNG", "SK_HYNIX", "AVGO", "AMD", "INTC", "ASML", "AAPL", "MU"],
+      "event_types": ["UNPLANNED_LEADER_DEPARTURE", "FORMAL_INVESTIGATION", "NEW_EXPORT_CONTROL_OR_SANCTION"],
+      "source_kinds": ["OFFICIAL_COMPANY", "REGULATOR", "PUBLIC_INFO_TITLE"],
+      "window_taiwan_sessions": 5,
+      "taiwan_close_time": "13:30",
+      "sox_single_session_decline_pct_at_or_below": -3.0,
+      "sox_two_session_cumulative_decline_pct_at_or_below": -5.0,
+      "scores": {"event_in_window": 1, "event_in_window_with_sox_confirmation": 2},
+      "rule": "UNPLANNED_LEADER_DEPARTURE means an unplanned exit of the founder, chief executive or chair (resignation, removal or unannounced interim appointment). FORMAL_INVESTIGATION means the company or its top executive becomes the subject of a formal criminal, congressional or regulator investigation. NEW_EXPORT_CONTROL_OR_SANCTION means a major government announces a new export control or sanction naming the company. Each event needs a traceable official company or regulator source, or a permitted public_info item whose title states the event itself. The window starts on the first Taiwan session that opens at or after the announcement (an announcement after the 13:30 Taiwan close starts the next session) and lasts five Taiwan sessions. SOX confirmation uses only completed SOX daily closes dated on or after the announcement date in New York. The floor never exceeds 2 and never by itself changes thresholds, maxima or defensive rules."
+    },
     "semiconductor_risk_inputs": [
@@ -1447,3 +1463,3 @@
     "high_dividend_execution_policy": {
-      "decision": "D-220",
+      "decision": "D-221",
       "broker": "Cathay Securities",
@@ -1498,3 +1514,3 @@
       "daily_topup_d170": {
-        "decision": "D-220",
+        "decision": "D-221",
         "supersedes": "D-170 base-lot rule",
@@ -1527,3 +1543,3 @@
     "llm_orchestrated_api_d197": {
-      "decision": "D-220",
+      "decision": "D-221",
       "controller": "LLM",
@@ -1791,3 +1807,3 @@
       "decision": "D-170",
-      "current_entry_rule_decision": "D-220",
+      "current_entry_rule_decision": "D-221",
       "applies_to": [
@@ -1929,3 +1945,3 @@
     "living_runway_policy_d145": {
-      "decision": "D-220",
+      "decision": "D-221",
       "purpose": "The 2026-08-real TWD 2,250,000 three-year living reserve is daily elapsed-time indexed for live nominal checks and replenished in a qualified bull market. In a qualified bear market, up to the daily elapsed-time indexed 2026-08-real TWD 1,500,000 may be used for low buys, but its daily elapsed-time indexed 2026-08-real TWD 750,000 one-year floor is never crossed.",
@@ -2281,3 +2297,3 @@
     "emergency_reserve_policy_d193": {
-      "decision": "D-220",
+      "decision": "D-221",
       "amount_basis": "2026_08_REAL_TWD_ELAPSED_DAY_INDEXED_FOR_LIVE_GATES",
@@ -2308,3 +2324,3 @@
       "annual_review_reserve_maintenance": {
-        "decision": "D-220",
+        "decision": "D-221",
         "required": true,
@@ -2598,3 +2614,3 @@
           "source_document": "research/ETF_UNIVERSE.md",
-          "source_sha256": "b164af92335d285f44f0b2e1c203bf7e92dcf88919067fb4e673650a992340bc"
+          "source_sha256": "93194e593b8c7192cb9fd31fd283ef5c09008553f9df05ae7e304acad34e01b3"
         },
@@ -2606,3 +2622,3 @@
           "source_document": "research/ETF_UNIVERSE.md",
-          "source_sha256": "b164af92335d285f44f0b2e1c203bf7e92dcf88919067fb4e673650a992340bc"
+          "source_sha256": "93194e593b8c7192cb9fd31fd283ef5c09008553f9df05ae7e304acad34e01b3"
         },
@@ -2614,3 +2630,3 @@
           "source_document": "research/ETF_UNIVERSE.md",
-          "source_sha256": "b164af92335d285f44f0b2e1c203bf7e92dcf88919067fb4e673650a992340bc"
+          "source_sha256": "93194e593b8c7192cb9fd31fd283ef5c09008553f9df05ae7e304acad34e01b3"
         },
@@ -2622,3 +2638,3 @@
           "source_document": "research/ETF_UNIVERSE.md",
-          "source_sha256": "b164af92335d285f44f0b2e1c203bf7e92dcf88919067fb4e673650a992340bc"
+          "source_sha256": "93194e593b8c7192cb9fd31fd283ef5c09008553f9df05ae7e304acad34e01b3"
         },
@@ -2630,3 +2646,3 @@
           "source_document": "research/ETF_UNIVERSE.md",
-          "source_sha256": "b164af92335d285f44f0b2e1c203bf7e92dcf88919067fb4e673650a992340bc"
+          "source_sha256": "93194e593b8c7192cb9fd31fd283ef5c09008553f9df05ae7e304acad34e01b3"
         },
@@ -2638,3 +2654,3 @@
           "source_document": "research/ETF_UNIVERSE.md",
-          "source_sha256": "b164af92335d285f44f0b2e1c203bf7e92dcf88919067fb4e673650a992340bc"
+          "source_sha256": "93194e593b8c7192cb9fd31fd283ef5c09008553f9df05ae7e304acad34e01b3"
         },
@@ -2646,3 +2662,3 @@
           "source_document": "research/ETF_UNIVERSE.md",
-          "source_sha256": "b164af92335d285f44f0b2e1c203bf7e92dcf88919067fb4e673650a992340bc"
+          "source_sha256": "93194e593b8c7192cb9fd31fd283ef5c09008553f9df05ae7e304acad34e01b3"
         },
@@ -2654,3 +2670,3 @@
           "source_document": "research/ETF_UNIVERSE.md",
-          "source_sha256": "b164af92335d285f44f0b2e1c203bf7e92dcf88919067fb4e673650a992340bc"
+          "source_sha256": "93194e593b8c7192cb9fd31fd283ef5c09008553f9df05ae7e304acad34e01b3"
         },
@@ -2662,3 +2678,3 @@
           "source_document": "research/ETF_UNIVERSE.md",
-          "source_sha256": "b164af92335d285f44f0b2e1c203bf7e92dcf88919067fb4e673650a992340bc"
+          "source_sha256": "93194e593b8c7192cb9fd31fd283ef5c09008553f9df05ae7e304acad34e01b3"
         },
@@ -2670,3 +2686,3 @@
           "source_document": "research/ETF_UNIVERSE.md",
-          "source_sha256": "b164af92335d285f44f0b2e1c203bf7e92dcf88919067fb4e673650a992340bc"
+          "source_sha256": "93194e593b8c7192cb9fd31fd283ef5c09008553f9df05ae7e304acad34e01b3"
         },
@@ -2678,3 +2694,3 @@
           "source_document": "research/ETF_UNIVERSE.md",
-          "source_sha256": "b164af92335d285f44f0b2e1c203bf7e92dcf88919067fb4e673650a992340bc"
```
