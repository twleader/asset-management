# [t576] 交易雷達轉折判定改讀同一完成日 KD（TW_RULES_V24）

**對應 Requirements:** Requirement 201（分批試單與主升段結束判定所用的 KD、前一期 KD、完成日漲跌必須來自同一根已完成日 K，不混用盤中 K）
**前置任務:** t575（TW_RULES_V23 高賣低買）、t504（SRPP Swagger 身分受控發布鏈）
**Liquibase changeset:** 無

## 背景

2026-11-13 使用者以 `/codex-review` 要求檢核雷達能否增加獲利。Codex（gpt-6.1-sol／high）第 1 輪唯一「必須修」：盤中時 `TradingRadarService.prepareTechnicalBasis()` 把即時價列放在序列第 0 筆（`liveAdded=true`），`RadarInputAssembler.calculate()` 以含即時列的序列算 `indicators().k()/d()`，於是 `previousK/previousD` 變成前一完成日；但 `completedChangePercent` 只用完成日收盤。`TradingRadarRuleEngine` 的 `qualifiesForReboundBuy()`、`qualifiesForTrialBuy()`、`uptrendEndConfirmed()` 把「盤中 K 對昨日 K」與「昨日漲跌」混在同一判定，造成訊號在盤中隨價格忽隱忽現。

反例：完成日漲 +0.98%、完成日 K/D=43.89/44.63（未交叉）；盤中價上升使即時 K/D=45.93/45.06；52 週位置 0.136、其他否決不成立 → 輸出 `TRIAL_BUY`，盤中回落後又消失。

**正確行為：** 三個轉折判定的 KD 證據一律讀「完成日 KD 快照」（最終動作仍受既有盤中條件如 KD 過熱／時機狀態影響，本任務不改那些條件）——最近一根完成日 K 的 K、D 與其前一根完成日 K 的 K、D。收盤後（無盤中列）結果與 V23 完全相同。

同日驗證結論（只作背景，不在本任務實作）：引擎回測與蒙地卡羅（37 檔、每檔 200 條區塊自助抽樣路徑）顯示既有訊號扣除曝險後沒有顯著超額；本修正是時點一致性修正，不宣稱提升獲利。

## 要做什麼

- [x] 576.1 `backend/src/main/java/com/steven/assets/service/TradingRadarRuleEngine.java` 新增巢狀不可變 `public record CompletedKd(BigDecimal k, BigDecimal d, BigDecimal previousK, BigDecimal previousD)`，Javadoc：「最近一根完成日 K 與其前一根完成日 K 的 KD；排除盤中列」。
- [x] 576.2 `StockInput` 正典建構式（record 元件）最後新增 `CompletedKd completedKd`（現行 28 個元件、最後為 `BollingerInput bollinger`，新增後為 29 個）。**另新增一個保留舊 28 參數形狀的相容建構式**（`TradingRadarBollingerCalculationTest` 等既有測試直接呼叫此形狀），與**所有既有相容建構式**（第 713／757／775／792 行附近的四個）一樣保持原參數，內部以 `new CompletedKd(indicators == null ? null : indicators.k(), indicators == null ? null : indicators.d(), previousK, previousD)` 推得——相容建構式的語意是「輸入本身就是完成日序列」（回測與單元測試），Javadoc 明載。不得讓相容建構式傳 null 給 completedKd。
- [x] 576.3 `RadarInputAssembler.Assembled` 新增元件 `TradingRadarRuleEngine.CompletedKd completedKd`（放最後；同步更新 `Assembled.EMPTY` 與所有建構點）。`calculate(...)` 內：`firstCompleted == 0`（無盤中列）時取 `indicators` 的 k／d／previousK／previousD；`firstCompleted == 1`（有盤中列）時以 `adjustedRows.subList(firstCompleted, Math.min(adjustedRows.size(), firstCompleted + prepared.indicatorRows() - 1))`（排除盤中列、長度不超過完成日契約）呼叫同一 `indicatorService.computeFromSeries(...)`，取其 k／d／previousK／previousD。`cachedIndicators` 非 null 的快取路徑也一樣由完成日序列本地計算 completedKd，不讀盤中值。計算失敗或欄位缺值時 completedKd 的對應欄位為 null。
- [x] 576.4 `TradingRadarService` 建構 `StockInput` 的唯一呼叫點（約 1716 行）改用正典建構式，顯式傳入 `technical.completedKd()`。`BacktestService` 兩個建構點（約 852、3084 行）改用正典建構式，顯式傳入由其完成日 indicators 推得的 `CompletedKd`（回測序列本就不含盤中列，值等同相容推導）。以 `grep -ran "new TradingRadarRuleEngine.StockInput(\|new StockInput(" backend/src/main/java` 確認 production 沒有任何呼叫走相容建構式。
- [x] 576.5 引擎判定改讀快照（新增私有 helper，例如 `completedK(input)`、`completedD(input)`、`completedPreviousK(input)`、`completedPreviousD(input)`、`completedKdDeadCross(input)`）：
  - `qualifiesForTrialBuy`：條件 3（K<20 且 D<20）、條件 4（K>D、前一期 K≤D）改用快照四值；任一 null → false。
  - `qualifiesForReboundBuy`：`K > previousK` 且 `K > D` 改用快照；任一 null → false。
  - `uptrendEndConfirmed`：`K ≤ D` 必要條件與 dead cross（前一期 K>D、本期 K≤D）改用快照；任一 null → false。其他三項證據（osc、MA20 BELOW、下跌爆量）不變。
  - **不改**：`timingOf()`、`kdHeatOf()`、`kdOversold()`、`profitTakingConfirmed()`（含其內部 `kdDeadCross(input)`）、分數因子、`buyGate`、`narrowKdBand`、所有 DTO 的 K/D 揭露欄；門檻、權重、V23 動作順序與文案不變。
