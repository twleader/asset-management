# [t621] D-232：SRPP 9090重新取數與規則包分包讀取的規則包身分發布

**對應 Requirements:** Requirements 187／193／199（不可變報告計算、正式authority與美股核心計算）
**前置任務:** t620
**Liquibase changeset:** 無

## 背景

Steven 2026-11-21指示：早報、午報、美股報告不得因9090一次未取得資料就放棄交易建議，改為重新呼叫9090取得新資料；規則包改為先讀主體與索引、執行中依索引載入需要的包。SRPP D-232新增`scripts/fetch_9090.py`（暫時失敗、202／429／5xx、非JSON或行情過期時同一請求最多4次、間隔10／30／60秒；4xx不重試；第35分鐘後不再發出），`model_inputs.json`新增`execution_policy.schedule_runtime_policy.api_refetch_d232`與`final_quote_refresh_d176.per_symbol_refresh_follows`，並新增`assumptions/rule_packs.json`／`scripts/rule_packs.py`分包讀取。投資數值、門檻、資格與時限不變；9090的Java主程式、DTO、DB、排程、formula與Swagger都不變，`DailyDecisionCalculator`不讀上述runtime鍵。只同步規則包bytes並重建business-services。

本任務不把SRPP本機計算器搬進Java：其餘SRPP-only計算器各需新端點、Swagger身分鏈、同輸入golden對等與Codex規格審查，另列後續任務。

## SRPP凍結來源

root `/Users/steven/Project/SRPP/.claude/worktrees/morning-noon-us-reports-improve-bae1eb`，凍結commit `c6d7d88`，bundle `3425db1e0317b1cab1309f2a954bac7304a4a5177618c6c1987e41866f954436`（前一版 `bacfe2efab3830bd683305faa8d5e71e9b481a4f23e742d5f9b83aabf624ae13`）；`model_inputs.json`（`e687e35e…`）與`policy_manifest.json`（`7c0570c7…`）位元組改變，Swagger（`aaedb6b9…`）不變。

## 要做什麼

- [x] 621.1 同步凍結`policy_manifest.json`與`model_inputs.json`原始bytes至backend決策resource；原Ruby產d201-registration.sql，原Python重產report-same-input、long-short-exit golden（只變bundle行）；state-topup golden不變；111／15／22個parity case金融輸出不變。authority gate、golden `--check`、Ruby registry測試、focused Java通過。
- [ ] 621.2 雙repo feature commit／main no-ff merge／push與remote讀回→已合併SQL insert-only registry→run-stack只build／force-recreate business-services→JAR bytes讀回並立即`--ff-only`快轉日報工作區→從SRPP實測9090。

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

審查記錄：Codex用量上限（11-24 00:32恢復），未執行Codex spec審查；SRPP端變更已由另一支Claude審查代理獨立審查並修正，資產端僅同步bytes，Java不變。
