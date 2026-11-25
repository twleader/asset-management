# [t636] 發布Codex早午報原生入口parity與固定設定保留檢查

**對應 Requirements:** Requirement 187（SRPP不可變日報計算包規則身分與同輸入一致性）
**前置任務:** 無
**Liquibase changeset:** 無

## 背景

早午報受版控canonical入口與App原生prompt曾漂移，僅驗政策hash無法證明當前排程使用完整正文。2026-11-25 22:15:58由操作者經原生入口貼回完整canonical prompt，當時僅四個固定欄位有before證據，不能補稱全欄未變。本輪新增可重複檢查與從capture起算的完整固定欄位baseline；不改canonical ENTRY、SHARED_RULES、時段、模型、金融規則、required_reads或runtime contract，不以新baseline追溯證明舊操作。

## 要做什麼

- [x] 636.1 SRPP新增codex_daily_entries.py與focused tests。只讀兩個精確入口codex-srpp-09-30／codex-srpp的TOML及當前SQLite；CODEX_HOME未設或空白採家目錄.codex，優先sqlite/codex.db，只有路徑不存在才fallback codex-dev.db，存在但錯schema／broken symlink／directory／無資料不fallback。以readonly connection及query_only查實際欄位、不假設started_at、不寫原生設定。逐入口聚合錯誤，不能一入口失敗就漏另一入口。
- [x] 636.2 以SHARED_RULES既有id/name/slot表和完整ENTRY替換<時段>取得canonical prompt，分別核TOML與DB全文（只strip外側空白）、exact id/name、ACTIVE、唯一專案根、原週一至五slot rrule。RRULE僅接受FREQ、INTERVAL、BYDAY、BYHOUR、BYMINUTE、BYSECOND閉合集合；可接受欄位順序、RRULE:前綴及INTERVAL=1／BYSECOND=0明示預設，拒絕COUNT、UNTIL、BYMONTH及任何額外限制／未知key、重複key，TOML與DB各自必須等於同一個canonical週一至五slot語意；baseline在限制存在後capture亦不得洗成PASS。逐入口TOML與DB的非空model／reasoning_effort必須一致，execution_environment存在時相同；不修改這些欄位。檢查結果不授權金融gate、不證明report ready／已執行／已交付，Claude remote保持NOT_EVALUATED／UNKNOWN且獨立回報。
- [x] 636.3 prepare只在全新且非原生automations/sqlite目錄建立canonical prompt與preparation baseline，不覆寫已存在目錄、不改TOML或DB。baseline包含實際capture timestamp、template/rules SHA與bundle reference，以及當時可讀TOML/DB全部固定欄位逐值hash；明確排除prompt和updated_at／last_run_at／next_run_at／next_run_nominal_at／model_authored_prompt等動態欄位。檢查有baseline時核其schema、source binding、時間及每個入口固定欄位hash集合；缺失／未來／來源漂移／新增刪除欄位或value變更具名失敗，不自動重capture洗成PASS。無baseline最多PARITY_PASS_SETTINGS_UNVERIFIED／SETTINGS_VERIFICATION_UNPROVEN；baseline只能證明capture之後，不得追溯22:15:58前操作。
- [x] 636.4 verify_runtime_policy.py新增明示--scope codex，只執行上述Codex入口檢查／prepare，禁止同時混用Claude remote選項；預設full保留原strict行為。文件要求發布／起跑維護pre/post entry parity，未通過不得稱report ready，entry parity與金融L0／資料／草稿採用各自驗證。canonical ENTRY及SHARED_RULES原bytes不變，不改原生schedule或模型。
- [x] 636.5 資產端僅同步本輪最終SRPP manifest/model原bytes，原generator產formal document、insert-only SQL及兩份golden，原export同步固定24來源與runtime-manifest並--check；預期sim僅manifest與其runtime-manifest身分變更。backend model/formal/Swagger/formula/金融Java程式不改；111/15 golden除bundle外完整JSON及calculator hashes不變。ignored日曆只複製既有原bytes作離線fixtures，不refresh、不simulate、不validate或啟動producer。
- [x] 636.6 spec依三輪收斂規範修完並記錄、arch依既有門檻及相關測試通過後，兩repo feature commit、main no-ff merge/push/readback，再執行merged tracked SQL insert-only登錄並核新row全文及舊rows不變。從乾淨asset main正式設定build/recreate必要服務；本輪sim身分更新須驗其實際runtime bytes。部署前保存完整HostConfig及Config等nonsecret逐欄hash baseline，after精確比對預期外設定、完整Env pairs／設定env、mounts與無關containers；上一輪HostConfig差異未知維持UNKNOWN，不追溯洗成PASS。只從SRPP實機三維護GET，不補跑日報、不建草稿、不寄信、不下單。
- [x] 636.7 22:30起hold runtime原工作區改源、registry及正式containers，直到操作者精確確認22:35真US report完成；hold期間可完成隔離feature產物與離線測試。不得為發布打斷／重跑主報告、延長時限或混用新舊來源；自然早午報採用與US本輪結果另據真run收據回報。

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

