# [t575] 交易雷達「高賣低買」：賣出限主升段結束、買進限跌深反彈（TW_RULES_V23）

**對應 Requirements:** Requirement 200（雷達減碼／出場只在仍處高檔且轉弱確認時輸出；跌深後出現反彈時輸出分批試單候選；不得在回檔低點殺低）
**前置任務:** t477（買進閘門 15／8）、t487（TW_RULES_V22）、t504（SRPP Swagger 身分受控發布鏈）
**Liquibase changeset:** 無

## 背景

使用者原則：「逢高點要求賣出，低點要求買進；賣出建議是在主升段結束後，買進建議也是在跌深、有反彈機會產生之後。」

**現在的錯誤行為（2026-11-13 以 `GET http://127.0.0.1:9090/api/public/trading-radar/today` 實測，`TW_RULES_V22`）：**
台股完成週跌 12.19% 後，持有中的 0050、006208、0056、00878、00881、009804、00919、VOO、VT 被判 `REDUCE_CANDIDATE`（減碼候選）。這些標的季線乖離 −9.5%～+0.1%、年線之上 2.7%～23.7%、K 已回升且 K>D（例：0050 K 71.25／D 52.84、week52Position 0.760、MA20／MA60 兩日 BELOW、osc 0.01、分數 36），屬回檔反彈中而非破底；全場 36 檔沒有任何買進／加碼／試單候選。

**根因（`backend/src/main/java/com/steven/assets/service/TradingRadarRuleEngine.java` 的 `actionFor()`）：**
1. 分數是順勢分數：價格在均線下、兩日確認 BELOW、MACD 負都扣分，分數 <40（`thresholds.reduce()`／`caution()`）就映射成 `REDUCE_CANDIDATE`／`EXIT_CANDIDATE`。
2. 「不殺低」保護只在 `TimingState.EXTREME_OVERSOLD`（`kdOversold()` 且季線乖離 ≤ −20% 或自身分位 ≤ 2）才生效；一般回檔完全不受保護。
3. 低檔買進只有 `qualifiesForTrialBuy()`：年線之上乖離 ≥5%、年線兩日 ABOVE、K<20 且 D<20、黃金交叉、完成日不跌——極窄。

**正確行為：** 賣出只在「仍在高檔區＋轉弱確認」時給；跌離高檔後不再給減碼（只降為續抱提高警戒）；跌深後出現反彈時給分批試單候選。本次為使用者明定的投資原則（風險偏好），**非 walk-forward 實證**。

## 要做什麼

所有修改都在 `TradingRadarRuleEngine.actionFor()` 及其新增私有 helper（helper 為同一實作、依 `actionFor()` 收到的該軌 `timing` 逐軌求值；candidate 路徑 SWING 軌傳入的是 baseline timing，照現行呼叫端傳入即可，不改 `evaluateHorizon()` 簽章）；**不得新增 `StockInput` 欄位**（record 相容建構式會讓新欄位靜默為 null），只讀既有欄位：`held()`、`price()`、`completedChangePercent()`、`indicators().k()/d()`、`previousK()`、`previousD()`、`ma20Confirmation()`、`ma240Confirmation()`、`instrumentType()`、`marketRegime()`、`marketStale()`、`ma60BiasPercent()`、`week52Position()`、`etfPremiumPct()`、`extendedIndicators().osc()`、`volumeRatio()`。

- [x] 575.1 新增具名常數（Javadoc 註明「使用者原則，無回測依據，不得宣稱降低風險或提升報酬」）：
  - `SELL_HIGH_ZONE_W52 = 0.75`（52 週相對位置，0..1）
  - `REBOUND_DEEP_BIAS_PCT = -8.0`（季線乖離，單位 %）
  - `REBOUND_DEEP_W52 = 0.25`
  - `UPTREND_END_DOWN_VOLUME_RATIO = 1.2`（沿用既有 `profitTakingConfirmed()` 下跌爆量的 1.2）
- [x] 575.2 新增 `private boolean highZone(StockInput input, TimingState timing)`：`week52Position != null && week52Position ≥ 0.75`，或 `timing == OVERBOUGHT || timing == EXTREME_OVERBOUGHT`。null 不算高檔。
- [x] 575.3 新增 `private boolean uptrendEndConfirmed(StockInput input)`：K、D 任一 null → false；**必要條件 `K ≤ D`**；且下列四項至少兩項成立：
  1. `kdDeadCross(input)`（既有方法，前一期 K>D、本期 K≤D）
  2. `extendedIndicators() != null && osc() != null && osc().signum() < 0`
  3. `ma20Confirmation() == Confirmation.BELOW`
  4. `completedChangePercent() != null && completedChangePercent().signum() < 0 && volumeRatio() != null && volumeRatio() ≥ 1.2`
