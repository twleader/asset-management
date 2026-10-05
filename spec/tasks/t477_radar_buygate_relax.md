# [t477] 雷達買進閘門放寬乖離 15%／單日漲幅 8%

**對應 Requirements:** Requirement 178
**前置任務:** t446
**Liquibase changeset:** 無

## 背景

近半年以 0050／2330／2317 重算：乖離 ≥12% 是最大單一否決（0050 共 45／128 天），單日漲幅 ≥5% 影響甚小。Task 446 已讓兩個門檻可校準，但正式路徑仍硬編 5／12。使用者明確要求放寬（風險偏好，非實證）。

## 要做什麼

- [x] 477.1 `TradingRadarRuleEngine` 新增常數 `PRODUCTION_CHASED_MOVE_PCT=8`、`PRODUCTION_BUYGATE_BIAS_PCT=15`，`candidate == null` 分支改用它們；`BIAS_HIGH` 不變，`RuleParameters.v12Default()`／`v13BuyGateCandidate`／回測網格值不變（回測 baseline 與 swing 會隨正式路徑改為 8／15，見 Requirement 178 回測影響段，不得宣稱仍是 5／12 對照）。
- [x] 477.2 `RULE_VERSION` 改 `TW_RULES_V21`；`FubonRadarCompatibilityManifest.DECISION_INPUT_VERSION` 同步改 V21；同步 `docs/openapi/docker-external-api.yaml`（ruleVersion 範例／描述）與重產 Swagger Markdown（外部鏡像只在地端覆寫，雲端 session 僅改專案內那份）、`TradingRadarDto`／`TradingRadarService` 註解、`TradingRadarView.vue`、`tradingRadarPanelLoader.test.js`、BFF `TradingRadarPanelFixtures`／`TradingRadarPanelServiceTest`、`TradingRadarNotificationServiceTest`（約 257 行期望值改 V21；`PREVIOUS_RULE_VERSION` 現為 V17，維持不動；斷言升版首輪不 enqueue）、`TradingRadarRuleEngineTest`（約 582 行版號斷言）、backend `TradingRadarPanelServiceTest`、`BacktestServiceTest`（約 924–955 行）、`TreasuryYieldServiceTest`（約 209 行）；`TradingRadarControllerCurrentTest` 與 `RedisRadarTechnicalCacheRepositoryRedisIntegrationTest` 的 V20 為 fixture 字串，順手同步、`TradingRadarOpenApiSchemaContractTest`、`RadarTechnicalResolverFubonOverlayTest`；先 `grep -ran TW_RULES_V20` 逐一處理。
- [x] 477.3 測試改寫：`TradingRadarRuleEngineTest` 的 `chasedDailyMoveAndMidTierOverboughtBoundary_stillVetoesProductionBuyGate`（fixture 5／12 邊界）改為 15／8 邊界仍否決，並新增乖離 13、漲幅 6 正式路徑可出買進／加碼候選；`TradingRadarV13ActionPolicyTest` `buyGateVetoCandidateAtDefaultThresholdsMatchesProductionAtBoundary`、`widerChasedDailyMoveCandidateAllowsBuyBeyondDefaultFivePercentVeto`、`widerOverboughtBiasCandidateAllowsBuyWithoutChangingReportedTimingState`、`extremeOversoldPriorityExclusionPreventsWrongfulBuyGateVetoAtDefaultBias` 四個 t446 測試改為「正式路徑 8／15 對照 `RuleParameters.v13BuyGateCandidate(…,5,12)` 候選的差異」（原以正式路徑當 5／12 對照的斷言會失敗），其中 `EXTREME_OVERSOLD` 優先權回歸樣本乖離改為 ≥15 才能繼續驗證排除條件；V13 buy-gate 5／12 候選仍否決（不得寫成「V12 baseline 候選」，`evaluateCandidate` 只接受 V13 參數）。

- [x] 477.4 不動 API／DB／券商；不改風險文案、不抑制「完成日漲幅達 5% 以上，避免追高」。
- [x] 477.5 驗收上限：放寬前基準為買進候選 1、減碼候選 1（26 檔，2026-10-06）。重建後重取 `GET /api/public/trading-radar/today`，買進／加碼候選數須 ≤ 基準 3 倍且 ≤ 標的數 25%；超過就改用乖離 14%／漲幅 6% 重驗，並把前後數字記入完成報告。賣出端不放寬。（上限取兩者較小值，約 3 檔）
- [x] 477.6 賣出端不動：測試斷言極端超賣（`EXTREME_OVERSOLD`）持有樣本仍回 `HOLD_CAUTION`、不輸出減碼／出場候選；不得修改 `actionFor()` 賣出分支與分數門檻。

## 驗證

```bash
bash scripts/spec-check.sh
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -DextraArgLine=-Dnet.bytebuddy.experimental=true -Dtest='*Radar*,*Backtest*,*RuleParameters*' test
```

依 `/run-stack` 重建 business-services（`--no-cache`）並 restart bff，確認 healthy 並 `unzip` jar 驗證含 `TW_RULES_V21`。

## 完成報告

- 實作檔案：`TradingRadarRuleEngine`（新增 `PRODUCTION_CHASED_MOVE_PCT=8`、`PRODUCTION_BUYGATE_BIAS_PCT=15`，`RULE_VERSION=TW_RULES_V21`）、`FubonRadarCompatibilityManifest`、`TradingRadarDto`／`TradingRadarService` 註解、`TradingRadarView.vue`、`docs/openapi/docker-external-api.yaml` 與 `9090-api-swagger.md`，以及相關 backend／BFF／前端測試（V20 改 V21、t446 四個測試改為對照 8／15 與 5／12 候選）。賣出端未動。
- 驗證：backend mvn 全綠（2614 通過）；BFF 測試、前端測試與 build 通過。
- run-stack 驗收：image 建置時間 frontend 01:40、bff 01:42、business 01:51；`ruleVersion` 為 `TW_RULES_V21`。今日 38 檔動作分布與放寬前相同（買進候選 1、減碼候選 1、出場 0），符合 477.5 上限（通過），但未觀察到放寬的實際效果，瓶頸可能在總分門檻 75。
- 回測 baseline 隨正式路徑改為 8／15，不再是 5／12 對照。
