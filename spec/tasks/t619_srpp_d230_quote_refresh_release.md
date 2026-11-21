# [t619] D-230：SRPP計算包報價缺口改以9090新報價重判的規則包身分發布

**對應 Requirements:** Requirements 187／193／199（不可變報告計算、正式authority與美股核心計算）
**前置任務:** t618
**Liquibase changeset:** 無

## 背景

Steven 2026-11-21指示：股價不夠新鮮時應呼叫9090取得新報價，不該直接以不夠新鮮放棄交易建議；並要求修正規則矛盾。SRPP D-230：`report-calculations`中策略債券、國泰緊急00865B與009826建倉的**買進**零張腿，若阻擋原因全部是擷取當時的報價缺口（`LIVE_PRICE_UNVERIFIED`、`LIVE_QUOTE_STALE`、`BEST_FIVE_UNVERIFIED`、`FUBON_OFFICIAL_CAPTURE_FAILED`、`FUBON_SOURCE_NOT_CAPTURED`、`PREMIUM_OR_PRICE_UNVERIFIED`），由SRPP消費端（`scripts/trade_blocker_policy.py`、`scripts/report_evidence_guard.py`）以同輪既有`GET /api/quotes`／`GET /api/quotes/one`新報價、五檔、深度（官方淨值／折溢價缺口另加`premiumDiscountPct`）重判，張數仍只由SRPP既有計算器產出且不超過其每日上限；其他原因與D-218高股息核心補足維持不可解除。另把條文中的D-214配置鏡像數字對齊D-222／D-224機器設定（`model_inputs.json`位元組不變）。

9090不新增或改變任何路由、計算、DTO、DB、排程、formula或Swagger；計算包仍照原規則產生零張腿，重判只在SRPP報告端。資產端只換規則包身分並重建business-services。

## SRPP凍結來源

root `/Users/steven/Project/SRPP/.claude/worktrees/srpp-logic-review-0ab4b8`，凍結commit `e395253`，bundle `a143a30305f58339dd3062ab569d3486a1842ce5b7f3928ad0939d12b2db2867`（前一版 `da3fe6e47db30a6082bd01fed00a2b95614acc4b4c599ea8d9cdd9e898dae02f`）；manifest檔案SHA-256 `4864d98a3354a2fd59a47a8ff198fe72ccbd69d7d133e2c4250bb19a462d1fbb`；`model_inputs.json`（`9bf226bf…`）與Swagger（`aaedb6b9…`）位元組不變，只同步`policy_manifest.json`。

## 要做什麼

- [x] 619.1 同步凍結`policy_manifest.json`原始bytes至backend決策resource；原Ruby產d201-registration.sql，原Python重產report-same-input與long-short-exit golden（只變bundle行），authority gate、golden `--check`、Ruby registry測試、focused Java通過。不改Java、DB、formula、Swagger。
- [ ] 619.2 雙repo feature commit／main no-ff merge／push與remote讀回→已合併SQL insert-only registry（舊rows全文不變）→run-stack只build／force-recreate business-services（避開報告與匯出窗）→JAR bytes讀回並立即`--ff-only`快轉日報工作區→從SRPP實測9090。

## 驗證

```sh
bash scripts/spec-check.sh
python3 -B scripts/check-srpp-report-authority.py --srpp-root <SRPP root> --check
python3 -B scripts/generate-srpp-report-golden.py --srpp-root <SRPP root> --check
python3 -B scripts/generate-srpp-long-short-exit-golden.py --srpp-root <SRPP root> --check
ruby scripts/tests/srpp-report-policy-register-test.rb
mvn -q -f backend/pom.xml -Dtest=SrppReportModuleCalculatorTest,DecisionPolicyAuthorityTest,DailyDecisionCalculatorTest test
git diff --check
```

審查記錄：Codex用量上限（11-24 00:32恢復），未執行Codex spec審查；SRPP端行為變更由作者以外的Claude審查代理審閱，S1–S9已修（折溢價閘、計算器每日上限、D-218排除、方向不明列、文件範圍與殘留數字），SRPP全套回歸無新增失敗。
