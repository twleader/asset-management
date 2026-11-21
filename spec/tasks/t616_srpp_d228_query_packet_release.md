# [t616] D-228 每日規則包查詢包讀取選項的SRPP規則包身分發布

**對應 Requirements:** Requirements 187／193／199（不可變報告計算、正式authority與美股核心計算）
**前置任務:** t615
**Liquibase changeset:** 無

## 背景

SRPP D-228 為每日報告新增選用的規則包讀取方式：`daily_authority_packet.py --l2-mode query` 與 `--defer-modules`，讓設定目錄與HTML範本改以查詢包（`rule_query.py`）讀取，條文點名的欄位逐字全文、其餘只列id／標籤／位元組／雜湊，查詢包失敗自動退回完整讀取。D-228 不改任何規則條文、數值、門檻、資格、時限或停止條件，也不改policy 2.75.0／model 3.25.0。

這只是SRPP文字與讀取工具的變更；資產端只換規則包身分：9090任何API、Java計算器、DTO、DB、排程、model、formula、formal targets、Swagger與schema鏡像都不變。`scripts/`、`docs/`、`assumptions/`列管來源改變使bundle hash改變，舊hash在新JAR上會回409 POLICY_UNSUPPORTED，因此必須登錄新hash並重建business-services。

## SRPP凍結來源

root `/Users/steven/Project/SRPP/.claude/worktrees/srpp-schedule-check-42107e`（分支`claude/srpp-schedule-check-42107e`，基準SRPP main／origin/main `8447acd`），凍結commit `3d1b9f3`，共11個檔案：`docs/DAILY_TRADING_AUTOMATION.md`、`docs/DECISION_LOG.md`、兩份Claude schedule entry SKILL、`scripts/README.md`、`scripts/daily_authority_packet.py`、`scripts/test_data_tools.py`、新增`scripts/rule_query.py`與`scripts/test_rule_query.py`，以及重建後的`assumptions/daily_runtime_contract.json`與`assumptions/policy_manifest.json`。新bundle `565ac5c1de6f402dbfc5d872109fef843a3bdceecafe3defbad3a5291ae7b860`（舊 `8d260b093fa15868ee8b0bea578c0e95a6842f378db6882f39a7e99d0d99bcff`）；`docs/9090 Port API Swagger.md`位元組不變（SHA-256 `aaedb6b9…`）。

## 要做什麼

- [ ] 616.1 先spec-check及作者以外的唯讀spec審查，記錄通過後才同步backend。來源只用上述root凍結；policy 2.75.0／model 3.25.0維持，model_inputs、formula manifest、Swagger、schema、targets、Java與DB均不變。
- [ ] 616.2 同步凍結`assumptions/policy_manifest.json`原始bytes至backend決策resource（model同值不改）。原Ruby stdout產受追蹤d201-registration.sql，原Python重產report-same-input與long-short-exit golden（只應變bundle行），並以authority gate驗證。
- [ ] 616.3 authority gate、golden checks、Ruby registry、focused Java與backend package通過；保存新的source／bytes證據。
- [ ] 616.4 順序為離線驗收→雙repo各feature commit／main no-ff merge／push及精確remote讀回→已合併SQL insert-only registry；精確核新hash的policy_bundle_sha256／formula_version／policy_document／formula_manifest且舊rows全文不變。
- [ ] 616.5 由run-stack從乾淨正式asset main與原設定，只build／force-recreate business-services；重建前以唯讀證據確認台股與美股報告均未執行中（無活動報告、守門員未在處理），證明各啟動入口為no-op，並依本輪有效排程與時區確認整段「recreate→JAR bytes讀回→日報工作區`--ff-only`快轉」不跨入台股報告與守門員窗（09:05–13:10）、美股報告窗（22:35–23:45）及各匯出排程分鐘（匯出分鐘另行避讓）；證據不足或時間不夠安全時停止並保留已落地的main與registry row，不強行切換。重建後核對容器／JAR內嵌bytes，並立即快轉日報工作區`/Users/steven/Project/SRPP`（舊hash在新JAR上回409，兩步之間不得留空窗）。從SRPP實測9090連線。

## 驗證

```bash
TMP=$(mktemp -d)   # 兩個repo之外的暫存目錄
bash scripts/spec-check.sh
python3 -B scripts/check-srpp-report-authority.py --srpp-root /Users/steven/Project/SRPP/.claude/worktrees/srpp-schedule-check-42107e --check
python3 -B scripts/generate-srpp-report-golden.py --srpp-root /Users/steven/Project/SRPP/.claude/worktrees/srpp-schedule-check-42107e --check
python3 -B scripts/generate-srpp-long-short-exit-golden.py --srpp-root /Users/steven/Project/SRPP/.claude/worktrees/srpp-schedule-check-42107e --check
ruby scripts/tests/srpp-report-policy-register-test.rb
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -Dtest=SrppReportModuleCalculatorTest,DecisionPolicyAuthorityTest,DailyDecisionCalculatorTest test
shasum -a 256 backend/src/main/resources/srpp/decision/policy_manifest.json /Users/steven/Project/SRPP/.claude/worktrees/srpp-schedule-check-42107e/assumptions/policy_manifest.json   # 兩者須相同
git diff --check
```

正式發布另核對兩端 main refs、insert-only row、business-services 重建／重建後運行映像、JAR 內嵌 bytes 及從 SRPP 實測 9090；新hash的正式日報採用為 NOT_YET_EXECUTED，不補跑日報、寄信或下單。

## 完成報告

待實作及正式驗收。