- [x] 576.6 版號：`RULE_VERSION` = `"TW_RULES_V24"`（類別 Javadoc 版本歷程補 V24 一行）；`FubonRadarCompatibilityManifest.DECISION_INPUT_VERSION` = `"TW_RULES_V24|" + TECHNICAL_SOURCE_VERSION`；`TradingRadarService` 註解、DTO Javadoc 的現行版號同步；`docs/openapi/docker-external-api.yaml` 所有 `TW_RULES_V23` 改 `TW_RULES_V24`、`info.version` 1.27.1 → 1.27.2；`ruby scripts/render-9090-openapi-docs.rb` 重產兩份專案內鏡像並 `--check`；`scripts/tests/docker-external-api-openapi-test.rb` 與 `scripts/tests/unified-9090-openapi-renderer-test.rb` 中斷言 1.27.1 之處改 1.27.2；後端與 BFF 測試／fixtures 的 `TW_RULES_V23` 改 V24（以 `grep -ran TW_RULES_V23 backend/src bff/src frontend/src docs/openapi scripts/tests` 清零為準；`TradingRadarNotificationServiceTest.PREVIOUS_RULE_VERSION` 不動）。
- [x] 576.7 測試（加在既有 `TradingRadarRuleEngineTest` 與 `RadarInputAssembler` 的既有測試類；以 `grep -rln "class RadarInputAssembler" backend/src/test` 找到對應測試類，沒有時加在 `TradingRadarRuleEngineTest`）：
  1. Codex 反例：即時 K/D=45.93/45.06、前一期 43.89/44.63，完成日快照 K/D=43.89/44.63、前一期 K/D 為 K≤D 的值，跌深成立、完成日 +0.98% → **非** `TRIAL_BUY`。
  2. 同上但完成日快照本身 K>D 且 K 上升、其他閘門（含由盤中 KD 決定的 `timingOf()`／`kdHeatOf()`）保持不否決 → `TRIAL_BUY`，即使即時 K≤D。
  2b. 完成日快照成立，但盤中 KD 使時機變為 `OVERBOUGHT`（例：完成日 K/D=79/77、前一期 78/76、52 週位置 0.2；盤中 K/D=86/80）→ 依既有規則仍被否決，非 `TRIAL_BUY`（本任務不改盤中否決條件）。
  3. 長線 `qualifiesForTrialBuy` 同樣反例（即時交叉、完成日未交叉）→ 不走長線試單。
  4. 賣出閘門：高檔區、即時 K≤D 且即時死叉，但完成日快照 K>D → 不得 `REDUCE_CANDIDATE`／`EXIT_CANDIDATE`；反之完成日 K≤D＋完成日死叉＋MA20 BELOW → 可減碼（即使即時 K>D）。
  5. 快照任一欄 null → 不試單、不放行賣出（持有者分數落減碼區時為 `HOLD_CAUTION`）。
  6. 相容建構式：未傳快照時由 indicators／previousK/D 推得，V23 既有測試結果全部不變。
  7. `RadarInputAssembler`：`liveAdded=false` 時 `completedKd` 等於 `indicators` 的四值；`liveAdded=true` 時等於只用完成日列算出的四值，且不等於含盤中列的值（以會改變 K 的盤中價構造）。
  8. 規則版號 V24 與通知升版首輪不寄信（沿用既有測試改期望）。
