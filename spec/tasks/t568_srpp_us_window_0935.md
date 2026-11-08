# [t568] 美股背景發布與latest讀取窗提前至美東09:35

**對應 Requirements:** Requirement 199（美股 SRPP 不可變報告計算與觀察狀態）；延伸 Requirements 187／193（不可變報告計算與 SRPP 規則包 authority 發布）
**前置任務:** t567
**Liquibase changeset:** 無

## 背景

使用者於2026-11-08把Codex既有美股報告`22-00`的啟動時段由美東09:45提前至09:35（台北夏令21:35／冬令22:35，±300秒一次判定與60分鐘停止條件不變），t567已完整發布對應的SRPP規則包身分，但刻意沒有改伺服器：9090 `GET /api/public/srpp/us-report-calculations` 仍是背景發布自NY09:40、`latest`讀取窗NY09:45至10:45。上週五（2026-11-06）實跑從啟動到呼叫美股API原文只有4分50秒，所以週一2026-11-09台北22:35啟動的報告會在約NY09:40前後呼叫API，幾乎必然落在窗外（`409 OUTSIDE_REPORT_WINDOW`），核心賣方`core_sell_guard.py --us-package`本機後備也不可用（`docs/US_REPORT_EVIDENCE.md`已記為具名缺口）。使用者2026-11-08明示：背景發布與`latest`讀取窗都改為NY09:35起，並確認下列取捨：

- producer與讀取窗起點同為NY09:35；結束仍是NY10:45，與regular session取交集，半開區間。
- `reportSlot=US_OPEN_0945`與`displayReportTime`（夏令21:45／冬令22:45）字面值全部維持。它們是伺服器固定標籤，不代表窗口起點；改名會牽動`core_sell_guard.py`（calculator hash）、`model_inputs.json`（model版本）、兩個Java計算器、BFF validator、兩份schema列舉與SRPP契約，風險與時限都不允許，也沒有必要。
- 5分鐘行情新鮮度（D-108 `US_REGULAR_LAST_TRADE_5M`）及所有賣方規則、分數常數、交易權限、硬閘門一律不變；本任務只動窗口，不改任何投資規則。開盤初段（NY09:35至09:40）價格噪音由既有5分鐘新鮮度、報價預檢與核心賣方確認規則把關，不新增保護。
- 部署時機：2026-11-08（週日，非交易日）當晚執行，週一台北09:05台股日報前完成；最後才把日報實際使用的SRPP工作區快轉到新main。

producer現況語意（本任務不改）：排程每分鐘tick（`0 * * * * *`，America/New_York），但`UsReportFingerprint.hash`會排除assets／radar的`generatedAt`與`us-tactical.asof`，`UsReportPublisher`遇到同一來源指紋時回傳既有包，所以是「每分鐘tick、來源有實質變化才發新包」；`latest`取`published_at`最新的一筆。

## 要做什麼

- [ ] 568.1 資產端Java（只此兩處，其餘程式不動）：
  - `UsReportCalendar.producerWindow`起點由`LocalTime.of(9,40)`改為`LocalTime.of(9,35)`；`UsReportCalendar.reportWindow`起點由`LocalTime.of(9,45)`改為`LocalTime.of(9,35)`。兩者結束`10:45`、`!isBefore`起點與`isBefore`終點的半開語意、與`session(date).contains(at)`取交集全部維持；窗內時間遇非交易日／未知年份仍丟`NON_TRADING_DAY`／`CALENDAR_UNAVAILABLE`，窗外時間依既有短路語意直接回`false`、不呼叫`session()`，這些行為都不變。`displayTime`仍固定以09:45換算（21:45／22:45），**不得改**。
  - `UsReportEnvelopeValidator`中`capturedAt`的NY時間下限由`LocalTime.of(9,40)`改為`LocalTime.of(9,35)`，上限`10:45`、regular session檢核與`displayReportTime`檢核（`date.atTime(9,45)`換算）不變。既有已發布的NY09:40以後的包仍通過新下限（只放寬下限，不收緊）。
  - **不得改**：`UsReportRequest.SLOT="US_OPEN_0945"`、`UsCoreSellCalculator`第150行的`Set.of("21:45","22:45")`、`UsTacticalCalculator`第51行的slot推導、`UsReportProducerScheduler`的cron（維持每分鐘`0 * * * * *`，zone America/New_York）、`UsReportPublisher`的`OUTSIDE_REPORT_WINDOW`判斷（仍用`producerWindow`）、`UsReportReadService`的`reportWindow`判斷與錯誤碼、5分鐘新鮮度與其他任何閘門。BFF `SrppUsReportResponseValidator`（只檢查capture落在session open／close內與21:45／22:45對應，沒有09:40下限）、`SrppUsReportQuery`、`us-report-schema.json`、gateway與Tailscale不動。
  - `latest`在NY09:35起、當分鐘第一包尚未發布前仍回既有`503 CONTEXT_NOT_READY`（不是`409 OUTSIDE_REPORT_WINDOW`），不新增錯誤碼。
