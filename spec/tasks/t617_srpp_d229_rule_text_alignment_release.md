# [t617] D-229 規則文字與回歸測試對齊D-224的SRPP規則包身分發布

**對應 Requirements:** Requirements 187／193／199（不可變報告計算、正式authority與美股核心計算）
**前置任務:** t616
**Liquibase changeset:** 無

## 背景

SRPP D-229 只讓規則文字與回歸測試追上已Accepted的D-224／D-227及機器可執行設定：①高股息四檔合計硬上限一律17%（文字原散見15%）、補足目標13.5%（原13%／13.33333333%）；②D-224取消股票總比重獨立硬上限後，熊市與修正窗口文字不再引用60%／70%天花板；③D-141熊市賣債後非緊急債券下限文字改為5%（設定值早已是5%）；④0050核心目標文字為13%；⑤版本標示對齊D-227／v2.75.0；五個回歸測試改用D-224現行數值。`assumptions/model_inputs.json`只改說明文字（五個欄位：熊市預算說明兩欄、高股息輪換與通膨訊號的17%上限說明各一欄、0050／00881豁免範圍說明一欄），所有數值、門檻、formal targets、model、formula、Swagger與schema位元組不變，policy 2.75.0／model 3.25.0維持。

這只是SRPP文字與測試的變更；資產端只換規則包身分：9090任何API、Java計算器、DTO、DB、排程、formula與Swagger都不變。`docs/`、`assumptions/`、`scripts/`列管來源改變使bundle hash改變，舊hash在新JAR上會回409 POLICY_UNSUPPORTED，因此必須登錄新hash並重建business-services。

## SRPP凍結來源

root `/Users/steven/Project/SRPP/.claude/worktrees/srpp-logic-reports-61f21f`（分支`claude/srpp-logic-reports-61f21f`，基準SRPP main／origin/main `9eb0e04`），凍結commit `2eef061`，共24個檔案（文字、五個測試、DECISION_LOG D-229、重建後的`daily_runtime_contract.json`與`policy_manifest.json`）。新bundle `6ec353ec006a09b484489a26edecbdf390ac70d448e5357e6765cb07966773f2`（舊 `565ac5c1de6f402dbfc5d872109fef843a3bdceecafe3defbad3a5291ae7b860`）；`docs/9090 Port API Swagger.md`位元組不變（SHA-256 `aaedb6b9…`）。`model_inputs.json`位元組改變（SHA-256 `7e5ac7fe…`），須同步到backend決策resource。

## 要做什麼

- [x] 617.1 先spec-check及作者以外的唯讀spec審查，記錄通過後才同步backend。來源只用上述root凍結；policy 2.75.0／model 3.25.0維持，formula manifest、Swagger、schema、targets、Java與DB均不變。
- [x] 617.2 同步凍結`assumptions/policy_manifest.json`與`assumptions/model_inputs.json`原始bytes至backend決策resource。原Ruby stdout產受追蹤d201-registration.sql，原Python重產report-same-input與long-short-exit golden（只應變bundle行），並以authority gate驗證。
- [x] 617.3 authority gate、golden checks、Ruby registry、focused Java與backend package通過；保存新的source／bytes證據。
- [ ] 617.4 順序為離線驗收→雙repo各feature commit／main no-ff merge／push及精確remote讀回→已合併SQL insert-only registry；精確核新hash的policy_bundle_sha256／formula_version／policy_document／formula_manifest且舊rows全文不變。
- [ ] 617.5 由run-stack從乾淨正式asset main與原設定，只build／force-recreate business-services；重建前以唯讀證據確認台股與美股報告均未執行中（無活動報告、守門員未在處理），證明各啟動入口為no-op，並依本輪有效排程與時區確認整段「recreate→JAR bytes讀回→日報工作區`--ff-only`快轉」不跨入台股報告與守門員窗（09:05–13:10）、美股報告窗（22:35–23:45）及各匯出排程分鐘（約21:25–21:43）；證據不足或時間不夠安全時停止並保留已落地的main與registry row，不強行切換。重建後核對容器／JAR內嵌bytes，並立即快轉日報工作區`/Users/steven/Project/SRPP`（舊hash在新JAR上回409，兩步之間不得留空窗）。從SRPP實測9090連線。

## 驗證

```bash
bash scripts/spec-check.sh
python3 -B scripts/check-srpp-report-authority.py --srpp-root /Users/steven/Project/SRPP/.claude/worktrees/srpp-logic-reports-61f21f --check
python3 -B scripts/generate-srpp-report-golden.py --srpp-root /Users/steven/Project/SRPP/.claude/worktrees/srpp-logic-reports-61f21f --check
python3 -B scripts/generate-srpp-long-short-exit-golden.py --srpp-root /Users/steven/Project/SRPP/.claude/worktrees/srpp-logic-reports-61f21f --check
ruby scripts/tests/srpp-report-policy-register-test.rb
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -Dtest=SrppReportModuleCalculatorTest,DecisionPolicyAuthorityTest,DailyDecisionCalculatorTest test
shasum -a 256 backend/src/main/resources/srpp/decision/policy_manifest.json /Users/steven/Project/SRPP/.claude/worktrees/srpp-logic-reports-61f21f/assumptions/policy_manifest.json   # 兩者須相同
git diff --check
```

正式發布另核對兩端 main refs、insert-only row、business-services 重建／重建後運行映像、JAR 內嵌 bytes 及從 SRPP 實測 9090；新hash的正式日報採用為 NOT_YET_EXECUTED，不補跑日報、寄信或下單。

## 審查與離線驗收記錄

2026-11-21：Codex spec審查第1輪CHANGES_REQUESTED（major：`rotation_policy`仍有15%文字；minor：欄位計數）已修；第2輪CHANGES_REQUESTED（major：CURRENT_PLAN／rebalancing仍有15%與60%／70%文字）已修並擴大掃除；第3輪因Codex用量上限（回覆「usage limit … Nov 24th」）無法執行，未以其他審查者代替，記為未完成的審查輪次。離線：spec-check BLOCK0／CHECK0；authority gate PASS（bundle `6ec353ec…`）；report／long-short／state-topup golden `--check` 通過且只變bundle行；Ruby registry測試6 runs 0 failures；focused Java（SrppReport*／DecisionPolicyAuthority／DailyDecisionCalculator／SrppSettings*）通過。
