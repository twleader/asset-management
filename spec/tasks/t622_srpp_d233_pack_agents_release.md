# [t622] D-233：SRPP規則包條件包代理的規則包身分發布

**對應 Requirements:** Requirements 187／193／199（不可變報告計算、正式authority與美股核心計算）
**前置任務:** t621
**Liquibase changeset:** 無

## 背景

Steven 2026-11-21核准D-233：SRPP日報規則包主體之外，六個附評估程式的條件包（usd_fx、deep_drawdown、voo_bridge、high_dividend_stabilization、cash_rotation、correction_bear）可交由最多2個子代理以主工作凍結證據讀包與執行評估程式，結果經新`scripts/verify_pack_agent_result.py`驗證後由主工作保留全部最終判斷與硬閘。`model_inputs.json`新增`execution_policy.schedule_runtime_policy.rule_pack_agents_d233`。投資數值、門檻、資格與時限不變；9090的Java主程式、DTO、DB、排程、formula與Swagger都不變，`DailyDecisionCalculator`不讀此runtime鍵。只同步規則包bytes並重建business-services。

## SRPP凍結來源

SRPP worktree `claude/d233-pack-subagents`，凍結commit `dec5330`，bundle `bef329db726ee948c0608ef5f2674f9f9471b6c94ae5b175f7a81a74dca29049`（前一版 `3425db1e0317b1cab1309f2a954bac7304a4a5177618c6c1987e41866f954436`）；`model_inputs.json`（`cd08ecbb…`）與`policy_manifest.json`（`261a17d3…`）位元組改變，Swagger（`aaedb6b9…`）不變。

## 要做什麼

- [x] 622.1 同步凍結`policy_manifest.json`與`model_inputs.json`原始bytes至backend決策resource；原Ruby產d201-registration.sql，原Python重產report-same-input、long-short-exit golden（只變bundle行）；state-topup golden不變。authority gate、golden `--check`、Ruby registry測試、focused Java通過。
- [ ] 622.2 雙repo feature commit／main no-ff merge／push與remote讀回→已合併SQL insert-only registry→run-stack只build／force-recreate business-services→JAR bytes讀回並立即`--ff-only`快轉日報工作區→從SRPP實測9090。

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

審查記錄：Codex用量上限（11-24 00:32恢復），未執行Codex spec審查；SRPP端變更已由另一支Claude審查代理獨立審查，5項必須修已修正；資產端僅同步bytes，Java不變。
