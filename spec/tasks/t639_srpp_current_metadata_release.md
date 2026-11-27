# [t639] 早報正文現行政策版本修復的規則包配套發布

**對應 Requirements:** Requirements 187／193（不可變計算包與正式政策身分一致性）
**前置任務:** t637、t638
**Liquibase changeset:** 無

## 背景

2026-11-27 Codex 09:05 thread 01a21a8c-94fe-7a11-9474-e72ea1776ba1在09:13以STOPPED_L0結束；CURRENT_PLAN現行D236／2.78.0與獨立政策版本2.76.0矛盾，DAILY內文另殘留現行D234 tuple。hash check PASS只證明bytes被列管，沒有排除此語意衝突。本輪不補產報告或草稿。

## 要做什麼

- [ ] 639.1 等唯一來源 `/Users/steven/.codex/worktrees/morning-metadata-20261127/SRPP` 凍結。源端修正上述正文宣告並新增不受header／H2位置漏網的語意回歸，保留歷史章節及既有日曆/時限/L0硬閘。以rules/runtime/Codex/Claude/model三consumer檢查來源；記bundle、manifest/model/Swagger完整hash，不同步未凍結中間版。
- [ ] 639.2 資產端精確同步凍結manifest bytes；model與Swagger原bytes保持。原formal policy canonical投影、formula canonical與calculator source hashes不變；只有產物中的bundle metadata可更新。使用既有generator產policy document、insert-only registration SQL及兩份golden，逐case金融expected完全不變；有非metadata變化即停止查證，不自行重算投資規則求PASS。
- [ ] 639.3 authority gate、golden/formula checks、兩Ruby registry generator測試與backend authority/module相關測試通過，保存stdout、exit code及diff；不更改Java/BFF/API/producer/排程或任何券商權限。
- [ ] 639.4 最終spec定稿後獨立Codex唯讀審查與gate；本task完成狀態與正式發布結果寫external receipts，避免gate後再改spec。兩repo feature commit／main no-ff merge／push與remote refs核對後才merged SQL insert-only registry；新row全文及歷史rows未變另驗。
- [ ] 639.5 正式main設定rebuild/recreate必要business-services，驗新image/container healthy、設定/無關容器保留與JAR model/manifest/Swagger/SQL全文一致。SRPP host按Swagger維護GET連線檢查由主控執行；正式日報採用NOT_YET_EXECUTED，不建草稿、不寄信、不交易、不手動觸發producer。

## 驗證

以下SRPP_FEATURE為上述唯一絕對feature路徑；mvn依本機已配置JDK25執行。正式Git/SQL/部署在主控授權後由release operator續作，準備階段不執行SQL或改正式容器。

```sh
bash scripts/spec-check.sh
python3 -B scripts/generate-srpp-formula-v3.py --srpp-root /Users/steven/.codex/worktrees/morning-metadata-20261127/SRPP --check
python3 -B scripts/check-srpp-report-authority.py --srpp-root /Users/steven/.codex/worktrees/morning-metadata-20261127/SRPP --check
python3 -B scripts/generate-srpp-report-golden.py --srpp-root /Users/steven/.codex/worktrees/morning-metadata-20261127/SRPP --check
python3 -B scripts/generate-srpp-long-short-exit-golden.py --srpp-root /Users/steven/.codex/worktrees/morning-metadata-20261127/SRPP --check
ruby scripts/tests/srpp-report-policy-register-test.rb
ruby scripts/tests/srpp-policy-register-test.rb
SRPP_RELEASE_WORKTREE=/Users/steven/.codex/worktrees/morning-metadata-20261127/SRPP python3 -B scripts/tests/check-srpp-report-authority-test.py
mvn -q -f backend/pom.xml -Dtest=DecisionPolicyAuthorityTest,SrppReportModuleCalculatorTest,SrppSettingsAuthorityValuesTest,SrppPolicyRegistryServiceTest test
git diff --check
```

## 完成報告

準備與正式發布進度及精確hash/測試結果由本輪external receipts記錄：`/Users/steven/.codex/release-receipts/srpp-morning-metadata-20261127/`。此task在最終spec gate後不為回填狀態改寫；原早報STOP與未交付保持，維護通過不代表已補報或正式日報已採用。