- [x] 575.4 新增 `private boolean deepDrop(StockInput input, TimingState timing)`：`ma60BiasPercent ≤ −8.0`，或 `week52Position ≤ 0.25`，或 `timing == OVERSOLD || timing == EXTREME_OVERSOLD`（null 欄位該項不成立）。
- [x] 575.5 新增 `private boolean qualifiesForReboundBuy(StockInput input, TimingState timing)`，下列全部成立才 true：
  - `deepDrop(input, timing)`
  - `completedChangePercent() != null && completedChangePercent().signum() > 0`（**只用最近完成日 K**，不可用盤中 `changePercent()`）
  - K、D、previousK 皆非 null，且 `K > previousK` 且 `K > D`
  - `!narrowKdBand(input)`、`!fundamentalDeteriorating(input)`
  - `etfPremiumPct() == null || etfPremiumPct() < 3.0`（沿用 `ETF_PREMIUM_EXPENSIVE`）
  - `!equityMarketApplies(input) || !input.marketStale()`
  - `timing != OVERBOUGHT && timing != EXTREME_OVERBOUGHT`
  - 大盤 `RISK_OFF` **不否決**。
- [x] 575.6 `actionFor()` 順序改為：
  1. 既有 `qualifiesForTrialBuy(input)` → `TRIAL_BUY`（不動）
  2. **新**：`qualifiesForReboundBuy(input, timing)` → `TRIAL_BUY`。reasons 加一條「已跌深（<命中條件：季線乖離 X%／52 週低檔 Y／時機超賣>）且出現反彈（完成日收紅、K 上升且 K>D），列為分批試單候選。 」；risks 加「跌深反彈試單本質是接刀，僅適合小額分批；反彈可能失敗、低點可能再破。 」；股票類且 `marketRegime == RISK_OFF` 另加「大盤為 RISK_OFF，本訊號只限小額試單，不得視為一般買進或加碼候選。 」；`ma240Confirmation == BELOW` 另加「年線已兩日跌破，長期結構偏弱，試單應更小額。 」。文案不得寫門檻數字以外的承諾，數值用既有 `fmt1()`。
  3. 既有 `profitTaking` 分支改為：`profitTaking && uptrendEndConfirmed(input)` 才沿用原 reasons／risks 並回 `REDUCE_CANDIDATE`（持有）／`AVOID`（未持有）；`profitTaking` 成立但 `uptrendEndConfirmed` 不成立（例如 K>D）時，reasons 加「已達極端高檔且有轉弱跡象，但短線仍呈 K>D，尚未確認主升段結束，暫不建議減碼。 」後**繼續往下**走一般流程（`profitTakingConfirmed()` 本身的計算與 DTO 欄位 `profitTakingConfirmed` 語意不變）。
  4. 既有 `buyGate` 與分數 → BUY/ADD、HOLD/WATCH、HOLD_CAUTION/WAIT（不動）
  5. 分數映射得 `mapped`（不動）；既有 `EXTREME_OVERSOLD` 分支（文案與回傳 `HOLD_CAUTION`／`WAIT` 不動）——它是**獨立且優先**的保護，不讀 `week52Position`，可能與高檔區重疊，不得移除或併入新閘門
  6. **新賣出閘門**：若 `mapped` 為 `REDUCE_CANDIDATE` 或 `EXIT_CANDIDATE`（必為持有者）且 `!(highZone && uptrendEndConfirmed)` → reasons 加「尚未符合『主升段結束』條件（需仍在高檔區且轉弱確認、K≤D），依高賣低買原則不在回檔位置建議減碼。 」，回 `HOLD_CAUTION`；`ma240Confirmation == BELOW` 時 risks 另加「年線已兩日跌破，長期結構轉弱；本雷達依高賣低買原則不在低檔給減碼，是否停損請自行評估。 」。
  7. 若 `mapped == AVOID`（未持有）且 `deepDrop` → 回 `WAIT`；否則回 `mapped`。
