# [t567] 美股報告提前至美東09:35的SRPP規則包身分發布

**對應 Requirements:** Requirements 187／193／199（不可變報告計算、正式authority與美股核心計算）
**前置任務:** t566
**Liquibase changeset:** 無

## 背景

使用者明確要求將Codex既有美股報告`22-00`的啟動時段由美東09:45提前至09:35（台北夏令21:35／冬令22:35，守門員同步），並要求同步SRPP後執行完整規則包發布。這次只是SRPP政策文字與共用標籤的同步，資產端只換規則包身分；9090 `us-report-calculations`的伺服器契約不屬本任務且全部維持：`reportSlot=US_OPEN_0945`、背景發布自NY09:40、`latest`發布窗NY09:45至10:45、`displayReportTime`列舉21:45／22:45、Swagger與schema鏡像、驗證器的NY09:45下限。09:35起跑的報告若到API步驟時實際驗證時間早於NY09:45，該輪為發布窗未開的API具名缺口（尚無可採用的已驗證包；NY09:40起的預備包不供09:45前報告採用），已在窗內則照原契約採用；本任務不替伺服器改窗，也不宣稱09:35起跑已能採用API。

## SRPP凍結來源

root `/Users/steven/Project/SRPP/.claude/worktrees/us-stock-report-time-adjust-3a94f8`，基準為SRPP main／origin/main `25e10b7`。未提交變更共9個檔案：三份文件（`docs/schedule_entries/README.md`、`docs/US_REPORT_EVIDENCE.md`、`docs/CORE_SELL_GUARD.md`，時段文字與具名銜接邊界）；兩支共用腳本`political_decision_overlay.py`、`report_evidence_guard.py`（報告標籤集合同時接受21:35／22:35與舊21:45／22:45）；兩個測試`test_political_decision_overlay.py`（新標籤案例）、`test_verify_srpp_us_report_calculations.py`（fallback `slot`不等於包`displayReportTime`即`US_FALLBACK_SLOT_MISMATCH`的負向鎖）；以及原工具重產的`assumptions/daily_runtime_contract.json`（SHA `6e1db86abafaf2d173eb6464197d75e5a081a14ea83cac3fd02fca5e44829725`）與`assumptions/policy_manifest.json`（SHA `939f28bfce8697018f52dee906364c2c2f3e4bd55b3735b82417cfda5ace546a`）。新bundle `96312bf7f261f86fb6e074713100288200192d02fa608b08650f69f7c730ae65`，舊bundle `bedb78fffc74ea38b04f848676d53448e27cc30e0c0fb7b459b3aeb8ec438254`。`model_inputs.json`（`64e3f9fc…`）、Swagger（`a7ce2c89…`）、`core_sell_guard.py`（`904c13ef…`，即原calculator hash）全文不變。

標籤分兩種：報告主旨、資料目錄與證據身分用21:35／22:35，上述兩支腳本同時接受舊標籤；`core_sell_guard.py`的美股CLI只有`--us-package`後備，`slot`必須等於已驗證包的`displayReportTime`（目前21:45／22:45），所以該腳本未動。Java標籤集合維持只有21:45／22:45是刻意的：`UsReportPublisher`以`UsReportCalendar.displayTime`伺服器自產，沒有任何外部slot輸入。日後若要調整`displayReportTime`或發布窗，須另開任務同步`UsReportCalendar.displayTime`、`UsCoreSellCalculator`與`UsTacticalCalculator`的標籤判斷、SRPP `scripts/core_sell_guard.py`的美股slot集合、BFF `SrppUsReportResponseValidator`、SRPP與BFF schema列舉、Swagger及`verify_srpp_us_report_calculations.py`。

## 要做什麼

