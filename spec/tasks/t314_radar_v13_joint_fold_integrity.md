# [t314] 交易雷達 V13 joint-fold 無洩漏與回測成本邊界閉環

**對應 Requirements:** Requirement 65（required-horizon 樣本外校準必須可重現且不得借用另一 horizon 的 fold 證據）
**前置任務:** t308（V13 可成交回測）、t310（V13 停止點）
**Liquibase changeset:** 無

## 背景

目前 `BacktestService.selectJointFoldCandidates` 以 `groups.get(0)` 作 seed，逐 seed 的 folds 建 grid，並把 seed 的 `fold.trainDates()`、`fold.evaluationFrom()` 與 sigma profile 套到同一 track 的另一個 required horizon。SHORT 的 5/20 與 MEDIUM 的 60/120 因可成交 exit 不同，global calendar、fold train dates、evaluation boundary 都可能不同；seed 寫法會讓報告看似是 joint selection，實際卻沒有證明每個 horizon 都使用自己的 evaluation 邊界，也無法產生單一可重現的 joint sigma profile。

`RadarBacktestExecution.CostAssumption` 另帶 effective interval，但缺省成本寫死 `effectiveFrom=2026-08-01`、執行路徑又完全不檢查日期，導致 2016–2026 報告一面 echo 生效日、一面對生效日前樣本照算。這不一定改變 candidate 與 baseline 的相對排序，卻會讓 net return、practical delta 與模型聲明不可稽核。

本任務只修正回測證據與報告，不啟用 production V13。完成後即使真實 holdout 拒絕全部 candidate，`TradingRadarRuleEngine.RULE_VERSION` 仍須維持 `TW_RULES_V12`。

## 要做什麼

- [ ] **314.1 建立純 Java joint track fold。** 在 `RadarWalkForwardPlan` 新增 immutable `JointTrackFold`（或同等 typed value），欄位至少含 `index`、排序後 `requiredHorizons`、排序後唯一 `jointTrainDates`、`horizonFolds Map<Integer,Fold>`。factory 接 required horizons 與每個 horizon 自己的 `Fold`：
  - required horizon 全部存在且 fold index 相同才可建立；任何缺漏回 explicit unavailable，不得取第一個 horizon 補值。
  - `jointTrainDates` 是所有 horizon `trainDates` 的集合交集，不是 union；空交集為 unavailable。
  - 每個 joint train date 必須嚴格早於每一個 horizon 自己的 `evaluationFrom()`；違反時 fail closed。
  - 各 horizon 的 `evaluationDates/evaluationFrom/evaluationTo` 原樣保留，禁止改成共同 seed block。

- [ ] **314.2 每個 production key／fold 只建立一份 joint sigma profile。** `BacktestService` 對 `(market,instrumentKind,productionProfile,track,foldIndex)` 收集該 exact production key 的非 candidate-dependent code union，用 314.1 的 `jointTrainDates` 呼叫唯一 sigma calibration：
  - sigma observation 的 signal date 必須在 `jointTrainDates`；cutoff 是交集最後一日，`asOfTo<=cutoff`。
  - 同一 fold 的所有 required horizons 使用同一個 immutable profile 與同一份由它生成的 candidate grid；不得使用任一 horizon 原先的 `foldSigmaProfiles` 建 joint grid。
  - joint dates、code universe 或 profile 缺漏時，該 track/fold 標 `JOINT_FOLD_SIGMA_PROFILE_UNAVAILABLE`，不得回退 request-global profile或單一 horizon profile。