- [x] 575.7 `RULE_VERSION` 改為 `"TW_RULES_V23"`，類別 Javadoc 版本歷程補一行 V23 摘要；`FubonRadarCompatibilityManifest.DECISION_INPUT_VERSION` 改為 `"TW_RULES_V23|" + TECHNICAL_SOURCE_VERSION`；`TradingRadarService` 中描述「現行 production 版號為 TW_RULES_V22」的註解改 V23；DTO Javadoc 中的現行版號字串同步。
- [x] 575.8 OpenAPI：`docs/openapi/docker-external-api.yaml` 所有 `TW_RULES_V22`（ruleVersion 範例、`decisionInputVersion` 描述與範例）改 `TW_RULES_V23`，`info.version` 1.27.0 → 1.27.1；只改這些字串，路由、schema 形狀不變。以 `ruby scripts/render-9090-openapi-docs.rb` 重產 `docs/openapi/9090-api-swagger.md` 與 `backend/src/main/resources/srpp/9090-api-swagger.md`（兩者位元組一致），再 `ruby scripts/render-9090-openapi-docs.rb --check`。
- [x] 575.9 測試同步：後端與 BFF 中斷言 `TW_RULES_V22` 的測試／fixtures 改 V23（`TradingRadarOpenApiSchemaContractTest`、`TradingRadarControllerCurrentTest`、`BacktestServiceTest`、`TradingRadarNotificationServiceTest`、`RedisRadarTechnicalCacheRepositoryRedisIntegrationTest`、`TradingRadarRuleEngineTest`、`RadarTechnicalResolverFubonOverlayTest`、`TradingRadarPanelServiceTest`、`TreasuryYieldServiceTest`、`bff/.../TradingRadarPanelFixtures.java`、`bff/.../TradingRadarPanelServiceTest.java`；以 `grep -rn TW_RULES_V22 backend bff frontend/src` 清零為準，`TradingRadarNotificationServiceTest` 的 `PREVIOUS_RULE_VERSION` 不動）。因新閘門使某些既有期望由減碼變續抱提高警戒，**逐案確認新結果符合 Requirement 200 才改期望值**，在完成報告列出每個被改期望的測試名與理由；不得為讓測試通過而改 production 邏輯以外的語意。
- [x] 575.10 新增測試（加在既有 `TradingRadarRuleEngineTest`），至少：
  1. 回檔低位：持有、`week52Position=0.60`、時機 NEUTRAL、分數落在減碼區 → `HOLD_CAUTION`，reasons 含「主升段結束」說明。
  2. 高檔區轉弱：持有、`week52Position=0.85`、K≤D、KD 死叉＋MA20 BELOW、分數落減碼區 → `REDUCE_CANDIDATE`。
  3. 高檔區反彈中：同 2 但 K>D → 不得為 `REDUCE_CANDIDATE`／`EXIT_CANDIDATE`。
  4. `week52Position=null` 且時機 NEUTRAL、分數落出場區 → 不賣。
  5. 跌深反彈：`ma60BiasPercent=−10`、完成日 +1.2%、K 35>prevK 28、K>D → `TRIAL_BUY`（持有、未持有各一）。
  6. 跌深但完成日收黑、或 K≤D、或 K≤prevK → 非 `TRIAL_BUY`。
  7. 否決逐一：narrow KD band、基本面惡化、ETF 溢價 3.0%、股票類 stale、時機 OVERBOUGHT → 非 `TRIAL_BUY`。
  8. RISK_OFF 不擋：股票類、RISK_OFF、其他條件成立 → `TRIAL_BUY` 且 risks 含 RISK_OFF 揭露。
  9. 年線跌破揭露：被賣出閘門擋下且 `ma240Confirmation=BELOW` → risks 含停損自評文案。
  10. 未持有 AVOID 在跌深區 → `WAIT`；非跌深區仍 `AVOID`。
  11. 三軌一致：同一輸入三軌（short／swing／medium）皆套用賣出閘門。
  12. 既有 `EXTREME_OVERSOLD` 測試結果不變；另加重疊案例：`week52Position=0.85`、K=8／D=12、極端超賣、osc<0、MA20 BELOW、分數落出場區 → `HOLD_CAUTION`（極端超賣分支優先）。
  13. 獲利了結但 K>D：極端超買（K=90、D=85、季線乖離 25%）、osc<0、完成日下跌且 `volumeRatio=1.3`（`profitTakingConfirmed` 成立、無死叉）、持有 → 三軌皆非 `REDUCE_CANDIDATE`／`EXIT_CANDIDATE`；同樣輸入但 K≤D 且死叉 → `REDUCE_CANDIDATE`。