- [ ] 568.2 BFF排程清單說明文字：`bff/src/main/java/com/steven/assets/bff/schedulelist/SchedulePublicBffController.java`第78–79行「SRPP 美股不可變報告計算包發布」兩處字串的「09:40–10:45」改為「09:35–10:45」（說明文字與`ScheduledJobDto`的`schedule`頻率欄位各一處），其餘字串、cron表達式`0 * * * * *`與時區不動。`SchedulePublicBffControllerTest`只鎖job數量與其他job文字、不鎖這兩個字串，所以不需改測試，也不新增無意義測試。此項需重建`bff`容器。
- [ ] 568.3 Java測試（作者以外審查可驗，不能只靠訪視；沿用`UsReportCalendarTest`與`UsReportFixtures`，不新增production seam）：
  - `UsReportCalendarTest`補窗口邊界：以夏令（2026-10-26，UTC-4）與冬令（2026-11-03，UTC-5）各驗`producerWindow`與`reportWindow`在NY09:34:59為`false`、09:35:00為`true`、10:44:59為`true`、10:45:00為`false`；既有的第13行「13:40Z時reportWindow為false」斷言必須反轉為true，不得靠刪除斷言通過。非交易日取樣改用週末日期（例如2026-10-31）驗`NON_TRADING_DAY`，取樣時間須落在NY09:35–10:45內（例如2026-10-31T13:40:00Z；窗外時間依既有短路語意回`false`且不丟例外），因為`UsReportFixtures.calendar()`只排除週六日，不能用它的平日假日；若要驗官方假日，沿用`UsReportCalendarTest`既有自建mock寫法。`displayTime`夏令仍為`21:45`、冬令仍為`22:45`。
  - validator下限測試（放在既有`UsReportCalendarTest`，不另開測試類，所以聚焦驗證指令的`-Dtest`清單已涵蓋）：`UsReportEnvelopeValidator.validate`對所有失敗一律丟`UPSTREAM_INVALID`，所以必須用**差分**方式證明拒絕原因是窗口下限而不是其他不一致。做法：把`UsReportFixtures.frozen(...)`與`assets()`中固定使用`AT`（`2026-10-26T13:46:00Z`）的時間參數化（新增接受capture時點的overload，既有呼叫與既有測試的行為位元組不變），用同一個平移量至少連動平移validator實際檢核的欄位：root`capturedAt`、`inputBundle.capturedAt`、每個`sourceVector`的`capturedAt`、assets的`generatedAt`（必須不晚於capture且在capture前300秒內）；`us-tactical.asof`、`liveAssets.stocks[].updatedAt`／`priceUpdatedAt`與quote／radar的+08:00時間不是validator檢核項，calculator對它們只降級（例如`LIVE_SELL_PRICE_UNVERIFIED`／`EXACT_RADAR_UNVERIFIED`）、不丟例外，可不平移；各source的sha256／revision由`UsReportCaptureAdapter.source(...)`重算，`inputSnapshotSha256`與`contentSha256`由publish重算；然後以`UsReportPublisher.publish`（package-private，同package可呼叫；mock `UsReportRepository`，不使用Testcontainers）或`UsReportRepositoryPostgresTest`既有的coherent-rehash手法產出envelope。斷言成對：capturedAt為NY09:35:00的包接受、**同一包平移一秒**到09:34:59拒絕；10:44:59接受、10:45:00拒絕；每個取樣時點的結果與同時點的`producerWindow`一致；`displayReportTime`與capturedAt夏冬令不一致仍拒絕。若無法在不放寬production下構造，改fixture helper，不得放寬production。
  - 新增`UsReportReadService`行為矩陣的純單元測試（同樣放在既有`UsReportCalendarTest`；使用它的package-private `Clock`建構子與mock的`UsReportReadPort`、不用Testcontainers）：NY09:34:59且包不存在回`409 OUTSIDE_REPORT_WINDOW`；NY09:36且`latest`為空回`503 CONTEXT_NOT_READY`；NY10:45:00回`409 OUTSIDE_REPORT_WINDOW`；固定runId不受`reportWindow`限制的既有行為不變。
