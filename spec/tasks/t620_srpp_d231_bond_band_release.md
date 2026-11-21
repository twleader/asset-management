# [t620] D-231：SRPP債券一般再平衡帶21%–28%的規則包身分發布

**對應 Requirements:** Requirements 187／193／199（不可變報告計算、正式authority與美股核心計算）
**前置任務:** t619
**Liquibase changeset:** 無

## 背景

Steven 2026-11-21選定D-231方案B：D-224已把策略債券目標改為23.50%，但一般再平衡帶仍是24%–31%（下緣高於目標）。SRPP把`bond_allocation_band_d143.normal_rebalance_floor`改0.21、`normal_rebalance_cap`改0.28、`dynamic_allocation_policy_d141.total_bond_cap`改0.28，並統一59.50%／23.50%條文與模型說明。9090的`DailyDecisionCalculator`以`totalBondCap`算債券群組容量，直接讀同步後的`model_inputs.json`，Java主程式、DTO、DB、排程、formula與Swagger都不變；只同步規則包bytes並重建business-services。

## SRPP凍結來源

root `/Users/steven/Project/SRPP/.claude/worktrees/srpp-logic-review-0ab4b8`，凍結commit `97cbfbf`，bundle `bacfe2efab3830bd683305faa8d5e71e9b481a4f23e742d5f9b83aabf624ae13`（前一版 `a143a30305f58339dd3062ab569d3486a1842ce5b7f3928ad0939d12b2db2867`）；`model_inputs.json`（`167d7994…`）與`policy_manifest.json`（`411b02b6…`）位元組改變，Swagger不變。

## 要做什麼

- [x] 620.1 同步凍結`policy_manifest.json`與`model_inputs.json`原始bytes至backend決策resource；原Ruby產d201-registration.sql，原Python重產report-same-input、long-short-exit（只變bundle行）與state-topup golden（只變`transition_ledger.py`計算器雜湊，該檔僅改註解）；111／15／22個parity case金融輸出不變。`DailyDecisionCalculatorTest.sameRoundSecondLegAndPriorSlotReservationsRespectCombinedExposure`的窄容量fixture原以31%上緣設計（債券3,061,000／總資產10,000,000），改為28%上緣下同樣差39,000的2,761,000；斷言本身不變。authority gate、golden `--check`、Ruby registry測試、focused Java通過。
- [ ] 620.2 雙repo feature commit／main no-ff merge／push與remote讀回→已合併SQL insert-only registry→run-stack只build／force-recreate business-services（避開21:25–21:43匯出窗）→JAR bytes讀回並立即`--ff-only`快轉日報工作區→從SRPP實測9090。

## 驗證

```sh
bash scripts/spec-check.sh
python3 -B scripts/check-srpp-report-authority.py --srpp-root <SRPP root> --check
python3 -B scripts/generate-srpp-report-golden.py --srpp-root <SRPP root> --check
python3 -B scripts/generate-srpp-long-short-exit-golden.py --srpp-root <SRPP root> --check
SRPP_RELEASE_WORKTREE=<SRPP root> python3 -B scripts/generate-srpp-state-topup-golden.py --srpp-root <SRPP root> --check
ruby scripts/tests/srpp-report-policy-register-test.rb
mvn -q -f backend/pom.xml -Dtest='SrppReport*Test,DecisionPolicyAuthorityTest,DailyDecisionCalculatorTest' test
git diff --check
```

審查記錄：Codex用量上限（11-24 00:32恢復），未執行Codex spec審查；範圍為使用者選定的單一參數帶，Java不變。