- [x] 575.11 不新增 API／路由／DB／排程／券商 I/O；輸出只呈現建議，不下單。不修改分數、權重、分數門檻、`timingOf()`、`profitTakingConfirmed()`、`qualifiesForTrialBuy()`、`buyGate`、`EVIDENCE_GATE_V2`、`TradingRadarV13ActionPolicy`、`RuleParameters`。
- [ ] 575.12 SRPP Swagger 身分同步與 registry 產物（Task 504 受控鏈）。`<SRPP_WORKTREE>` 為由 `/Users/steven/Project/SRPP` 乾淨 main 建立的受控 feature worktree（絕對路徑、非 symlink、非 main、乾淨）。在 asset-management worktree 依下列**固定順序**執行，每步核對退出碼，第一個失敗即停止查因：
  1. `ruby scripts/render-9090-openapi-docs.rb --srpp-root <SRPP_WORKTREE>`
  2. `python3 scripts/sync-srpp-swagger-identity.py --srpp-root <SRPP_WORKTREE>`（更新 SRPP 的 consumer schema、runtime contract、policy manifest 與 backend `srpp/decision/policy_manifest.json`）
  3. `ruby scripts/render-9090-openapi-docs.rb --check --srpp-root <SRPP_WORKTREE>`、`python3 scripts/sync-srpp-swagger-identity.py --check --srpp-root <SRPP_WORKTREE>`
  4. 在 `<SRPP_WORKTREE>`：`python3 scripts/policy_bundle.py check --purpose daily --consumer Codex`、`python3 scripts/daily_runtime_contract.py check`，讀回 manifest SHA-256，並記錄新 policy bundle SHA-256。SRPP 端只允許 `docs/9090 Port API Swagger.md`、`assumptions/srpp_report_calculations_schema.json`、`assumptions/daily_runtime_contract.json`、`assumptions/policy_manifest.json` 四個產物變動。
  5. `ruby scripts/srpp-report-policy-register.rb`（無參數，讀第 2 步已更新的 backend embedded manifest）完整 stdout 先寫到 scratchpad 暫存檔、確認非空，再移入受追蹤的 `backend/src/main/resources/srpp/report/d201-registration.sql`；僅準備 SQL、不執行。
  6. 以原 generator 重產 bundle 綁定的 golden：`python3 -B scripts/generate-srpp-report-golden.py --srpp-root <SRPP_WORKTREE>` 與 `python3 -B scripts/generate-srpp-long-short-exit-golden.py --srpp-root <SRPP_WORKTREE>`；`scripts/generate-srpp-state-topup-golden.py` 以 `--check` 確認是否需重產，需要才重產。以 `python3 -m json.tool --sort-keys` 對 HEAD 結構化比對，允許差異**僅限** golden 的 `policyBundleSha256`；任何 case 的 input／expected 或 calculator hash 改變即停止查因（純 Swagger 身分更新不得改變金融輸出），不手改 expected、不改 generator 或 Java 計算。
  7. 之後才跑 `python3 -B scripts/check-srpp-report-authority.py --srpp-root <SRPP_WORKTREE> --check`、三支 generator 的 `--check`、`ruby scripts/tests/srpp-report-policy-register-test.rb`、`SRPP_RELEASE_WORKTREE=<SRPP_WORKTREE> python3 -B scripts/tests/check-srpp-report-authority-test.py`。
  8. 兩 repo 各自 feature commit、main `--no-ff` merge、push、`git ls-remote` 讀回；為避免影響當日日報，推送、登錄與部署於台北時間 13:15 之後、21:25 匯出排程之前進行。之後才以已合併的受追蹤 SQL 做 insert-only registry 登錄（`docker exec -i asset-postgres psql -X -q -v ON_ERROR_STOP=1`，stdin 為 `BEGIN;`＋原 SQL bytes＋`COMMIT;`，前後讀回舊 rows 全文不變、新 row 四欄與 generator 一致；同 hash 已存在先全文比對），再從乾淨 asset-management main 依 run-stack 重建 business-services、restart bff，確認運行 JAR 內 `srpp/9090-api-swagger.md`、`srpp/decision/policy_manifest.json`、`srpp/report/d201-registration.sql` 與合併來源逐位元相同。`report-calculations` 正式採用待下一合法時窗自然實跑，維護時記 `NOT_YET_EXECUTED`，不手動觸發 producer、POST observer、重跑報告、寄信或交易。