- [ ] 568.4 SRPP來源與中間版bundle（SRPP feature worktree，主agent在實作期間完成並提交）：`docs/US_REPORT_EVIDENCE.md`第69行與`docs/schedule_entries/README.md`的「銜接邊界」段改述新窗口（背景發布與latest讀取窗同為NY09:35至10:45；slot與display只是標籤；producer每分鐘tick、來源有實質變化才發新包；NY09:35當分鐘包尚未發布回`503 CONTEXT_NOT_READY`與窗前呼叫同為具名缺口，不等待、不重試）；`scripts/verify_srpp_us_report_calculations.py`的NY讀取窗下限`dt.time(9,45)`改為`dt.time(9,35)`，並由`scripts/test_verify_srpp_us_report_calculations.py`新增的邊界測試鎖定（09:34:59拒、09:35:00過、10:44:59過、10:45:00拒）。`core_sell_guard.py`、`model_inputs.json`、formula manifest、formal targets、Java計算器與`D211／policy 2.60.0／model 3.22.0`全部不變，calculator hashes（含`core_sell_guard.py`的`904c13ef…`）不變。以原工具（`daily_runtime_contract.py build`、`policy_bundle.py build`、`policy_bundle.py rules`）重建runtime contract與policy manifest，得**中間版bundle X1**，再`git commit`使SRPP feature worktree乾淨。X1不是最終hash：568.5的sync還會再產生X2，後續一律以X2為準。
- [ ] 568.5 Swagger文字與鏡像（Task 504受控鏈，順序固定，每步核對退出碼）：
  1. 在資產端`docs/openapi/docker-external-api.yaml`的`/api/public/srpp/us-report-calculations`description中，只把「latest只在當前美東交易日09:45至10:45」的`09:45` token改為`09:35`（該句在第4229–4230行折行，只替換第4229行的`09:45`、長度相同、折行不變，**不要**整句搜尋取代；全檔唯一一處）。`reportSlot`的`US_OPEN_0945`、`displayReportTime`列舉21:45／22:45、其他schema與路由位元組不動。
  2. 把SRPP worktree（X1）的`assumptions/policy_manifest.json`原始bytes複製到`backend/src/main/resources/srpp/decision/policy_manifest.json`——這是sync的機械前置條件，否則sync回`PUBLISHED_POLICY_ARTIFACT_DIFFER`。
  3. `export LANG=en_US.UTF-8 LC_ALL=en_US.UTF-8`（否則renderer因worktree清單含非ASCII路徑而以`invalid byte sequence in US-ASCII`中止），在**乾淨、非main、非symlink、Git managed**的SRPP feature worktree依序執行`ruby scripts/render-9090-openapi-docs.rb --srpp-root <SRPP_WORKTREE>`、`python3 scripts/sync-srpp-swagger-identity.py --srpp-root <SRPP_WORKTREE>`（產生新Swagger SHA-256與最終bundle X2，自動寫入SRPP與資產端兩份manifest），再執行兩者的`--check --srpp-root <SRPP_WORKTREE>`。產生的`docs/openapi/9090-api-swagger.md`、`backend/src/main/resources/srpp/9090-api-swagger.md`與SRPP的`docs/9090 Port API Swagger.md`須位元組一致。不得放寬工具的dirty-path檢查。
  4. 在SRPP worktree提交sync產生的四個固定產物（Swagger mirror、`srpp_report_calculations_schema.json`、`daily_runtime_contract.json`、`policy_manifest.json`），再跑`daily_runtime_contract.py check`與`policy_bundle.py check`（Codex、Claude、model三種）。此後所有authority、SQL、golden、Git、容器與驗收一律綁**最後**核對成功的X2。
