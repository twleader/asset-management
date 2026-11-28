# [t641] 日報階段包渲染閘的規則包配套發布

**對應 Requirements:** Requirements 187／193（不可變日報計算包與正式規則身分）
**前置任務:** t640
**Liquibase changeset:** 無

## 背景

D-232規定日報先讀主體包與RULE_PACK_INDEX，每個`load=stage`階段包須在該階段前以`--pack <id>`讀完並記入`timing.json.packs_read`。2026-11-23 Claude 12:00只讀主體35批、從未讀任何階段包仍建立核心與附錄草稿；Codex 2026-11-26 12:00則讀了全部階段包。原因是沒有任何程式核對`packs_read`。使用者2026-11-28要求修正Claude軌分包讀取並完整發布。原11-23草稿與結果不改寫、不補報。

## 要做什麼

- [ ] 641.1 源端唯一feature `/Users/steven/Project/SRPP/.claude/worktrees/srpp-claude-trading-day-check-4bd010`（分支`claude/srpp-claude-trading-day-check-4bd010`）新增`scripts/stage_pack_gate.py`：`render_daily_report.py`與`daily_report_preflight.py --facts`命令列在渲染前，依`assumptions/rule_packs.json`全部`load=stage`包核對事實檔同目錄`timing.json.packs_read`（包名字串或`{"pack": id}`物件）；trading_date自2026-11-30起生效，缺包`STAGE_PACKS_NOT_READ:<ids>`、timing缺或壞、日期格式錯皆具名失敗且不渲染。兩軌同標準；只核自述紀錄，不證明已讀、不涵蓋觸發型條件包、不改投資規則或交易授權。源端CLI細節與測試由SRPP實作者交付，不宣稱資產端已測該行為。
- [ ] 641.2 等源端最後凍結（bundle `d23bc703f96d13800ec4b9103a26b87ca4053b46ed2b060b0af60377700dadde`）與rules/runtime/Codex/Claude/model檢查後才copy精確manifest bytes。model、Swagger、formal policy、formula與calculator來源hash完全不變；只更新bundle metadata，原generator產insert-only SQL與report/exit golden。各case金融expected全文相同，其他未改golden原bytes相同；若出現非metadata變化即停止查證，不擴改金融規則求PASS。
- [ ] 641.3 重新跑authority gate與其測試、formula與兩golden checks、兩Ruby registration generator測試、backend authority/module/settings/registry測試與offline package；不沿用前輪收據。必要gate失敗即停止依賴動作。
- [ ] 641.4 最終spec內容定稿後以Codex CLI做spec與arch審查並記錄gate。兩repo feature commit、main no-ff merge、push與remote refs精確讀回後，從merged main受追蹤SQL正式insert-only登錄，四欄全文比對並驗歷史rows未變；不UPDATE/DELETE舊row。
- [ ] 641.5 使用正式main設定只rebuild/recreate必要business-services，健康、新image/container、環境鍵數與實際掛載相等（以本輪實際值為準，不輸出秘密）、無關容器保留、JAR model/manifest/Swagger/SQL全文相同逐一存證。host 9090三項維護GET由主控按Swagger執行；正式日報採用為NOT_YET_EXECUTED，不建草稿、寄信、交易、POST或手動觸發producer。

## 驗證

以下都是本輪重新執行的資產端驗收；源端閘門測試結果由SRPP實作者提供，不能由資產端hash PASS取代。正式Git/SQL/部署由release operator在兩repo落地後執行，準備階段不改正式容器。

```sh
bash scripts/spec-check.sh
python3 -B scripts/generate-srpp-formula-v3.py --srpp-root /Users/steven/Project/SRPP/.claude/worktrees/srpp-claude-trading-day-check-4bd010 --check
python3 -B scripts/check-srpp-report-authority.py --srpp-root /Users/steven/Project/SRPP/.claude/worktrees/srpp-claude-trading-day-check-4bd010 --check
python3 -B scripts/generate-srpp-report-golden.py --srpp-root /Users/steven/Project/SRPP/.claude/worktrees/srpp-claude-trading-day-check-4bd010 --check
python3 -B scripts/generate-srpp-long-short-exit-golden.py --srpp-root /Users/steven/Project/SRPP/.claude/worktrees/srpp-claude-trading-day-check-4bd010 --check
ruby scripts/tests/srpp-report-policy-register-test.rb
ruby scripts/tests/srpp-policy-register-test.rb
SRPP_RELEASE_WORKTREE=/Users/steven/Project/SRPP/.claude/worktrees/srpp-claude-trading-day-check-4bd010 python3 -B scripts/tests/check-srpp-report-authority-test.py
mvn -q -f backend/pom.xml -Dtest=DecisionPolicyAuthorityTest,SrppReportModuleCalculatorTest,SrppSettingsAuthorityValuesTest,SrppPolicyRegistryServiceTest test
mvn -q -f backend/pom.xml -DskipTests package
git diff --check
```

## 完成報告

本輪external receipts：`/Users/steven/Project/SRPP/reports/stage-pack-gate-release-20261128/`。完成狀態、實際hash/refs/registry/container/9090與限制記此處；最後spec gate後不為回填狀態改本檔。原11-23 Claude午報只讀主體仍建草稿的事實不改寫，維護發布不代表補報或正式日報已採用。
