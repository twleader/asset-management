# Task 597 spec 審查材料

## 目標
同步 SRPP D221（bundle 727be98b…）至資產端 authority；不改 9090 計算器、wire、DB。D221 於 SRPP 本機計算半導體龍頭事件下限與 D204 SOX 連動。

## spec-check
變更檔數: 6（含未追蹤新檔）

PASS: 9090 OpenAPI Markdown mirrors are byte-identical and current
PASS: 9090 gateway/OpenAPI 二十七條 parity、response manifest、parameters、examples、strict schemas 與 generated docs 完整
PASS: AI 觀測 OpenAPI Markdown 與來源一致；未寫入任何金融或 SRPP 檔案
PASS: 金融27+觀測1獨立契約/union28/strict schemas/US雙向identity條件/統一文件發布身份
PASS: db/schema.sql（去除專案檔頭後）逐位元等於 asset-postgres 此刻的 pg_dump 輸出
      表數：123 張（檔頭宣告一致）

════ 結果 ════
BLOCK: 0   CHECK: 0
→ 機械檢查通過（不代表 spec 正確，仍須跑對抗式審查）。

## diff
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
index 86138ca1..3e92fbcc 100644
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
+- [ ] 211.3 以既有 SRPP `export_settings_runtime.py` 同步 `srpp-simulator/runtime` 23 檔與 runtime-manifest，`--check` 通過；不手抄。
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
```

## 新檔 spec/tasks/t597_srpp_d221_leader_shock_authority.md
```
# [t597] D221 半導體龍頭事件下限與 D204 費半連動的 SRPP 權威同步

**對應 Requirements:** Requirement 211
**前置任務:** Task 591（D220 權威同步）
**Liquibase changeset:** 無

## 背景

2026-11-13 黃仁勳請辭，SRPP 兩軌讀到官方公告與大量許可新聞，卻因半導體分級只認週期／競爭／展望／製程／市占事件而記 0～1 分；D204 美股→台股只看 IXIC（-0.91%），未看同日 SOX -3.43%。使用者於 2026-11-16 核准 SRPP D221 第一～三節：半導體類別新增龍頭重大事件下限（十家固定名單、三種事件、5 個台股交易日窗口，公告後 SOX 任一完成日 ≤ -3% 或連兩日累計 ≤ -5% 升至 2 分），D204 美股→台股改評 IXIC 與 SOX 取較低分。六項類別、上限、5／8／3／4 門檻與交易規則不變。最終 SRPP bundle `727be98b36a55a1127af02f4002c6797ab12cae56e603d466e7060e6f7d62d27`（SRPP feature `claude/risk-assessment-gap-97ab3c` commit `dd59b1c`）。

## 要做什麼

1. 精確複製最終 SRPP `assumptions/model_inputs.json` 與 `assumptions/policy_manifest.json` 原 bytes 至 `backend/src/main/resources/srpp/decision/`。
2. 以 `check-srpp-report-authority.py` 的 `expected_formal_document` 驗證 `d201-policy-document.json`（預期不變，因配置目標未改）；以 `ruby scripts/srpp-report-policy-register.rb` 原 stdout 重產 `d201-registration.sql`（先寫暫存、非空才移入）。
3. 依序以原 generator 重產 `report-same-input-golden.json`、`report-long-short-exit-golden.json`、`report-state-topup-golden.json`、short-us golden；預期只有 `policyBundleSha256` 改變，案例數與 `originalCalculatorHashes` 不變（`cross_market_context.py`／`leader_shock.py` 不在 9090 計算器集合）。
4. 以 SRPP `scripts/export_settings_runtime.py --destination <asset>/srpp-simulator/runtime` 匯出並 `--check`；`generate-srpp-formula-v3.py --srpp-root <SRPP> --check` 必須維持 PASS。
5. 確認 `SRPP_RISK_RUBRIC_V2` rubricSha256 與發布前相同（`5ca0f8fd3b82efd5c91e2f40dbe56045c9909ecc4cd966d073d932203bdb1c0b`）。
6. 執行相關 Java 離線回歸；若斷言綁定舊 decision／version，只改為同版來源取值，不改正式演算法。
7. 不新增 HTTP／DTO／DB／行情／排程／報告／交易／郵件。發布依 CLAUDE.md Task 504 鏈：雙 repo commit／no-ff merge／push 與 remote 回讀 → insert-only 登錄並回讀精確 row → 從乾淨 main 重建 business-services、srpp-simulator → 回讀 JAR authority 與 runtime bytes → SRPP host 三 GET；report-calculations 正式採用待合法時窗，否則 NOT_YET_EXECUTED。