- [x] 576.8 不新增 API／路由／DB／排程／券商 I/O；輸出只呈現建議，不下單。
- [ ] 576.9 SRPP Swagger 身分同步與 registry 產物（Task 504 受控鏈）。`<SRPP_WORKTREE>` 為由 `/Users/steven/Project/SRPP` 乾淨 main 建立的受控 feature worktree（絕對路徑、非 symlink、非 main、乾淨）。在 asset-management worktree 依下列**固定順序**執行，每步核對退出碼，第一個失敗即停止查因：
  1. `ruby scripts/render-9090-openapi-docs.rb --srpp-root <SRPP_WORKTREE>`
  2. `python3 scripts/sync-srpp-swagger-identity.py --srpp-root <SRPP_WORKTREE>`（更新 SRPP 的 consumer schema、runtime contract、policy manifest 與 backend `srpp/decision/policy_manifest.json`）
  3. `ruby scripts/render-9090-openapi-docs.rb --check --srpp-root <SRPP_WORKTREE>`、`python3 scripts/sync-srpp-swagger-identity.py --check --srpp-root <SRPP_WORKTREE>`
  4. 在 `<SRPP_WORKTREE>`：`python3 scripts/policy_bundle.py check --purpose daily --consumer Codex`、`python3 scripts/daily_runtime_contract.py check`，讀回 manifest SHA-256，並記錄新 policy bundle SHA-256。SRPP 端只允許 `docs/9090 Port API Swagger.md`、`assumptions/srpp_report_calculations_schema.json`、`assumptions/daily_runtime_contract.json`、`assumptions/policy_manifest.json` 四個產物變動。
  5. `ruby scripts/srpp-report-policy-register.rb`（無參數，讀第 2 步已更新的 backend embedded manifest）完整 stdout 先寫到 scratchpad 暫存檔、確認非空，再移入受追蹤的 `backend/src/main/resources/srpp/report/d201-registration.sql`；僅準備 SQL、不執行。
  6. （generator 需讀 gitignored 的 `data/input/交易日曆_2026.json`／`交易日曆_2027.json`：先由 `/Users/steven/Project/SRPP/data/input/` 複製到 `<SRPP_WORKTREE>/data/input/`，不入版控。）以原 generator 重產 bundle 綁定的 golden：`python3 -B scripts/generate-srpp-report-golden.py --srpp-root <SRPP_WORKTREE>` 與 `python3 -B scripts/generate-srpp-long-short-exit-golden.py --srpp-root <SRPP_WORKTREE>`；`scripts/generate-srpp-state-topup-golden.py` 以 `--check` 確認是否需重產，需要才重產。以 `python3 -m json.tool --sort-keys` 對 HEAD 結構化比對，允許差異**僅限** golden 的 `policyBundleSha256`；任何 case 的 input／expected 或 calculator hash 改變即停止查因（純 Swagger 身分更新不得改變金融輸出），不手改 expected、不改 generator 或 Java 計算。
  7. 之後才跑 `python3 -B scripts/check-srpp-report-authority.py --srpp-root <SRPP_WORKTREE> --check`、三支 generator 的 `--check`、`ruby scripts/tests/srpp-report-policy-register-test.rb`、`SRPP_RELEASE_WORKTREE=<SRPP_WORKTREE> python3 -B scripts/tests/check-srpp-report-authority-test.py`。
  8. 兩 repo 各自 feature commit、main `--no-ff` merge、push、`git ls-remote` 讀回；為避免影響當日日報，推送、登錄與部署於避開日報與匯出排程分鐘進行（使用者已明示今晚即可發布；重啟前唯讀確認今天已過時點的匯出排程皆已執行、未到者不會被補跑）。之後才以已合併的受追蹤 SQL 做 insert-only registry 登錄（`docker exec -i asset-postgres psql -X -q -v ON_ERROR_STOP=1`，stdin 為 `BEGIN;`＋原 SQL bytes＋`COMMIT;`，前後讀回舊 rows 全文不變、新 row 四欄與 generator 一致；同 hash 已存在先全文比對），再從乾淨 asset-management main 依 run-stack 重建 business-services、restart bff，確認運行 JAR 內 `srpp/9090-api-swagger.md`、`srpp/decision/policy_manifest.json`、`srpp/report/d201-registration.sql` 與合併來源逐位元相同。`report-calculations` 正式採用待下一合法時窗自然實跑，維護時記 `NOT_YET_EXECUTED`，不手動觸發 producer、POST observer、重跑報告、寄信或交易。