- [ ] **314.3 以各 horizon 自己的 boundary 做 purge 後再 joint selection。** 對同一 candidate、每個 required horizon：只取 signal date 位於 `jointTrainDates`、primary execution 存在、且 `exitDate < 該 horizon evaluationFrom` 的 train rows；candidate 與 V12 baseline 再取相同 `(code,signalDate,horizon)` intersection。任一 horizon `intersectionN=0` 即該 candidate 不具 joint 資格，不能借另一 horizon 樣本。跨 horizon selector tuple 固定為：
  1. pooled candidate `netReturnPct<=-10` rate 較低；
  2. 先把同 code 在所有 required-horizon rows 的 candidate-baseline delta 取 mean，再對 code means 取 median，較高者優先；
  3. 所有 required-horizon intersection rows 的 candidate mean 減 baseline mean，較高者優先；
  4. 非零 candidate weights 較少；
  5. 完整 `RuleParameters.distanceFrom(V12_DEFAULT)` 較小；
  6. `parameterSetId` lexical 較小。

  `distanceFrom` 必須涵蓋短／中期 thresholds、confidence、normalized floor/multiples、downside threshold、所有 candidate weights、`WeakeningCondition` 的 volume floor，以及 `BondRateCandidate` 三個 tenor、shape weight、return scale；不得漏掉可調 weakening 條件後仍宣稱「較接近完整 V12」。`WeakeningCondition` 的兩個 boolean 是不可放寬的 safety invariant，constructor 必須繼續拒絕任一 false，production candidate grid 不得為了 distance 測試產生弱化參數。

- [ ] **314.4 報告 echo joint 與 per-horizon 證據。** 每個 `WalkForwardFold/FoldExecutionEvidence`（或等價 DTO）新增且 JSON/CSV 都可見：`jointTrainDateCount`、`jointTrainFrom`、`jointTrainTo`、`jointRequiredHorizons`、共同 sigma cutoff/source/status；同時保留該 report row 自己的 `trainFrom/trainTo/evaluationFrom/evaluationTo`、candidate/baseline/intersection n/codes、purged train n/codes。兩個 required horizon 的同 fold 必須 echo 相同 selected candidate 與 joint sigma snapshot，但各自 evaluation boundary 可不同。舊 request／舊 JSON 欄位保持相容。

- [ ] **314.5 修正 misleading candidate identity。** 把實際 `SHORT=M3, MID=Y5, LONG=Y10, shapeWeight=0.25` 的 ID 從 `V13_TREASURY_BOND_Y5_Y5_Y10_SHAPE25` 全面改為 `V13_TREASURY_BOND_M3_Y5_Y10_SHAPE25`；更新程式、測試、CSV/JSON 期待值與完整 parameter snapshot。不得只改 label 而讓 tenor mapping 漂移。

- [ ] **314.6 成本 effective interval 真正生效。** 缺省 `CONSERVATIVE_MODEL_2026_08` 是跨期間固定比較的模型假設，`effectiveFrom/effectiveTo` 都設 null。使用者 override 的非 null 邊界為 inclusive；primary 與 close sensitivity 只有 entryDate、exitDate 都落在 interval 才可產生。日期不符時 `ExecutionAttempt` 以獨立 `excludedCostOutsideEffectiveRange=true` 回報，不能混入 missing-open/insufficient-forward；`MarketHorizonExecution` 與 CSV/JSON 增加同名排除計數。此排除發生在 `TradableOpportunity` 之前，因此不得進 global calendar、sigma/calibration、holdout 或 promotion。

- [ ] **314.7 回歸測試。** 至少新增下列可辨別失敗原因的測試：
  - 5/20 horizon 的 train/evaluation dates 刻意不同，證明 joint train 是交集、兩邊各用自己的 evaluationFrom，交換 required horizon 順序結果 bit-identical；
  - 一邊 exit 跨入 evaluation block 時只 purge 該 horizon row，且不得用 seed boundary 放行；
  - joint sigma 的 observation dates 全屬交集，兩個 horizon parameter snapshot 完全相同，任一 profile unavailable 時整個 joint fold unavailable；
  - selector 六層 tie-break，包括只差合法 weakening volume floor 的 `distanceFrom`；另斷言任一 weakening boolean=false 仍由 constructor fail closed，不得放寬 invariant；
  - candidate ID 與 M3/Y5/Y10 tenor snapshot 一致；
  - timeless default 可計歷史樣本；override 的 from/to 前、邊界日、區間內、區間後案例，排除計數與 global calendar 都正確；
  - legacy 六欄 request 與 V12 production score/action/ruleVersion bit-identical。

## 驗證

