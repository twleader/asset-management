# [t535] 同步 SRPP 邏輯修正並對齊完整持有期退出

**對應 Requirements:** Requirement 187（不可變報告包與純讀採用）、Requirement 193（規則包 authority 可重現發布）
**前置任務:** t534（D-205 與已結清帳本身分已進 main）
**Liquibase changeset:** 無

## 背景

SRPP 正修復五項買賣邏輯矛盾，來源 worktree 是 `/private/tmp/srpp-logic-consistency-20261102`。投資政策維持 D-205／2.54.0／模型3.22.0，model 僅修正舊 D-118 文字3%→1.5%及 VOO／VT敘述，數值不變；兩支 Python 的修正包含長期債券短線退出須查證完整持有期、熊市月頻先限制總股票容量再優先補高股息。資產專案不能先發布尚未凍結的 hash，也不能因來源邏輯修正而任意改 Java 公式或金融 expected。

資產 Java 純函式亦有相同的 `reference_date < reference_start` 直接 EXIT_BLOCKED 分支，因此本任務除同步來源與產物，亦已獲授權對齊同一個完整持有期退出缺陷，保留原門檻與缺來源拒絕。尚未授權正式部署、資料庫登錄或金融 API。資產來源起點為 main `f175ea5b7a1dc11f89afad184ce8b715d402a6b0`，獨立 worktree `/private/tmp/asset-srpp-logic-authority-20261102`、分支 `codex/srpp-logic-authority-20261102`。

## 要做什麼

- [x] 535.1 編輯前掃描所有 worktree 的相同路徑未提交及未合併變更，確認 task535 無撞號；完成獨立 spec-review 並記錄。收到來源負責者的最終凍結通知後才進行後續同步。
- [x] 535.2 在同一絕對 SRPP root 驗證 daily policy 的 Claude／Codex 與 runtime contract，記錄 source feature／main 或當下凍結 commit、model raw SHA、manifest raw SHA、bundle SHA。逐位元複製兩份 JSON 到 `backend/src/main/resources/srpp/decision/`；保留 strict JSON／raw-byte／JCS／十欄候選語意閘門。對比 source model，確認所有數值與正式 targets 不變，差異只限已授權文字。
- [x] 535.3 執行原 `scripts/srpp-report-policy-register.rb`，stdout 逐位元寫回 `backend/src/main/resources/srpp/report/d201-registration.sql`；只容許 insert-only 與 `ON CONFLICT DO NOTHING`，絕不執行 SQL。原四支 generators 重產 `report-same-input-golden.json`、`report-state-topup-golden.json`、`report-short-us-full-golden.json`、`report-income-payroll-golden.json`。所有 Python 執行明示 `-B` 或 `PYTHONDONTWRITEBYTECODE=1`，禁止向 SRPP 寫入 bytecode。主 report／state-topup 明示 `--srpp-root`；short-US／income-payroll 無來源參數，原工具執行前先核對 `/Users/steven/Project/SRPP` 已由來源負責者安全同步至同一 commit，或正常 no-ff merge 前後的相同 tree，且 model、完整 scripts、`pre_retirement_payroll.json` 及2026日曆 bytes 相同。無法安全同步 primary 時才於記憶體替換來源常數，記錄原工具 SHA，保持原 `__file__` 和產生器內容，不改來源或工具。
- [x] 535.4 與本任務起點的四份 golden 比對完整 cases 逐值相同；允許差異僅為 bundle 與實際變更來源計算器的 hash，不預設哪些 hash 必變。任何金融 expected、case 輸入或未授權 metadata 差異都停止回報，不得改期望掩蓋。正式 targets document、其餘 Java 公式、Swagger 完整 bytes 維持；不修改 API、schema、producer、排程或金融門檻，不呼叫金融 API、不寄信、不交易。
- [x] 535.5 僅修同一完整持有期退出缺陷：`SrppReportShortUsCalculator` 保留60日進場窗；當鎖定日期早於該窗時，從既有已凍結 `completedSessions`／calendarVerified／currentSessionVerified、quote chart 的完整已完成日期／收盤價、dividendHistory 驗持有期。鎖定日為交易日需保留當日，否則保留前一完成日；缺舊bar或日曆／來源不完整維持阻擋。僅用無衝突且去重配息扣減原 locked support／pressure，不重設原 entry／reference／holding sessions 或120日門檻；同日配息異值、股票股利仍 EXIT_BLOCKED 且凍結新買進。只有原退出trigger已成立時，BUY反向或非空actionGateReasons才否決退出；沒有trigger的61／119日部位保持HOLD／未凍結，不因此新增觸發。原Python fixture的60日close全50仍可分析原locked45／60退出，故僅將共用 `SrppReportModuleCalculator.shortScenario` 的輸入域允許 `pressure==support`，仍拒絕 `pressure<support` 與非正價格／股數；所有費稅、風報比公式原樣。`shortEntryReasons` 嚴格區間與淨風報比硬閘保留，明確驗等號情境 `NO_SUPPORT_PRESSURE_INTERVAL`、share ceiling=0／mayRecommend=false，反向區間仍拒絕；120日部位只按原locked45／60及完整持有期觸發退出。pending sell room及核心／短線股數隔離不變，不啟用 canonical feed或新增IO。
- [x] 535.6 新增原Python輸出的獨立持有期 golden及聚焦Java parity測試，不改原13例。新增 `scripts/generate-srpp-long-short-exit-golden.py` 必要 `--srpp-root <absolute>` 與唯讀 `--check`，禁止bytecode，先驗同 root官方daily/Codex bundle及embedded bytes，再用其 `test_long_short_term_exit.py.LongShortTermExitTests` 合成fixture與實際 `short_term_opportunities.screen` 產生 `backend/src/test/resources/srpp/report-long-short-exit-golden.json`。暫存日曆只寫系統暫存區；輸出記錄bundle、原計算器與fixture SHA；check須完整bytes相同、失敗非0且零寫入。至少涵蓋00679B／00751B各61、119、120日（6例），舊除息（調整原45／60至44／59）、鎖定日起完整資料、缺舊bar、舊配息衝突、BUY無trigger（61或119日HOLD／未凍結）及有trigger（120日EXIT_BLOCKED）、非交易日鎖定日期。Java用同一bundle比較完整選取position欄位（reference_date、entry_date、holding_sessions、shares、exit_status／reasons、locked_adjusted_support／pressure、exit_share_ceiling、pending_exit_shares／remaining_exit_room）及sleeve status／new_buys_frozen、核心歸屬，不手改expected。新增 `scripts/tests/generate-srpp-long-short-exit-golden-test.py` 隔離回歸，驗相對／缺失root、embedded bytes不符、stale hash／case拒絕，正反check對source model／manifest／相關scripts及asset authority／受追蹤fixture零寫入；只允許自有暫存fixture日曆寫入並清理。
- [x] 535.7 通過同 root 的 authority／主 report／state-topup／新增持有期 checks、authority 與 state-topup generator 回歸、Ruby registry 測試、既有聚焦 Java authority／四類 parity及新增持有期測試；執行 spec-check、diff-check 與獨立 diff-scoped 架構檢視。資料庫不可用時如實記錄無法查證，不啟動容器或修改 schema。
- [x] 535.8 依原 commit-merge-push 先完成 feature commit／push／remote-ref readback。Java與classpath authority 屬可執行變更；本輪不執行 run-stack，所以不得越過「先服務驗收再 main merge」規定，保留 feature，具名記錄 main merge、registry、JAR／runtime 驗收未執行。不部署、不重建正式容器、不執行 registry SQL，不以離線綠燈宣稱正式採用。