## 驗證

```bash
bash scripts/spec-check.sh
grep -rn "TW_RULES_V22" backend/src bff/src frontend/src docs/openapi   # 應無輸出
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -DextraArgLine=-Dnet.bytebuddy.experimental=true -Dtest='*Radar*,*Backtest*,*RuleParameters*,*OpenApi*,*TreasuryYield*' test
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f bff/pom.xml -DextraArgLine=-Dnet.bytebuddy.experimental=true -Dtest='*TradingRadar*' test
ruby scripts/render-9090-openapi-docs.rb --check
```

依 `/run-stack` 以 `--no-cache` 重建 business-services 並 restart bff，`unzip -p` jar 確認含 `TW_RULES_V23`；重取：

```bash
curl -s http://127.0.0.1:9090/api/public/trading-radar/today | python3 -c "import json,sys,collections; d=json.load(sys.stdin); print(d['ruleVersion'], collections.Counter(s['action'] for s in d['stocks']))"
```

確認 `ruleVersion` 為 `TW_RULES_V23`，且 2026-11-13 案例中 K>D 的回檔持股（0050、006208 等）不再是減碼候選；前後動作分布記入完成報告（單日快照，僅量級檢查）。

## 完成報告

**575.1～575.11 已完成（2026-11-13，實作 subagent）；575.12（SRPP 身分鏈）未做，交主 agent。**

### 變更檔

- Production：`backend/src/main/java/com/steven/assets/service/TradingRadarRuleEngine.java`（四個具名常數、`highZone()`／`uptrendEndConfirmed()`／`deepDrop()`／`qualifiesForReboundBuy()` 與文案 helper `deepBias()`／`deepWeek52()`／`deepDropDescription()`；`actionFor()` 依 575.6 七步改序；`RULE_VERSION`＝`TW_RULES_V23`、類別 Javadoc 補 V23 行）、`FubonRadarCompatibilityManifest.java`（`TW_RULES_V23|`）、`TradingRadarService.java`（兩處註解）、`dto/TradingRadarDto.java`（Javadoc 現行版號 V21→V23）。分數、權重、門檻、`timingOf()`、`profitTakingConfirmed()`、`qualifiesForTrialBuy()`、`buyGate`、evidence gate、V13 policy、`RuleParameters` 均未動；`StockInput` 未新增欄位。
- 契約：`docs/openapi/docker-external-api.yaml`（4 處 V22→V23、`info.version` 1.27.0→1.27.1）；以 `ruby scripts/render-9090-openapi-docs.rb`（無 `--srpp-root`）重產 `docs/openapi/9090-api-swagger.md` 與 `backend/src/main/resources/srpp/9090-api-swagger.md`（`cmp` 位元組一致）；`scripts/tests/docker-external-api-openapi-test.rb`（2 處）與 `scripts/tests/unified-9090-openapi-renderer-test.rb`（1 處）的 1.27.0 斷言同步為 1.27.1（不改則該二測試必紅）。
- 測試版號同步（V22→V23）：`TradingRadarOpenApiSchemaContractTest`、`TradingRadarControllerCurrentTest`、`BacktestServiceTest`、`TradingRadarNotificationServiceTest`（`PREVIOUS_RULE_VERSION` 未動）、`RedisRadarTechnicalCacheRepositoryRedisIntegrationTest`、`TradingRadarRuleEngineTest`、`RadarTechnicalResolverFubonOverlayTest`、`TradingRadarPanelServiceTest`、`TreasuryYieldServiceTest`、BFF `TradingRadarPanelFixtures`、`TradingRadarPanelServiceTest`。
- 新增測試：`TradingRadarRuleEngineTest` 14 個 `v23_*` 案例，涵蓋 575.10 第 1～13 項（每個需落在減碼／出場區的案例都先斷言分數區間，避免 fixture 漂移造成假綠）。

### 被改期望的既有測試與理由