- [ ] 597.1 spec 與獨立 Codex 審查。
- [ ] 597.2 authority artifacts、golden、runtime export 精確同步。
- [ ] 597.3 authority check、formula V3 check、rubric hash 與 Java 離線回歸。
- [ ] 597.4 獨立 Codex 架構審查、落地與發布驗收。

## 驗證

`scripts/spec-check.sh`；`check-srpp-report-authority.py --srpp-root <SRPP feature> --check`；四個 golden generator 結果；`export_settings_runtime.py --check`；`generate-srpp-formula-v3.py --check`；`mvn -o -Dtest='SrppReport*Test,SrppSettings*Test,DecisionPolicyAuthorityTest' test`。金融採用（report-calculations 200）只在合法交易日時窗驗收。

## 完成報告

待驗收回填。
```

## SRPP 來源 D221 決策紀錄（節錄）
## D-221：半導體龍頭事件下限與D-204美股連動加看費半（2026-11-16 Accepted）

Steven於2026-11-16 Accept提案第一～三節（`review/D221_PROPOSAL.md`），第四節「龍頭事件觸發3分／防禦」不採。起因：黃仁勳2026-11-13請辭，兩軌11/13～11/16皆讀到官方公告與大量許可新聞，卻因半導體分級只認週期、競爭、展望、製程與市占事件而記0～1分；D-204只看IXIC（11/13 -0.91%），未看同日SOX -3.43%。

- 六項風險的半導體類別新增機械下限：十家固定名單、三種事件、5個台股交易日窗口（台股13:30收盤後公告順延一日）；窗口內記至少1分，公告後SOX任一完成日≤-3%或連兩日累計≤-5%記至少2分；由`scripts/leader_shock.py`計算，最終取一般判讀與下限較高者。只採事件本身，不放寬D-168「標題不得推論影響」。
- D-204美股→台股同時評IXIC與SOX，取較低分；缺一具名揭露；D-216急跌警示仍只看IXIC；仍為`ADVISORY_ONLY`。
- 不變：六項類別、上限、`SRPP_RISK_RUBRIC_V2`雜湊、5／8／3／4門檻、所有配置、交易閘門與D-207規則。以11/16資料試算兩軌總分皆為7，仍屬審慎，不改變當日任何買賣結論。
- 修改：`assumptions/model_inputs.json`、`scripts/leader_shock.py`（新）、`scripts/test_leader_shock.py`（新）、`scripts/cross_market_context.py`、`scripts/test_cross_market_context.py`、`scripts/policy_bundle.py`、`scripts/daily_runtime_contract.py`、`scripts/README.md`、`docs/DAILY_TRADING_AUTOMATION.md`、`docs/SHARED_RULES.md`、三份排程入口，及各文件版本標頭；政策v2.69.0，模型v3.24.0不變。不下單、不寄信。

## D-220：VOO轉配台股高股息與退休配置稽核（2026-11-14）

## 離線 dry-run 證據
- authority check PASS:727be98b…；golden 111/15/22/13 cases，僅 policyBundleSha256 一行改變；formula V3 check PASS 452d4260…；runtime export 23 檔；rubricSha256 前後皆 5ca0f8fd…；mvn SrppReport*/SrppSettings*/DecisionPolicyAuthorityTest 25 類 0 失敗、無需改 Java。