## 驗證

```bash
bash scripts/spec-check.sh
grep -ran "TW_RULES_V23" backend/src bff/src frontend/src docs/openapi scripts/tests   # 應無輸出
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -DextraArgLine=-Dnet.bytebuddy.experimental=true -Dtest='*Radar*,*Backtest*,*RuleParameters*,*OpenApi*,*TreasuryYield*,*Assembler*' test
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f bff/pom.xml -DextraArgLine=-Dnet.bytebuddy.experimental=true -Dtest='*TradingRadar*' test
ruby scripts/render-9090-openapi-docs.rb --check
ruby scripts/tests/docker-external-api-openapi-test.rb
ruby scripts/tests/unified-9090-openapi-renderer-test.rb
```

依 `/run-stack` 從乾淨 main `--no-cache` 重建 business-services，`unzip -p` 確認 jar 含 `TW_RULES_V24`、三個身分檔與 main 逐位元相同；重取 `GET /api/public/trading-radar/today` 確認 `ruleVersion=TW_RULES_V24`，前後動作分布記入完成報告（收盤後重放應與 V23 相同）。

## 完成報告

**範圍：** 576.1～576.8 已完成；576.9（SRPP 身分鏈、registry SQL、golden、commit／merge／push、部署）**未執行**，留待受控發布。驗證段的 `/run-stack` 重建與 `GET /api/public/trading-radar/today` 動作分布亦尚未執行。

**修改檔案（production）：**
- `backend/src/main/java/com/steven/assets/service/TradingRadarRuleEngine.java`：新增 `CompletedKd` record；`StockInput` 正典改為 29 元件（末元件 `completedKd`），新增 28 參數相容建構式並把既有 27 參數（無 Bollinger）相容建構式改為直接推導快照；其餘三個舊相容建構式經鏈結同樣推導，均不傳 null 快照。新增 `completedK/D/PreviousK/PreviousD/completedKdDeadCross` 私有 helper；`qualifiesForTrialBuy`（條件 3、4）、`qualifiesForReboundBuy`（K 上升且 K>D）、`uptrendEndConfirmed`（K≤D 與死叉）改讀快照、四值任一 null → false。`timingOf`／`kdHeatOf`／`kdOversold`／`profitTakingConfirmed`（含 `kdDeadCross`）／分數／`buyGate`／`narrowKdBand`／DTO K/D 未動。`RULE_VERSION=TW_RULES_V24`，類別 Javadoc 補 V24。
- `backend/src/main/java/com/steven/assets/service/RadarInputAssembler.java`：`Assembled` 末端新增 `completedKd`；新增 25 參數（含 Bollinger）相容建構式，24／23 參數相容形狀改為由 `indicators` 推導；`EMPTY` 帶四欄 null 快照。`calculate()` 新增 `completedKd(prepared, indicators)`：`firstCompleted==0` 取 `indicators` 四值（含 Task408 快取命中）；`firstCompleted==1` 不論是否快取，一律以 `adjustedRows.subList(1, min(size, 1+indicatorRows-1))` 呼叫同一 `computeFromSeries` 本地計算；失敗或缺值為 null。
- `TradingRadarService.java`：唯一建構點改走 29 參數正典並顯式傳 `technical.completedKd()`；版號註解更新。
- `BacktestService.java`：兩建構點（`evaluate`、`v13Input`）改走正典並顯式傳由完成日 `indicators` 推得的快照（私有 `completedKd(a)`）。
- `FubonRadarCompatibilityManifest.DECISION_INPUT_VERSION`、`TradingRadarDto` Javadoc：V24。
- `docs/openapi/docker-external-api.yaml`：`TW_RULES_V23`→`V24`、`info.version` 1.27.1→1.27.2；以 `ruby scripts/render-9090-openapi-docs.rb`（無 `--srpp-root`）重產 `docs/openapi/9090-api-swagger.md` 與 `backend/src/main/resources/srpp/9090-api-swagger.md`。