## 驗證

以下命令於資產 worktree 執行；SRPP 來源檢查以絕對腳本路徑選取其來源，不寫任何 SRPP 檔案。

```bash
python3 -B /private/tmp/srpp-logic-consistency-20261102/scripts/policy_bundle.py check --purpose daily --consumer Claude
python3 -B /private/tmp/srpp-logic-consistency-20261102/scripts/policy_bundle.py check --purpose daily --consumer Codex
python3 -B /private/tmp/srpp-logic-consistency-20261102/scripts/daily_runtime_contract.py check
python3 -B scripts/check-srpp-report-authority.py --srpp-root /private/tmp/srpp-logic-consistency-20261102 --check
python3 -B scripts/generate-srpp-report-golden.py --srpp-root /private/tmp/srpp-logic-consistency-20261102 --check
python3 -B scripts/generate-srpp-state-topup-golden.py --srpp-root /private/tmp/srpp-logic-consistency-20261102 --check
PYTHONDONTWRITEBYTECODE=1 python3 -B scripts/generate-srpp-long-short-exit-golden.py --srpp-root /private/tmp/srpp-logic-consistency-20261102 --check
PYTHONDONTWRITEBYTECODE=1 SRPP_RELEASE_WORKTREE=/private/tmp/srpp-logic-consistency-20261102 python3 -B scripts/tests/generate-srpp-long-short-exit-golden-test.py
PYTHONDONTWRITEBYTECODE=1 SRPP_RELEASE_WORKTREE=/private/tmp/srpp-logic-consistency-20261102 python3 -B scripts/tests/check-srpp-report-authority-test.py
PYTHONDONTWRITEBYTECODE=1 SRPP_RELEASE_WORKTREE=/private/tmp/srpp-logic-consistency-20261102 python3 -B scripts/tests/generate-srpp-state-topup-golden-test.py
ruby scripts/tests/srpp-report-policy-register-test.rb
ruby scripts/tests/srpp-policy-register-test.rb
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -Dtest=DecisionPolicyAuthorityTest,SrppReportBuildIdentityTest,DailyDecisionCalculatorTest,SrppReportModuleCalculatorTest,SrppReportStateTopupCalculatorTest,SrppReportShortUsCalculatorTest,SrppReportIncomePayrollCalculatorTest test
bash scripts/spec-check.sh
git diff --check
```