1. `TradingRadarRuleEngineTest.oversoldWatch_keepsOriginal009804ScoreAndAction`：`REDUCE_CANDIDATE`→`HOLD_CAUTION`，並新增斷言 reasons 含「主升段結束」。理由：本案正是 Requirement 200 根因案例 009804——`week52Position` null、時機 OVERSOLD（非高檔區），回檔中不得減碼；K 18.5 < prevK 24 未反彈，亦不成立跌深反彈。分數 37 不變。
2. `TradingRadarRuleEngineTest.trialBuy_requiresLongTermPremium_notJustAboveAnnualMa`、`trialBuy_requiresAnnualConfirmation_rejectsFreshBreakout`：原斷言「非 TRIAL_BUY」改為「既有長線試單路徑未成立（無『逆勢試單的本質是接刀』risk），若為 TRIAL_BUY 必含『已跌深』reason」。理由：fixture K 15>D 14、K 由 12 上升、完成日 +0.3%、KD 超賣（OVERSOLD＝跌深），依 Requirement 200 應由新「跌深反彈試單」輸出 `TRIAL_BUY`；測試原意（`qualifiesForTrialBuy()` 的年線乖離／年線確認條件）仍被驗證。
3. `TradingRadarV13ActionPolicyTest.riskInput()` fixture：`week52Position` 0.5→0.85（影響 `missingSigmaWinsOverClosedEvidenceGateForHeldRiskAction`、`unavailableSigmaProfileGatesPositiveRowSigmaForBuyAndSell`）。理由：V23 下 0.5 非高檔區，基礎動作被賣出閘門降為 `HOLD_CAUTION`，V13 sigma gate 不再有賣出動作可處理而不產生 `V13_SIGMA_GATE` 揭露；兩測試的目的是驗 V13 sigma gate 對「持有者賣出動作」的處理，故改 fixture 讓基礎動作仍為賣出（K≤D＋死叉＋OSC<0＋MA20 BELOW 已滿足轉弱確認），期望值未改。
4. `TradingRadarV13ActionPolicyTest.extremeOversoldPriorityExclusionPreventsWrongfulBuyGateVetoAtDefaultBias`：新增斷言原 fixture（prevK 10）現為 `TRIAL_BUY`（跌深反彈優先於 buyGate，依 575.6 第 2 步與 Requirement 200「優先序在既有 TRIAL_BUY 之後、獲利了結之前」）；為保留本測試驗 buyGate EXTREME_OVERSOLD 優先權排除的原意，被測輸入改 prevK=15（K 未上升，跌深反彈不成立），原 `BUY_CANDIDATE` 期望不變。

### 測試輸出摘要

- `grep -ran "TW_RULES_V22" backend/src bff/src frontend/src docs/openapi`：無輸出（exit 1）。
- 後端 `-Dtest='*Radar*,*Backtest*,*RuleParameters*,*OpenApi*,*TreasuryYield*'`：exit 0，703 tests、0 failures／errors／skipped（含 `RedisRadarTechnicalCacheRepositoryRedisIntegrationTest` 3 項於本機實跑通過；`TradingRadarRuleEngineTest` 87 項、`TradingRadarV13ActionPolicyTest` 31 項）。
- BFF `-Dtest='*TradingRadar*'`：exit 0，151 tests、0 failures。
- `ruby scripts/render-9090-openapi-docs.rb --check`：PASS；`ruby scripts/tests/unified-9090-openapi-renderer-test.rb`、`ruby scripts/tests/docker-external-api-openapi-test.rb`：皆 exit 0。
- `bash scripts/spec-check.sh`：exit 0，BLOCK 0／CHECK 0。

### 偏差與注意事項

- **跌深反彈優先於 buyGate 的副作用**：依 575.6 順序，同時符合跌深反彈與 buyGate（分數 ≥75）的標的會輸出 `TRIAL_BUY` 而非 `BUY_CANDIDATE`／`ADD_CANDIDATE`（見上第 4 項）。這是 spec 明定順序的結果，未自行調整；若要讓順勢買進優先，需另改 spec。
- 575.8 只跑了無 `--srpp-root` 的 renderer 與 `--check`；統一 Swagger 位元組改變後，PublishedSwaggerIdentity／policy manifest／registry 尚未同步（575.12），在完成 575.12 前，依賴 Swagger 身分一致的 SRPP 測試或 runtime（capture／evaluate 的 `SWAGGER_MISMATCH`）可能不一致；本次驗證範圍內的測試均未受影響。
- 未重建 Docker、未碰資料庫、未 commit；Docker 重取 `trading-radar/today` 的前後動作分布留待主 agent 驗收時補記。

### 575.12 SRPP 身分鏈（主 agent，2026-11-13 15:5x 台北）