- [x] 567.1 先spec-check及作者以外的唯讀spec審查，記錄通過後才同步backend。來源只用上述root凍結。D211／policy2.60.0／model3.22.0維持；model_inputs、formula manifest、formal targets、Swagger、Java計算器與`core_sell_guard.py`bytes必須不變，不新增分數常數、交易權限或硬閘門例外。Task 504的Swagger renderer／identity sync鏈因Swagger未變而不適用，以spec-check的mirror parity為證。
- [x] 567.2 同步凍結 `assumptions/policy_manifest.json` 原始bytes至backend決策resource，model同值就不改。原Ruby stdout產受追蹤d201-registration.sql，原Python重產report-same-input-golden與report-long-short-exit-golden，不手改expected、不更動generator。預期差異：SQL只有bundle字串一處；same-input golden只有`policyBundleSha256`一行；long-short golden只有bundle一行；calculator hashes不變；state-topup、US parity、income-payroll、short-us等其他golden位元組不變。作者以外的arch審查完整diff，不新增Java／API／DTO／DB／排程。
- [x] 567.3 authority gate、golden checks、Ruby registry、authority／long-short／state-topup／core-scope-consumer負向回歸、focused Java與backend package通過；保存本輪新的source／artifact hashes及實際輸出，不能沿用上輪容器或收據作新proof。
- [x] 567.4 使用者明示順序為離線驗收→雙repo各feature commit／main no-ff merge／push及精確remote讀回→已合併SQL insert-only registry。精確核新hash的policy_bundle_sha256／formula_version／policy_document／formula_manifest四欄全文，所有舊rows保持；不UPDATE／DELETE歷史資料。SRPP合併main後，下個台股交易日09:05的日報即使用新bundle：registry與business-services重建完成前report-calculations會回409 `POLICY_UNSUPPORTED`而退回本機計算器，所以本輪須在下個交易日09:05前完成；本次為週日，無交易日窗。日報與Codex排程（22-00、22-00-2的cwds）實際使用的 `/Users/steven/Project/SRPP` 目前不在main（在`codex/us-radar-universe-20261102`，等於舊main），因此SRPP合併並通過gate後，須以`git merge --ff-only main`安全快轉該工作區並保留其未追蹤資料，否則下個交易日09:05與22:35仍讀舊文件與舊腳本。回滾方式：registry為insert-only，新舊row並存；回滾即revert兩邊merge並從main重建business-services（對應舊bundle `bedb78ff…`），新舊SRPP與JAR須同進退。
- [x] 567.5 由委派run-stack從乾淨正式asset main與原.env／mount／network設定，只build／force-recreate business-services；先核實真Compose project與活動報告／啟動工作，並以唯讀SQL證明啟動入口皆為no-op（今日analysis OK、owner快照當日已roll、各匯出`last_run_date`為今日、備份、`DIVIDEND_BACKFILL_ENABLED=false`），避開約11:46至11:55與21:25至21:43的匯出排程分鐘。保留其他容器與BFF、原設定及有界health／safeGET流程。驗運行JAR五份authority與合併來源全文一致、計算器class保留，從SRPP host以新hash作唯讀業務GET並保存實際HTTP／原回應；不在時窗外冒稱成功採用，正式日報採用仍須合法入口及完整required_reads／同owner L0／verifier。無人工producer、刷新、報告、Gmail或交易。下一次09:35報告為下個交易日台北22:35（冬令），其API讀窗為台北22:45至23:45。重建後的503 `CALENDAR_UNAVAILABLE`／`CONTEXT_NOT_READY`屬正常，先跑`verify_9090.py`填日曆快取，不算失敗，也不手動POST或觸發producer。週日對精確新hash的業務GET只是診斷（t566先例：TW 400 `INVALID_REQUEST`、US 409 `NON_TRADING_DAY`），不是成功採用。合併後在SRPP main重核manifest SHA-256 `939f28bf…`與`policy_bundle.py check`；若SRPP main於合併前被推進而動到列管來源，凍結hash失效，須重建contract與bundle並重走本任務。

## 驗證

