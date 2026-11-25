# [t636] 發布Codex早午報原生入口parity與固定設定保留檢查

**對應 Requirements:** Requirement 187（SRPP不可變日報計算包規則身分與同輸入一致性）
**前置任務:** 無
**Liquibase changeset:** 無

## 背景

早午報受版控canonical入口與App原生prompt曾漂移，僅驗政策hash無法證明當前排程使用完整正文。2026-11-25 22:15:58由操作者經原生入口貼回完整canonical prompt，當時僅四個固定欄位有before證據，不能補稱全欄未變。本輪新增可重複檢查與從capture起算的完整固定欄位baseline；不改canonical ENTRY、SHARED_RULES、時段、模型、金融規則、required_reads或runtime contract，不以新baseline追溯證明舊操作。

## 要做什麼

- [ ] 636.1 SRPP新增codex_daily_entries.py與focused tests。只讀兩個精確入口codex-srpp-09-30／codex-srpp的TOML及當前SQLite；CODEX_HOME未設或空白採家目錄.codex，優先sqlite/codex.db，只有路徑不存在才fallback codex-dev.db，存在但錯schema／broken symlink／directory／無資料不fallback。以readonly connection及query_only查實際欄位、不假設started_at、不寫原生設定。逐入口聚合錯誤，不能一入口失敗就漏另一入口。
- [ ] 636.2 以SHARED_RULES既有id/name/slot表和完整ENTRY替換<時段>取得canonical prompt，分別核TOML與DB全文（只strip外側空白）、exact id/name、ACTIVE、唯一專案根、原週一至五slot rrule。RRULE僅接受FREQ、INTERVAL、BYDAY、BYHOUR、BYMINUTE、BYSECOND閉合集合；可接受欄位順序、RRULE:前綴及INTERVAL=1／BYSECOND=0明示預設，拒絕COUNT、UNTIL、BYMONTH及任何額外限制／未知key、重複key，TOML與DB各自必須等於同一個canonical週一至五slot語意；baseline在限制存在後capture亦不得洗成PASS。逐入口TOML與DB的非空model／reasoning_effort必須一致，execution_environment存在時相同；不修改這些欄位。檢查結果不授權金融gate、不證明report ready／已執行／已交付，Claude remote保持NOT_EVALUATED／UNKNOWN且獨立回報。
- [ ] 636.3 prepare只在全新且非原生automations/sqlite目錄建立canonical prompt與preparation baseline，不覆寫已存在目錄、不改TOML或DB。baseline包含實際capture timestamp、template/rules SHA與bundle reference，以及當時可讀TOML/DB全部固定欄位逐值hash；明確排除prompt和updated_at／last_run_at／next_run_at／next_run_nominal_at／model_authored_prompt等動態欄位。檢查有baseline時核其schema、source binding、時間及每個入口固定欄位hash集合；缺失／未來／來源漂移／新增刪除欄位或value變更具名失敗，不自動重capture洗成PASS。無baseline最多PARITY_PASS_SETTINGS_UNVERIFIED／SETTINGS_VERIFICATION_UNPROVEN；baseline只能證明capture之後，不得追溯22:15:58前操作。
- [ ] 636.4 verify_runtime_policy.py新增明示--scope codex，只執行上述Codex入口檢查／prepare，禁止同時混用Claude remote選項；預設full保留原strict行為。文件要求發布／起跑維護pre/post entry parity，未通過不得稱report ready，entry parity與金融L0／資料／草稿採用各自驗證。canonical ENTRY及SHARED_RULES原bytes不變，不改原生schedule或模型。
- [ ] 636.5 資產端僅同步本輪最終SRPP manifest/model原bytes，原generator產formal document、insert-only SQL及兩份golden，原export同步固定24來源與runtime-manifest並--check；預期sim僅manifest與其runtime-manifest身分變更。backend model/formal/Swagger/formula/金融Java程式不改；111/15 golden除bundle外完整JSON及calculator hashes不變。ignored日曆只複製既有原bytes作離線fixtures，不refresh、不simulate、不validate或啟動producer。
- [ ] 636.6 spec依三輪收斂規範修完並記錄、arch依既有門檻及相關測試通過後，兩repo feature commit、main no-ff merge/push/readback，再執行merged tracked SQL insert-only登錄並核新row全文及舊rows不變。從乾淨asset main正式設定build/recreate必要服務；本輪sim身分更新須驗其實際runtime bytes。部署前保存完整HostConfig及Config等nonsecret逐欄hash baseline，after精確比對預期外設定、完整Env pairs／設定env、mounts與無關containers；上一輪HostConfig差異未知維持UNKNOWN，不追溯洗成PASS。只從SRPP實機三維護GET，不補跑日報、不建草稿、不寄信、不下單。
- [ ] 636.7 22:30起hold runtime原工作區改源、registry及正式containers，直到操作者精確確認22:35真US report完成；hold期間可完成隔離feature產物與離線測試。不得為發布打斷／重跑主報告、延長時限或混用新舊來源；自然早午報採用與US本輪結果另據真run收據回報。

## 驗證

```bash
python3 scripts/check-srpp-report-authority.py --srpp-root /Users/steven/.codex/worktrees/codex-entry-parity-20261125/SRPP --check
SRPP_RELEASE_WORKTREE=/Users/steven/.codex/worktrees/codex-entry-parity-20261125/SRPP python3 -B scripts/tests/check-srpp-report-authority-test.py
ruby scripts/tests/srpp-report-policy-register-test.rb
ruby scripts/tests/srpp-policy-register-test.rb
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home /usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -Dtest=SrppPolicyDocumentTest,SrppPolicyRegistryServiceTest,SrppReportModuleCalculatorTest,SrppSettingsServiceTest,SrppSettingsAuthorityValuesTest test
PYTHONDONTWRITEBYTECODE=1 python3 -B /Users/steven/.codex/worktrees/codex-entry-parity-20261125/SRPP/scripts/export_settings_runtime.py --destination /Users/steven/.codex/worktrees/codex-entry-parity-20261125/asset-management/srpp-simulator/runtime --check
```

最終SRPP凍結bundle為`981a3ba582f2f49f12512e398b2c0ca1383f2dd57d1993f5c7ac216cfad79d6c`（261 files）；18 focused及相關合計96 tests已由主操作者回歸通過，仍不代表自然報告採用。SRPP focused測試涵蓋全文prompt、TOML/DB獨立漂移、壞primary SQLite檔錯誤與缺失row聚錯、prepare不寫原生、baseline capture後固定欄位新增／刪除／變更、未來／缺offset／source-binding漂移，以及Codex scope與原full獨立。有效SQLite但缺必要欄位的schema分支，本輪未獨立測試；不得把另一支US守門員的schema測試或壞檔測試稱為此分支驗收。baseline不得追溯歷史是工具輸出與文件的明示界線，不宣稱存在專門驗證歷史操作的測試。runtime純verify_runtime與SettingsContext.descriptor以-B執行，不能生成__pycache造成固定file-set漂移，不呼叫simulate或validate。正式build/recreate必在上述US hold解除、兩repo落地與registry驗證後依實際必要服務執行，再驗healthy與運行authority原bytes。

## 完成報告

準備中；未宣稱新baseline證明歷史全欄保留、Claude remote已驗證或日報已交付。角色修正由本session使用者授權Codex，獨立CLI審查仍必須。