```bash
bash scripts/spec-check.sh
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -DextraArgLine=-Dnet.bytebuddy.experimental=true -Dtest=RadarWalkForwardPlanTest,RadarBacktestExecutionTest,TradingRadarCalibrationSelectorTest,BacktestServiceTest test
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -DextraArgLine=-Dnet.bytebuddy.experimental=true test
```

## 完成報告

- **實作範圍：** `RadarWalkForwardPlan.JointTrackFold`／`JointTrackFoldResolution` 以排序後 required horizons、各 horizon 原始 `Fold` 與 train-date 交集建立 immutable joint fold；缺 horizon、fold index 漂移、空交集或 boundary 違反皆回具名 unavailable。`BacktestService` 對 exact `(market,instrumentKind,productionProfile,track,foldIndex)` 的 code union 建立唯一 joint sigma profile、candidate grid 與選值，各 horizon 再以自身 `evaluationFrom` 做 `exitDate < evaluationFrom` purge 及 same-sample intersection。交換 5／20 required-horizon 輸入順序的測試取得 bit-identical fold candidate IDs。
- **報告證據：** JSON `WalkForwardFold` 以 typed `LocalDate` 輸出 `jointTrainFrom/jointTrainTo`，並輸出 `jointTrainDateCount/jointRequiredHorizons`；CSV 只在序列化邊界轉 ISO date string。兩個 required horizons 的同 fold 斷言 joint date 範圍、sigma cutoff/source/status、selected candidate 及完整 parameter snapshot 相同，同時保留各自 evaluation boundary 與 `purgedTrainN/purgedTrainCodes/purgeBoundary`。
- **selector 與 candidate identity：** 六層 tie-break 沿用 downside、per-code paired median、pooled mean、非零 weights、完整 `distanceFrom(V12_DEFAULT)`、lexical ID；distance 已涵蓋 normalized-path enablement、短／中期 thresholds、confidence、floor/multiples、downside、全部 weights、weakening volume floor 與 bond tenor/shape/return scale。兩個 weakening boolean 仍由 constructor 拒絕 false。Treasury candidate ID 已改為 `V13_TREASURY_BOND_M3_Y5_Y10_SHAPE25`，snapshot 斷言 `SHORT=M3/MID=Y5/LONG=Y10`、shape weight `0.25`。
- **成本區間證據：** default `CONSERVATIVE_MODEL_2026_08` 的 `effectiveFrom/effectiveTo` 皆為 null。override from/to 採 inclusive；區間外以獨立 `excludedCostOutsideEffectiveRange` 排除，且 primary、close sensitivity、global calendar、sigma/calibration、holdout/promotion 都不納入。構造案例取得 `excludedCostOutsideEffectiveRange=18`、`excludedInsufficientForward=2`、`globalDateCount=10`、`closeSensitivityN=10`，missing-open 兩計數皆為 0。
- **自動化驗證（2026-08-12）：** 定向 `RadarWalkForwardPlanTest,RadarBacktestExecutionTest,TradingRadarCalibrationSelectorTest,BacktestServiceTest` 共 49/49 通過；完整 backend regression 共 832/832 通過，0 failure／0 error／0 skipped；`git diff --check` 通過；`scripts/spec-check.sh` 為 `BLOCK: 0／CHECK: 0`。
- **發布邊界：** 本任務只完成程式與自動化測試證據；Docker image rebuild/recreate、running-container health/API 與真實 full-universe holdout 留待 t314–t316 全部整合後依 `/run-stack` 統一驗證。`TradingRadarRuleEngine.RULE_VERSION` 仍為 `TW_RULES_V12`，本完成報告不構成 V13 promotion 或交易核准。
- **整合 runtime（2026-08-12）：** feature image 的 business-services、BFF 與 frontend 已 rebuild／force-recreate，BFF health 為 UP、首頁 HTTP 200；已登入 session 的交易雷達回應仍為 `TW_RULES_V12`／`EVIDENCE_GATE_V1`。真實 FULL_MARKET request 的 joint-fold／sigma／purge 結果由 t316 固定 request 的執行 report 決定，在結果完整回收前不得推論 V13 candidate 或 promotion。