**修改檔案（測試）：**
- `TradingRadarRuleEngineTest`：新增 8 個 `v24_*` 測試，對應 576.7 第 1、2、2b、3、4、5、6、8 項（反例同時斷言 V23 相容推導會輸出 `TRIAL_BUY` 以證明缺陷存在）。
- `RadarInputAssemblerWindowRegressionTest`：新增 3 個測試，對應 576.7 第 7 項（無盤中列＝`indicators` 四值；有盤中列＝只用完成日 241 列算出、等於收盤後同序列結果、不等於含盤中列值；盤中快取命中仍本地計算）。
- 版號字串 `TW_RULES_V23`→`V24`：`TradingRadarControllerCurrentTest`、`TradingRadarOpenApiSchemaContractTest`、`BacktestServiceTest`、`RadarTechnicalResolverFubonOverlayTest`、`RedisRadarTechnicalCacheRepositoryRedisIntegrationTest`、`TradingRadarNotificationServiceTest`（現行版號斷言；`PREVIOUS_RULE_VERSION` 未動）、`TradingRadarPanelServiceTest`、`TreasuryYieldServiceTest`、BFF `TradingRadarPanelFixtures`／`TradingRadarPanelServiceTest`；`scripts/tests/docker-external-api-openapi-test.rb`、`unified-9090-openapi-renderer-test.rb` 的 1.27.1→1.27.2。

**被改期望的測試與理由：** 只有上列版號字串（V23→V24、1.27.1→1.27.2）屬 576.6 的版號同步；**沒有任何既有測試的動作／分數期望被修改**，既有 V23 行為測試（經相容建構式）全數原樣通過，符合 576.7 第 6 項。

**驗證結果（2026-11-13，本 worktree）：**
- `grep -ran "TW_RULES_V23" backend/src bff/src frontend/src docs/openapi scripts/tests`：無輸出。
- backend `-Dtest='*Radar*,*Backtest*,*RuleParameters*,*OpenApi*,*TreasuryYield*,*Assembler*'`：exit 0，1247 tests、0 failure／error／skip。
- bff `-Dtest='*TradingRadar*'`：exit 0，151 tests、0 failure／error／skip。
- `ruby scripts/render-9090-openapi-docs.rb --check`、`scripts/tests/docker-external-api-openapi-test.rb`、`scripts/tests/unified-9090-openapi-renderer-test.rb`：全 PASS。
- `bash scripts/spec-check.sh`：BLOCK 0、CHECK 0。
- production `grep -ran "new TradingRadarRuleEngine.StockInput(\|new StockInput(" backend/src/main/java` 三處（`TradingRadarService:1716`、`BacktestService:852`、`:3094`）皆為 29 參數正典並顯式傳快照。

**偏差／詮釋：**
1. 576.3「快取路徑也一樣由完成日序列本地計算」詮釋為：有盤中列時不論快取一律本地計算；無盤中列時快取 `indicators` 本身即完成日值，直接取用而不重算，以保留 Task408 `RadarInputAssemblerLocalSnapshotReuseTest` 的「快取命中不呼叫 `computeFromSeries`」契約。
2. 「任一 null → false」對三個判定一律套用到快照四值（反彈試單原本不看 previousD、主升段結束原本只要 K/D），符合 576.7 第 5 項「快照任一欄 null」語意；經相容建構式的 V23 既有測試均未受影響。
3. `RadarInputAssembler` 本地計算以 `try/catch RuntimeException` 包住，失敗時快照四欄 null（576.3「計算失敗…為 null」）。
4. 新增 `Assembled` 25 參數相容建構式（原正典形狀），供可能的既有呼叫形狀相容；目前 production 只有 `calculate()` 與 `EMPTY` 兩個建構點。