```bash
TMP=$(mktemp -d)   # 兩個repo之外的暫存目錄
mkdir -p "$TMP/scope"
bash scripts/spec-check.sh
python3 -B scripts/check-srpp-report-authority.py --srpp-root /Users/steven/Project/SRPP/.claude/worktrees/us-stock-report-time-adjust-3a94f8 --check
python3 -B scripts/generate-srpp-report-golden.py --srpp-root /Users/steven/Project/SRPP/.claude/worktrees/us-stock-report-time-adjust-3a94f8 --check
python3 -B scripts/generate-srpp-long-short-exit-golden.py --srpp-root /Users/steven/Project/SRPP/.claude/worktrees/us-stock-report-time-adjust-3a94f8 --check
python3 -B scripts/generate-srpp-state-topup-golden.py --srpp-root /Users/steven/Project/SRPP/.claude/worktrees/us-stock-report-time-adjust-3a94f8 --check
python3 -B scripts/generate-us-srpp-calculator-golden.py --srpp-root /Users/steven/Project/SRPP/.claude/worktrees/us-stock-report-time-adjust-3a94f8 --output "$TMP/us-calculator-parity.json" && cmp "$TMP/us-calculator-parity.json" backend/src/test/resources/srpp/us-calculator-parity.json
ruby scripts/tests/srpp-policy-register-test.rb
ruby scripts/tests/srpp-report-policy-register-test.rb
SRPP_RELEASE_WORKTREE=/Users/steven/Project/SRPP/.claude/worktrees/us-stock-report-time-adjust-3a94f8 python3 -B scripts/tests/check-srpp-report-authority-test.py
SRPP_RELEASE_WORKTREE=/Users/steven/Project/SRPP/.claude/worktrees/us-stock-report-time-adjust-3a94f8 python3 -B scripts/tests/generate-srpp-long-short-exit-golden-test.py
SRPP_RELEASE_WORKTREE=/Users/steven/Project/SRPP/.claude/worktrees/us-stock-report-time-adjust-3a94f8 python3 -B scripts/tests/generate-srpp-state-topup-golden-test.py
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -Dtest=SrppReportModuleCalculatorTest,DecisionPolicyAuthorityTest,DailyDecisionCalculatorTest,SrppReportShortUsCalculatorTest,UsCoreSellCalculatorTest -Dsrpp.core.scope.exportDir="$TMP/scope" test
SRPP_RELEASE_WORKTREE=/Users/steven/Project/SRPP/.claude/worktrees/us-stock-report-time-adjust-3a94f8 SRPP_CORE_SCOPE_FIXTURE="$TMP/scope/core-scope-consumer.json" python3 -B scripts/tests/srpp-core-scope-consumer-test.py
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -DskipTests package
shasum -a 256 backend/src/main/resources/srpp/decision/policy_manifest.json /Users/steven/Project/SRPP/.claude/worktrees/us-stock-report-time-adjust-3a94f8/assumptions/policy_manifest.json   # 兩者須相同
git diff --check
```

本任務無Java變更，上述focused測試外另補跑不涉資料庫的 `SrppReportExtraCaptureAdapterTest`、`SrppReportStateTopupCalculatorTest`、`SrppRadarPolicyCompatibilityTest`、`DailyDecisionCaptureServiceTest`、`DecisionInputCaptureAdapterTest`；使用Testcontainers的 `SrppDecisionRepositoryPostgresTest` 不在本輪執行。驗證指令綁定凍結root路徑，該worktree清理後以合併後main路徑重跑manifest SHA readback。

SRPP端另在凍結root執行 `python3 scripts/policy_bundle.py check --purpose daily --consumer Codex` 與 `python3 scripts/daily_runtime_contract.py check`。雙repo落地及registry後，僅從正式main使用 `docker compose -p asset-management build business-services` 與 `docker compose -p asset-management up -d --no-deps --force-recreate business-services`；真project先由現行container labels確認。無DB schema變動，不重產schema.sql。收據放 `/Users/steven/Project/SRPP/data/output/diagnostics/2026-11-08-us-0935-release/`，不存秘密原文。

## 完成報告

2026-11-08（Asia/Taipei）完成Codex美股報告提前至美東09:35（台北夏令21:35／冬令22:35）的SRPP規則包身分發布。Codex App既有`22-00`與守門員`22-00-2`已先行改為21:35／22:35與22:05／23:05並由App讀回；Claude沒有美股排程，未建立。**正式日報採用仍為 NOT_YET_EXECUTED；本輪業務GET為非交易日診斷，api_accepted=false。**