本輪禁止 image rebuild／container recreate，正式 runtime 未驗收；不可改寫為成功。完整金融 cases 比對與四組產物的原 generator 重跑另保存離線收據；不新增鏡像實作的測試。

## 完成報告

2026-11-02 已完成來源同步、同一完整持有期缺陷的 Java 對齊與離線驗收。Git 收尾的實際 feature SHA 與遠端讀回另於本輪交付訊息記錄，不預填尚未產生的提交；main merge、registry 登錄、正式 JAR／runtime 驗收均未執行。

- 凍結來源為 SRPP feature `ecccdbf03d584f4de996e11f51b8b026b5b7056d`、已推送 main `a85d4e26d3ee3eaf74ff8830af3fac9114525cd2`，tree 皆 `b79b76865406558c3c52f4db29c279f8be68ad67`。來源 worktree 乾淨；primary 與來源的 model、manifest、薪資、日曆及完整 Python scripts 共84份 bytes 相同，故原 hardcoded generators 可原樣執行，未使用記憶體替換後備。
- D-205／policy 2.54.0／model 3.22.0 不變；模型只一個 D-118 說明字串改變，所有數值／布林／結構原樣。bundle `cf3d8955a97672685b7afd370cc10675cee62733dca14dfd0188bd16029d4e22`；model raw SHA `e272c4a75c2081393548f4f05e9693985112dd10a4e3a6f3a056bb4b2bf46ceb`；manifest raw SHA `7a22b6f8f850b98d65e9b9c612cd43a5f16e626b7019f6dac9615a808d16aa05`；runtime contract SHA `8f3e802edd4ab4060e738fd27e73d90f1750b2821954ed115786e10b39feb69f`。兩個 consumer policy checks、runtime check、authority checker及原工具 SQL 完整比對皆 PASS。
- 原四組22／22／13／25個完整 cases 共82例逐值不變；state-topup與income/payroll完整產物 bytes 不變。主report僅bundle與 `short_term_opportunities.py` hash更新（`1b5c5f97db2e331970c78b6937954271389a11ae511a4110477ab58114e018f4`→`90877046a3150f5949cd806b08039565279a1f845c1d0341a9fa4445d0f0fabc`）；short-US同樣更新該來源hash，另自然更正既有 stale sleeve metadata。原 f175 golden 的 `short_term_sleeve.py` 為 `0efaae58d3eb099e876ace80b35245db5a068092bb385fe240448cdc8bcfbee4`，但 f175 tracked manifest、SRPP base `55f18293` 原始碼、已落地 main 與本輪凍結原始碼皆為 `e7909fe13ffc13ea5e4986fef92cdf35141c8b22e568845400f3ed51d397caf0`；由原generator自然修正，未手填hash或金融expected。
- Java新增完整持有期的純證據helper，保留原60日進場、原locked支撐／壓力、entry／reference／holding sessions及120日門檻。最近60日平坦資料只成為分析事實；共用helper只放行等號，反向／非正值與原新買進硬閘保留。來源null、缺舊bar、舊配息衝突及股票股利維持阻擋；未新增feed、I/O、API、schema、producer或排程。
- 獨立新golden由原Python fixture與screen產生15例：兩檔債券各61／119／120日、舊除息、鎖定日起完整資料、缺舊bar、衝突配息、BUY無／有trigger、非交易日reference、重複配息去重與股票股利拒絕。其 SHA `f1b9f23eb415bc61a2ca0d13e4b50c41b70ed7ec464ae77e8fcf8999d83e14a0`；原13例未改。新增Java負例確認平坦區間新買量0／mayRecommend=false、反向與非正值拒絕及null來源退出阻擋。
- 七類聚焦Java最終62 tests／0 failures／0 errors／0 skips；來源型別補正後受影響的ShortUs／Module兩類22 tests已重新通過，其餘五類保留同輪通過結果。Python authority4＋state-topup5＋新增generator隔離5＝14 tests全部通過；Ruby report registry5／261assertions與共用registry8／46assertions皆通過。新golden完整bytes check、Swagger renderer／identity只讀checks及diff-check PASS；Swagger SHA仍 `40819f617271e21b33efbca040134c3024e4915d1d34c735fa3b6a2489d45afe`，正式targets與其他Java公式不變。
- 獨立spec增量及最後11檔實作diff架構審查皆0 confirmed findings。機械spec-check為BLOCK0／CHECK1，唯一CHECK是當前執行環境無法查證資料庫容器／schema drift；本次無schema變更，不啟動容器或改DB補證。正式部署與登錄未授權，依專案先run-stack驗收再main merge規定保留feature；不以離線測試或靜態hash宣稱runtime採用。