本輪完整來源發布與部署驗收完成，最終bundle為`981a3ba582f2f49f12512e398b2c0ca1383f2dd57d1993f5c7ac216cfad79d6c`（261 sources）。新增helper/test兩檔均受manifest hash保護；canonical ENTRY、SHARED_RULES、model、Swagger、runtime contract及金融required_reads原bytes不變。使用者原生貼回後的早午兩入口fullprompt hash分別為`a836ff7ff943b035c3c80666e3bf98992eb2743123cdf053909e494e92740650`與`c6721c417ec6f00d5efd1d5bf7f807e772578ae5f309657e685a5f9df936cef2`，當前TOML/currentDB一致，model/effort仍astra-low與sol-medium。部署前／runtime後／部署後Codex-only actual readback皆PASS，prepared-round3 capture後全部固定欄位hash未漂移；不追溯宣稱原生修改前全欄保持，該操作前僅model/effort/rrule/status四欄有before證據。

18 focused及相關合計96 tests PASS；asset authority4、Ruby6+9、Java108皆PASS；111/15 golden除bundle外完整金融JSON與calculator hashes不變。sim原export固定24來源＋runtime-manifest共25檔exact，純descriptor76fields，未simulate/validate。有效SQLite缺必要欄位分支本輪仍未獨立測，不以壞檔測試冒稱schema完整覆蓋。spec三輪CLI原結論均CHANGES_REQUESTED，前兩輪分別修RRULE與測試涵蓋宣稱；第三輪依其明許修法撤回未知schema已測宣稱，按既有三輪修完規範記錄final disposition/marker3675dfc45e2d，不送第四輪、不偽造CLI PASS。獨立arch首輪PASS0critical/major/minor，marker328e0ff68add。Claude修正角色依同session使用者明確授權Codex處理。

為避免bind來源混版，22:30起兩main/runtime/registry/容器均hold，只先推feature；精確USthread`01a21324-379e-75d1-8c40-0172421df9bb`自然22:37:50.457起跑，22:46:04.575 final、22:46:04.583 task_complete，root以唯一turn completed/error null獨立證實後才解除hold，未讀金融正文或打斷run，不代表另驗草稿。重新核refs/14frozen檔/完整容器baseline無漂移後，SRPP feature`6d258fa3ba41715fbcced19d711dabc2d59130ee`→no-ff main`dde4fc543a1e6a188368e8fcff0214c9f58289c3`；asset執行feature`e89e82039c53b98c27a747096a795f4059274d80`→no-ff main/image build source`b7d67f320b3d9613b17fe5425c06a3e02d7be4ea`，皆push/remote精確讀回。22:49:23實際SRPP runtime安全ff，351個SRPP及91個asset無關檔逐bytes保持，含當晚正常run產物。

merged tracked SQL insert-only登錄81→82，981a新row四欄全文等generator、舊81rows不變。乾淨asset main與正式env只單次build/force-recreate business-services及srpp-simulator，兩healthy；business container`b14dc4c4966d0d6f1c8f95c946a14cbfc43f11f970961cc88a2f81c5ce16e6d6`、image index`sha256:5477e04ce1434a0806182ef57ae65b35817ebab1c0570134ca623f37a103ec56`，sim container`e621edea156f76f07abbd885addd4e271c572866ecd2d1ee9100a5c8e4c180f4`、image index`sha256:806b509fbefcce2faf307182c162713f8375d66226670890bd2f96016a8273cb`。JAR5與sim/app25全文逐bytes等main，JARsha`dbd02f92729d7e961545339900f58b01da5f749991cea01b0891abe2a264ebf6`，worker health/catalog GET200、bundle981a/model/catalog hash精確及76fields。完整Config逐key/HostConfig各64keys、Env53/8與逐env-key、Compose47/2、mount4/0、8其他容器本輪保持；hostname自動IDprefix與image層級具證預期變更。首個驗收腳本錯將OCI index/platform digest要求相等，原錯保留，以同次build及本機platform inspect父子身分鏈修正，無重build/recreate/改設定。歷史Task635舊sim HostConfig未知仍UNRESOLVED，本輪完整before/after證據不回溯洗成PASS。

root正常外部Chrome fresh設定中心載入76欄／版本2.76.0，無policy mismatch畫面；使用者切到Gmail後CUA拒進階點擊即停止，未觸碰Gmail，所以UI未獨立讀fullbundle（由JAR/catalog另證），不宣稱HTTP/API原文。未按草稿/儲存/模擬/發布/登入或讀憑證。22:52:04 SRPP實機.venv三維護GET皆200非空PASS：calendar365 AVAILABLE、TWSE日線21、quotes40/40正價，僅READ_ONLY_CONNECTIVITY_ONLY。full verifier維持REMOTE_SCHEDULE_UNVERIFIED/hostPASS，Claude遠端無fresh證據仍未知；自然早午報完整讀取/資料/採納/草稿交付仍NOT_YET_EXECUTED，不因入口parity或健康升格。未補報、未refresh金融來源、未模擬、未建草稿/寄信/交易。

正式收據位於SRPP `reports/codex-entry-release-20261125/`與`/tmp/srpp-codex-entry-release-20261125/`。本段純文件驗收回填會另做feature/no-ff/push，最終文件main不同於上述image build source；除本Task純完成正文外，原13份執行／authority來源保持，不為自指SHA反覆修改或重建。