- [ ] 568.6 資產端authority與registry產物：確認`backend/src/main/resources/srpp/decision/policy_manifest.json`已由568.5的sync寫成X2且與SRPP worktree的`assumptions/policy_manifest.json`逐位元相同（不再手動覆寫；`model_inputs.json`同值不改），**先**用原Ruby腳本`scripts/srpp-report-policy-register.rb`的stdout產受追蹤的`d201-registration.sql`（先寫暫存檔、確認非空再移入，因為golden產生器會重跑ruby比對SQL），**再**用原Python重產`report-same-input-golden.json`與`report-long-short-exit-golden.json`，不手改expected、不更動generator。預期差異：SQL只有bundle字串一處；兩份golden各只有`policyBundleSha256`一行（兩份golden不含Swagger身分欄位）；`us-calculator-parity.json`、state-topup、income-payroll、short-us等其他golden位元組不變，calculator hashes不變；Swagger mirror各一行；YAML一行。若實際差異超出預期，停止並查明原因，不得放寬checker。不新增Java、API、DTO、DB、排程或Liquibase。
- [ ] 568.7 離線驗收、獨立審查與發布：作者以外的spec審查（critical／major修畢）與diff-scoped arch審查通過；全量`mvn test`（backend與bff）、authority gate、golden `--check`、Ruby registry測試、SRPP端`policy_bundle.py check`與`daily_runtime_contract.py check`及verifier測試通過。全量測試允許由Testcontainers啟動的隔離暫時PostgreSQL容器（與t566前例一致；它不是正式stack、不使用正式憑證），但不得碰正式`asset-postgres`。基準（main `052516a3`）全量backend `mvn test`為3658項、3 failures＋2 errors、2 skipped，5個既有失敗與美股窗口無關：`ApiErrorLogServiceTest`兩項、`MarketDataPortDelegationTest.coldDividendSyncOccursBetweenTheTwoProjectionsAndRepositoryReads`、`CanonicalDividendDisplayReadServiceTest.historicalWithdrawalRemainsWithdrawnAndDoesNotBecomeAnUnknownUiOrAnnualRow`（NPE）、`MarketDataPortSpringWiringTest.springAssemblesEveryServiceWithTheProductionAdaptersWithoutNetworkIo`；本任務的驗收標準是失敗集合與這5項完全相同、無新增，且新測試全綠，不得為了變綠修改或刪除這5項。bff全量`mvn test`基準（main `052516a3`）為967項、0 failures、0 errors、1 skipped，驗收標準是仍為0 failures／0 errors。使用者明示順序：離線驗收→雙repo各feature commit／main no-ff merge／push並以`ls-remote`精確讀回→已合併SQL單一交易insert-only登錄（只INSERT，不UPDATE／DELETE任何歷史row；登錄前後各取完整row，舊rows全文不變，新row四欄`policy_bundle_sha256`／`formula_version`／`policy_document`／`formula_manifest`與generator原文一致）→從乾淨正式asset main以原`.env`／mount／network設定只build與force-recreate`business-services`與`bff`（BFF只因568.2的說明文字；先重建`business-services`並等healthy，再重建`bff`），先以唯讀SQL證明`business-services`啟動入口皆為no-op（今日analysis、owner快照roll、各匯出`last_run_date`、備份、`DIVIDEND_BACKFILL_ENABLED=false`）並避開各匯出排程分鐘（當晚至少含21:25至21:43、22:25、22:38，以重建前當日實際排程表為準）→驗運行JAR內`BOOT-INF/classes/srpp/`下五份authority（`decision/model_inputs.json`、`decision/policy_manifest.json`、`9090-api-swagger.md`、`report/d201-registration.sql`、`report/d201-policy-document.json`）與合併來源逐位元相同、舊bundle出現0次、US計算器類別保留→最後才以`git merge --ff-only main`快轉日報實際使用的`/Users/steven/Project/SRPP`（推進的是該checkout目前所在分支，並保留其未追蹤資料）。重建`business-services`之前須先預檢該checkout：目前為非main分支、已追蹤檔乾淨、HEAD是新SRPP main的祖先（可純快轉）——因為重建後JAR只認X2，快轉失敗會讓週一09:05台股日報退回本機計算器。若SRPP main於合併前被推進而動到列管來源，凍結hash失效，須重建contract與bundle並重走本任務。
- [ ] 568.8 部署後驗證（誠實界線）：`verify_9090.py`（`/Users/steven/.claude/skills/srpp-policy-release/scripts/verify_9090.py`）填日曆快取（重建後的503 `CALENDAR_UNAVAILABLE`／`CONTEXT_NOT_READY`屬正常，不手動POST、不觸發producer）；TW `report-calculations`與其他9090路由不受影響（以精確新hash診斷GET與一般公開GET證明路由仍在，週日回`NON_TRADING_DAY`／`400 INVALID_REQUEST`屬診斷）；快轉後在`/Users/steven/Project/SRPP`執行`policy_bundle.py check --purpose daily --consumer Codex`，並用該checkout的manifest bundle與`docs/9090 Port API Swagger.md`的SHA-256對TW `report-calculations`做精確診斷GET，證明週一09:05會用的hash與JAR一致；`us-report-calculations`在週日只能得到`NON_TRADING_DAY`診斷。**窗內（NY09:35至09:45）的真實200讀回只能待週一2026-11-09台北22:35實跑**，本任務以Java邊界測試、SRPP verifier邊界測試與fixture驗證證明窗口語意，並如實記為「正式窗內讀回NOT_YET_EXECUTED」，不冒稱已採用。