- **範圍與身分。** D211／policy2.60.0／model3.22.0維持，新bundle `96312bf7f261f86fb6e074713100288200192d02fa608b08650f69f7c730ae65`（舊 `bedb78ff…`）。SRPP九個檔案：三份文件、`political_decision_overlay.py`與`report_evidence_guard.py`（報告標籤同時接受21:35／22:35與舊21:45／22:45）、兩個測試及原工具重產的runtime contract與manifest。`core_sell_guard.py`、model_inputs、Swagger、formula manifest、formal targets與全部Java未動；資產端只有embedded manifest、原Ruby產SQL與兩份原Python產golden各換bundle一處，calculator hashes與所有金融expected不變。
- **審查。** 第一輪spec審查抓到major：我原先放寬`core_sell_guard.py`的標籤集合並在文件寫成可用，但該腳本的美股CLI只有`--us-package`後備，`slot`必須等於包的`displayReportTime`（21:45／22:45），照文件填21:35／22:35會`US_FALLBACK_SLOT_MISMATCH`。已撤回該腳本與測試、改寫文件並加負向測試，重建contract與bundle（作廢的中間hash `4d817b41…` 未登錄）。第二輪spec審查與作者以外的arch審查皆critical0／major0。arch審查的minor中，實際工作區快轉、回滾方式、503說明、驗證清單補充、`core_sell_guard.py`同步清單已寫入本spec；該段文字是審查後依建議加入的措辭，spec審查紀錄的雜湊早於這些補充，未另行重審。
- **離線驗收。** spec-check BLOCK0／CHECK0；authority gate、report／long-short／state-topup golden `--check`（111／15／22案）、US parity `cmp`位元組相同；Ruby 8＋5項、Python authority 4＋long-short 5＋state-topup 5＋core-scope consumer 3項；focused Java 56項與額外五個不涉資料庫的類別41項、`mvn package`成功。`SrppDecisionRepositoryPostgresTest`使用Testcontainers，本輪未執行。SRPP端`test_data_tools`有一個既有失敗（d208／d209／d211測試檔未列入盤點），在未改動的主checkout同樣失敗，非本輪造成。
- **Git與registry。** SRPP feature `7a7b794`、main／remote `48f1f5d`；資產feature `26f2e3bf`、main／remote `27d0cedd`。兩repo各no-ff merge與push並以`ls-remote`讀回；日報實際使用的`/Users/steven/Project/SRPP`原在`codex/us-radar-universe-20261102`，已`--ff-only`快轉到`48f1f5d`並保留未追蹤資料。之後由已合併SQL以單一交易insert-only登錄，registry 37→38列，舊37列全文與`registered_at`不變，新列`formula_version`／`policy_document`／`formula_manifest`與前一版相同、只有bundle不同。
- **部署。** 從乾淨資產main只build與force-recreate `business-services`：container `f7fdd19a68b2`→`ccfe42eca22e`，image `af9be28b03ad`→`aa56a77b3e4d`，JAR SHA-256 `ed354dd7c622c067c8bf417d4f8f5cc2d07e3f115239d3e64052541bf858dae9`，healthy。運行中JAR的manifest、model_inputs、Swagger、`d201-registration.sql`、`d201-policy-document.json`與合併來源逐位元相同，舊bundle出現0次，US計算器與排程類別仍在；新舊JAR 2200個條目只有manifest與SQL兩項不同。有效設定（compose、47個env鍵、mounts、network、healthcheck、restart、ports）與重建前相同，其他8個容器id、image id與啟動時間不變，BFF未重啟。啟動為週日，分析、producer、備份、匯出皆為no-op；實際執行的只有api_error_log保留期刪除3筆與市場分析self-heal的非交易日判斷（未產生分析、未寄信）。首兩次委派因子代理模型名稱無法解析在第一個請求即失敗、未做任何動作，第三次明示模型後完成。
- **SRPP實機9090。** 13:12由SRPP主機執行既有`verify_9090.py`：日曆365日HTTP200有效、TWSE 21列有效、台股報價HTTP200但**0筆而判FAIL**，整體收據為FAIL。空陣列早於本次重建：網關日誌顯示同端點在11/08 00:04（台北）已回200、2位元組空陣列；`/api/quotes`經BFF讀`external-materials-service`的Redis快取、不經`business-services`，兩者皆未動，判斷為週末快取過期的既有狀態，需於下個交易日報價就緒後複查。以本輪精確新hash對Codex／Claude各做`report-calculations`的2026-11-08 09:05診斷GET，兩者皆HTTP409 `NON_TRADING_DAY`（非`POLICY_UNSUPPORTED`），不等於正式採用。沒有人工producer、刷新、報告、寄信或交易。
- **未完成與限制。** 新JVM內authority與registry的runtime比對、09:35起跑時API步驟落在NY09:45至10:45窗內與窗前的實際行為，均須待交易日實跑（下一次為週一台北09:05台股、22:35美股，後者API讀窗22:45至23:45）；報價複查須在週一09:05前或由日報L0自行驗證。舊image `af9be28b03ad`本機已不存在，回滾須從main或`2e8abd41`重建。

收據在 `/Users/steven/Project/SRPP/data/output/diagnostics/2026-11-08-us-0935-release/`：`registry-before.jsonl`、`registry-after.jsonl`、`registry-insert.out`、`deployment-preflight.json`、`deployment-verification.json`、`deployment-startup-observations.log`、`jar-extract/`、`9090-connectivity.json`、`business-readback-receipt.json`與兩份原始回應。

本段只回填已完成實證，未修訂功能契約、backend runtime資源或SRPP來源；依CLAUDE.md「只是回填任務完成報告」明文例外處理，不為文件提交重建bundle、SQL或映像，不重啟容器。已部署實作身分以上述`27d0cedd`為準，不預填未來文件commit SHA。