- SRPP 受控 feature worktree：`/Users/steven/Project/SRPP/.claude/worktrees/radar-v23-swagger`（分支 `claude/radar-v23-swagger-identity`，由 SRPP main `49ca0bc` 建立）；generator 需要的 gitignored 交易日曆 `data/input/交易日曆_2026／2027.json` 由 SRPP 主工作區複製（不入版控）。
- renderer／sync／兩個 `--check` 皆 exit 0。新 `swaggerSha256=806557e96afd048a5c7d34800fe8cb9c5129504851a59c2e179b144358887ce2`、swagger contract 1.27.1；policy bundle 由 `1f1e1646…062b` 變為 **`d601dfa7e71d240776a1a2af4c1770a9642e40ffc562fdcf0fcb5225bdef7da4`**；SRPP manifest 檔案 SHA-256 `9fccb08c2dff2f861b9cba7c89cf948c73fa1112573a650e8d733330035953ed`。SRPP 端只變動四個固定產物。
- `policy_bundle.py check --purpose daily --consumer Codex` PASS（176 檔）、`daily_runtime_contract.py check` PASS。
- `d201-registration.sql` 由 Ruby 無參數 stdout 重產，與舊檔差異只在 bundle hash。report-same-input 與 long-short-exit golden 重產，結構化差異僅 `policyBundleSha256`；state-topup `--check` 通過無需重產。
- authority gate、三支 generator `--check`、兩支 Ruby register 測試、三支 Python 回歸測試皆 exit 0。後端 SRPP（非 Postgres）＋雷達＋Backtest＋OpenAPI＋Swagger 測試 1236 個 0 失敗，`package` exit 0。
- 雙 repo 落地：SRPP feature `3c00517` → main merge `235d4f0`；asset-management feature `56f4a65` → main merge `99c51d2`；兩邊 push 後 `ls-remote` 讀回一致。
- Registry：以已合併 main 的 `d201-registration.sql` 單一交易 insert-only 登錄；登錄前 43 列、`d601dfa7` 不存在，登錄後只新增 1 列，舊列全文不變；新列 `formula_version`／`policy_document`／`formula_manifest` 與前一版 `1f1e1646…` 完全相同（僅 bundle hash 不同）。
- 部署（16:00 台北）：從乾淨 `/Users/steven/Project/asset-management-main`（`99c51d2`）`-p asset-management build --no-cache business-services`，force-recreate 後 12 秒 healthy，9090 market-index 恢復。重啟前確認今天已過時點的匯出排程皆已執行、晚間排程未到時間，`DIVIDEND_BACKFILL_ENABLED=false`。新 image `sha256:1c9007fe…`；運行 JAR 內 `srpp/9090-api-swagger.md`、`srpp/decision/policy_manifest.json`、`srpp/report/d201-registration.sql` 與 main 逐位元相同，`TradingRadarRuleEngine.class` 含 `TW_RULES_V23`。
- 雷達前後（`GET /api/public/trading-radar/today`，36 檔，單日快照）：中長期動作 V22 `REDUCE_CANDIDATE` 8 → V23 0、`TRIAL_BUY` 0 → 1（00881）、`HOLD_CAUTION` 4 → 10；短線 `REDUCE_CANDIDATE` 8 → 0、`TRIAL_BUY` 0 → 2（00881、GOOGL）；1週~1月 `REDUCE_CANDIDATE` 9 → 0、`TRIAL_BUY` 0 → 2。0050、006208、0056、00878、009804、00919、VOO、VT 由減碼候選改為續抱提高警戒（K>D 反彈中、非主升段結束）；00881（季線乖離約 −9.5%、反彈中）三軌皆為分批試單候選；MRVL 未持有超賣由 AVOID 改 WAIT。
- SRPP 9090 實測：日報工作區 `/Users/steven/Project/SRPP` 以 `--ff-only` 同步至 `235d4f0`（只動 4 個受追蹤檔，未追蹤資料保留）；同步前的收據 `reports/radar-v23-swagger-2026-11-13/9090-connectivity.json` 為 `EXPECTED_BUNDLE_DIFFERS_FROM_SRPP_SOURCE`（工作區仍是舊 bundle，屬預期），同步後 `9090-connectivity-after-ff.json` PASS（日曆、TWSE 指數、台股報價皆 200 且內容有效）。`report-calculations` 正式採用待下一交易日合法時窗自然實跑：`NOT_YET_EXECUTED`。