### 576.9 SRPP 身分鏈（主 agent，2026-11-13 23:2x 台北）

- 審查：spec Codex 2 輪（第 1 輪 2 major 已修，第 2 輪 PASS）；`/codex-review` 獲利性審查第 2 輪 `VERDICT: PASS`（確認必須修已解決、無新缺陷）；架構 Codex PASS。
- SRPP 受控 feature worktree `/Users/steven/Project/SRPP/.claude/worktrees/radar-v24-swagger`（分支 `claude/radar-v24-swagger-identity`，自 SRPP main `235d4f0`）；交易日曆 gitignored 輸入由 SRPP 主工作區複製。
- renderer／sync／兩個 `--check` exit 0。新 `swaggerSha256=f2c43135c4b556d96f61e41a87d54dddf10ca6b80075bb1fa3f96282df802817`、contract 1.27.2；policy bundle `d601dfa7…` → **`bfc7a284bac29c447afc961f7d6da30f7efdbee84a83dfa39bc16cd6141e93a7`**；SRPP manifest 檔案 SHA-256 `89bf8d986b347eace2c0d2e39c165bd20dbadf85949227b335d51864a3a5991a`；SRPP 端只變動四個固定產物。`policy_bundle.py check`（176 檔）與 `daily_runtime_contract.py check` PASS。
- `d201-registration.sql` 重產（差異僅 bundle hash）；report-same-input 與 long-short-exit golden 重產，結構化差異僅 `policyBundleSha256`；state-topup `--check` 通過。authority gate、generator `--check`、Ruby／Python 回歸皆 exit 0；後端 SRPP＋雷達＋Assembler＋OpenAPI＋Swagger 測試 1247 個 0 失敗，`package` exit 0。
- 雙 repo 落地：SRPP feature `2b76e87` → main merge `79ac972`（feature 分支亦被推上 origin，未刪）；asset-management feature `721968e` → main merge `1352239`；push 後 `ls-remote` 讀回一致。
- Registry：單一交易 insert-only 登錄，44 → 45 列、舊列不變；`bfc7a284…` 列的 formula／policy document／manifest 與 `d601dfa7…` 相同。
- 部署（23:24 台北）：重啟前唯讀確認所有已過時點的匯出排程皆已於今日執行、`DIVIDEND_BACKFILL_ENABLED=false`；從乾淨 main `1352239` `--no-cache` build 並 force-recreate business-services，11 秒 healthy，9090 恢復。image `sha256:749567c9…`；JAR 內三個身分檔與 main 逐位元相同，含 `TW_RULES_V24`。
- 雷達讀回 `ruleVersion=TW_RULES_V24`。與 16:00 的 V23 快照相比，中長期 TRIAL_BUY 1、HOLD_CAUTION 10→11、AVOID 2→1；差異集中在 AMZN／AMD／AVGO／TSM／00679B（美股盤中 23:24 行情與 16:00 不同所致，不能歸因於 V24），台股持股動作不變（收盤後 V24 與 V23 等價，符合預期）。
- SRPP 9090 實測：以 SRPP main（`79ac972`）為來源執行 `verify_9090.py` PASS（日曆、TWSE 指數、台股報價 200）。**日報工作區 `/Users/steven/Project/SRPP` 未能快轉**：另一個 session 於 23:07–23:17 在該工作區修改了 14 個受追蹤檔（含 `assumptions/policy_manifest.json`、`daily_runtime_contract.json`），`--ff-only` 因會覆蓋其修改而中止，工作區保持原狀（`235d4f0`、bundle `d601dfa7`）。須待該 session 落地後再同步，否則日報工作區的 Swagger 身分與正式服務不一致。`report-calculations` 正式採用：`NOT_YET_EXECUTED`。