## 驗證

```bash
export LANG=en_US.UTF-8 LC_ALL=en_US.UTF-8
SRPP=/Users/steven/Project/SRPP/.claude/worktrees/elated-kare-80e658
TMP=$(mktemp -d)   # 兩個repo之外的暫存目錄
bash scripts/spec-check.sh
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -Dtest=UsReportCalendarTest,UsReportRequestTest,UsCoreSellCalculatorTest,UsTacticalCalculatorTest,UsReportCaptureAdapterTest test
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -f backend/pom.xml test
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -f bff/pom.xml test
ruby scripts/render-9090-openapi-docs.rb --check --srpp-root "$SRPP"
python3 scripts/sync-srpp-swagger-identity.py --check --srpp-root "$SRPP"
python3 -B scripts/check-srpp-report-authority.py --srpp-root "$SRPP" --check
python3 -B scripts/generate-srpp-report-golden.py --srpp-root "$SRPP" --check
python3 -B scripts/generate-srpp-long-short-exit-golden.py --srpp-root "$SRPP" --check
python3 -B scripts/generate-srpp-state-topup-golden.py --srpp-root "$SRPP" --check
python3 -B scripts/generate-us-srpp-calculator-golden.py --srpp-root "$SRPP" --output "$TMP/us-calculator-parity.json" && cmp "$TMP/us-calculator-parity.json" backend/src/test/resources/srpp/us-calculator-parity.json
ruby scripts/tests/srpp-policy-register-test.rb
ruby scripts/tests/srpp-report-policy-register-test.rb
shasum -a 256 backend/src/main/resources/srpp/decision/policy_manifest.json "$SRPP/assumptions/policy_manifest.json"   # 兩者須相同
git diff --check
```

SRPP端另在其feature worktree執行`cd scripts && python3 -B -m unittest test_verify_srpp_us_report_calculations`（需先把正式SRPP的ignored `data/input/交易日曆_2026.json`原檔複製到worktree的同名路徑，否則6項既有D211測試因找不到日曆而錯誤；限離線測試並記錄sha256，不是live採用）、`python3 scripts/daily_runtime_contract.py check`、`python3 scripts/policy_bundle.py check --purpose daily --consumer Codex`／`Claude`與`--purpose simulation --consumer model`。雙repo落地及registry後，僅從正式main使用`docker compose -p asset-management build business-services bff`與`up -d --no-deps --force-recreate business-services`、`bff`（先business-services healthy再bff），真Compose project先由現行container labels確認；無DB schema變動，不重產`schema.sql`。收據放`/Users/steven/Project/SRPP/data/output/diagnostics/2026-11-08-us-window-0935/`，不存秘密原文。

## 完成報告

待發布完成後回填（只回填已完成實證）。
