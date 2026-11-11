# [t570] 六項風險證據採認一致性的SRPP規則包發布

**對應 Requirements:** Requirements 187／193／199（不可變報告計算、正式authority與美股計算）
**前置任務:** t569
**Liquibase changeset:** 無

## 背景

2026-11-11使用者要求接續完成已準備的風險證據採認一致性規則發布。原SRPP工作區10個修改檔已與交接清單及patch逐位元核對，不能再重複套patch；本輪只將原始bytes納入受控feature。先前建立Git ref因權限失敗，當時未改資產端、未登錄、未部署；本輪保留該失敗歷程，接續缺少階段。

凍結SRPP feature為`/private/tmp/srpp-risk-evidence-alignment-20261111`，基準main為`4ebde696b7f29bbbf0eda15e7ea4830b4b6d9028`。舊bundle為`b09f9e760124f2777a30a1fa09d8082f736cfb4b0e80dd4135de61b686883cae`；候選新bundle為`afe2c29ef765ff29733adf35aa134b7a5f8d78cd3b62cdedc8db740fae5d63e9`，manifest檔案SHA-256為`b85fe25ddedcdbec572364bcd8b1683f792a922e99909e59ebc5a5fe281ffd1e`，model為`81435c8cfba426eeda16f7e59155a466231a72324cd746d4882fbb1b26650c8e`，未變Swagger為`34abac7c97ae51d8e930145c0d2453d01506512712f8f152bf11f338745052a1`。重新產生與驗證後若身分改變，撤銷舊收據並重新對齊全部相依產物，不能只替換hash字串。

## 要做什麼

- [ ] 570.1 在資產feature`/private/tmp/asset-risk-evidence-alignment-20261111`先執行spec-check，避開其他worktree的同檔未提交／未合併變更，完成作者以外的唯讀spec審查及通過記錄，才寫backend。新Task570在建立前掃描所有有效worktree未被佔用；不新增Requirement或schema。
- [ ] 570.2 只採上述SRPP受控來源。model僅改`execution_policy.risk_score_evidence_policy`文字：判未評前先檢視同輪窗口內許可具體條目及分類允許輸入，記條目／URL、發布日、實際資料期間、採納或拒絕理由；Fed含最新指引，地緣含域外衝突，半導體不要求全部子指標，但內容仍須支持分級，標題／摘要不補造細節。真正矛盾／不可比／缺值仍未評，CPI可比值要求與所有風險分數門檻不變。009826資金API用途缺口沿既有確定性D-152買後檢查，保存同輪直接資產、原工具輸入輸出、買後定存及台幣存款總額與通膨底線；不以通用PARTIAL代替失敗，不以本機解除有效BLOCKED，不心算或自填PASS。已評數不足4、單項3、總分8、審慎5至7、policy2.60.0／model3.22.0／D211、公式、張數與限價、API／Swagger、排程、時限、交易權限不變，兩消費者獨立作逐筆判斷，不強制同分或買進。
- [ ] 570.3 同步SRPP `assumptions/model_inputs.json`及`assumptions/policy_manifest.json`精確bytes至`backend/src/main/resources/srpp/decision/`。`backend/src/main/resources/srpp/report/d201-policy-document.json`仍由原generator的canonical targets投影產生（預期bytes不變）；`spec/fixtures/srpp_formula_manifest_v2.json`不變。原Ruby完整stdout產`backend/src/main/resources/srpp/report/d201-registration.sql`，stderr獨立保留，僅準備SQL不執行。原Python產`backend/src/test/resources/srpp/report-same-input-golden.json`及`report-long-short-exit-golden.json`，只允許bundle及model source metadata改變；所有case金融輸出、calculator hashes、state-topup及US parity golden保持，不手改expected、不改generator或Java。不涉及Swagger變更，三份mirror只做唯讀parity，不啟動renderer／identity mutation鏈。
- [ ] 570.4 authority gate、golden checks、registry generator回歸、focused Java與backend package通過；保存實際退出碼、來源及產物hash、完整spec與實作diff，進行作者以外diff-scoped architecture查核並記錄。測試只能證明其涵蓋範圍，不宣稱LLM未來一定同分，也不是正式日報採用。
- [ ] 570.5 先雙repo各自feature commit→main `--no-ff` merge→push既有origin並精確`ls-remote`讀回，main必須乾淨；再從資產已合併main受追蹤SQL作正式insert-only登錄。既有相同hash先全文比對，不相同即停止；不UPDATE／DELETE。交易中錯誤即停止，逐欄讀回`policy_bundle_sha256`、`formula_version`、`policy_document`及`formula_manifest`全文，與generator原文一致，舊registry rows全文保持。保留前次失敗與本輪首個實際失敗，不循環重送SQL或推送。
- [ ] 570.6 依run-stack重新查真Compose project與服務，正式main`/Users/steven/Project/asset-management-main`乾淨、原.env／effective env／mounts／network相符後只build及force-recreate `business-services`，保留其他服務與正式設定。先唯讀重新核今日analysis、owner快照rollover、export last_run_date、備份與DIVIDEND_BACKFILL_ENABLED=false，確認本次維護不得額外觸發的寄信、外抓、rollover及備份入口為no-op，並避開活動報告與匯出排程分鐘；不沿用t569的歷史結果。SrppReportProducerScheduler的restartTick自然produce，以及14:00後新bundle的AFTER_CLOSE_INITIALIZATION可能由既有背景流程建立自然診斷包，允許觀察但不手動觸發，亦不視為日報採用。120秒內等待healthy，僅按原run-stack規定驗safe GET恢復，無證據不重啟BFF。核新image／container及JAR hash，運行JAR內`srpp/decision/{model_inputs,policy_manifest}.json`、`srpp/9090-api-swagger.md`、`srpp/report/{d201-registration.sql,d201-policy-document.json}`與合併來源逐位元相同，其他容器id／image／有效設定不變。JAR讀回後緊接在同一維護窗安全同步日報實際SRPP工作區`/Users/steven/Project/SRPP`至合併main；先確認原10檔與合併來源一致並保留未追蹤資料，不強制reset或覆寫使用者其他修改。
- [ ] 570.7 先完整讀當前Swagger分層目錄及`GET /api/public/trading-calendar`、`GET /api/public/market-index`、`GET /api/quotes`路由詳情，再由SRPP實際host／Python環境執行技能的`verify_9090.py`並存收據；三GET必須HTTP200、合法JSON、日曆AVAILABLE、TWSE序列非空對齊及台股報價非空且identity不重複。這是維護連線驗收，不是正式日報；正式採用尚未自然實跑記`NOT_YET_EXECUTED`。report-calculations正式驗收仍須當輪交易日、09:05≤台北時間<13:30、完整required_reads、同owner直接L0及獨立verifier；本維護不補造／重跑報告、POST observer、刷新來源、建立草稿、寄信或交易。

## 驗證

以下在資產feature執行；暫存輸出放兩repo之外，命令逐一核對退出碼。

```bash
bash scripts/spec-check.sh
python3 -B scripts/check-srpp-report-authority.py --srpp-root /private/tmp/srpp-risk-evidence-alignment-20261111 --check
python3 -B scripts/generate-srpp-report-golden.py --srpp-root /private/tmp/srpp-risk-evidence-alignment-20261111 --check
python3 -B scripts/generate-srpp-long-short-exit-golden.py --srpp-root /private/tmp/srpp-risk-evidence-alignment-20261111 --check
python3 -B scripts/generate-srpp-state-topup-golden.py --srpp-root /private/tmp/srpp-risk-evidence-alignment-20261111 --check
ruby scripts/tests/srpp-policy-register-test.rb
ruby scripts/tests/srpp-report-policy-register-test.rb
SRPP_RELEASE_WORKTREE=/private/tmp/srpp-risk-evidence-alignment-20261111 python3 -B scripts/tests/check-srpp-report-authority-test.py
SRPP_RELEASE_WORKTREE=/private/tmp/srpp-risk-evidence-alignment-20261111 python3 -B scripts/tests/generate-srpp-long-short-exit-golden-test.py
SRPP_RELEASE_WORKTREE=/private/tmp/srpp-risk-evidence-alignment-20261111 python3 -B scripts/tests/generate-srpp-state-topup-golden-test.py
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -Dtest=SrppReportModuleCalculatorTest,DecisionPolicyAuthorityTest,DailyDecisionCalculatorTest,SrppReportShortUsCalculatorTest,UsCoreSellCalculatorTest,RiskEvidenceEvaluatorTest test
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -DskipTests package
git diff --check
```

雙repo落地及registry驗證後，在乾淨正式資產main執行`docker compose -p asset-management build business-services`及`docker compose -p asset-management up -d --no-deps --force-recreate business-services`；project須先由運行標籤證實。從SRPP實機執行`python3 /Users/steven/.codex/skills/srpp-policy-release/scripts/verify_9090.py --srpp-root /Users/steven/Project/SRPP --expected-bundle afe2c29ef765ff29733adf35aa134b7a5f8d78cd3b62cdedc8db740fae5d63e9 --output /Users/steven/Project/SRPP/reports/risk-evidence-alignment-2026-11-11/resume/9090-connectivity.json`。本輪不動Liquibase或schema；機械檢查若仍因Docker權限無法核schema，具名保存CHECK並在可用權限後重查，不宣稱DB parity已通過。

## 完成報告

2026-11-11完成本輪來源核對、資產authority準備與離線驗收；**Git落地、正式registry、正式部署與SRPP host部署後9090驗收仍待完成，正式日報採用為NOT_YET_EXECUTED**。以下僅回填實際完成狀態，不修改已審核的需求、約束或任務步驟。

- **來源與審查。** 原SRPP10檔與交接patch完全一致，managed feature重建runtime／bundle後仍為`afe2c29ef765ff29733adf35aa134b7a5f8d78cd3b62cdedc8db740fae5d63e9`，model只改風險證據文字。spec第一輪兩項major（「未評數不足4」錯字、啟動入口全no-op的泛稱）已修正；第二支獨立spec-auditor回報critical0／major0／minor0。實作後獨立diff-scoped arch-auditor同為critical0／major0／minor0。完成報告是本次唯讀審查通過後的狀態回填；spec／arch本機記錄均依hooks保存與核對。
- **資產產物。** 同步model及manifest原始bytes，原canonical targets投影與既有formal document逐位元一致；原Ruby stdout產insert-only SQL（未執行），stderr分存；原Python重產report111案、long-short15案，兩份golden差異恰只有policyBundleSha256。資產端scripts、Java與spec fixtures共792檔對HEAD逐位元不變，calculator hashes、golden金融輸出、state-topup與US parity golden保持。
- **離線驗收。** authority gate、新report／long-short／state-topup golden checks（111／15／22案）、Ruby兩組8＋5項、Python authority／long-short／state-topup四＋五＋五項、focused Java六類87項全部通過，0 failures／errors／skips；backend `mvn -DskipTests package`成功，`git diff --check`通過。六類Java為SrppReportModuleCalculatorTest、DecisionPolicyAuthorityTest、DailyDecisionCalculatorTest、SrppReportShortUsCalculatorTest、UsCoreSellCalculatorTest及RiskEvidenceEvaluatorTest。spec-check為BLOCK0／CHECK1（sandbox下Docker不可用），其後正式唯讀schema核對已在可用權限下PASS：119表，db/schema.sql與當刻pg_dump逐位元一致；不把前者的CHECK抹成已驗證。
- **首個實作失敗與有界修復。** report golden最初因managed feature未帶gitignored `data/input/交易日曆_2026.json`回TRADING_CALENDAR_UNVERIFIED。依發布流程只從primary完整複製原檔供離線回歸，SHA-256 `ce6f23524852d1e089557d8593bd29e6448d7b580cab41fef7f2b0437a7e3f9b`，來源不改、不刷新、不捏造，再跑原generator與檢核成功；首個失敗收據與fixture來源/hash均保留。
- **部署preflight的備份阻擋。** 13:50唯讀預檢見今日美股備份07:12曾回Google Drive上傳或索引結果未定，保留原dump；`backup_enabled=true`、今日持久化US備份row與mutation marker均缺。因此重啟可能再次發起US備份，尚不能證明該啟動入口no-op；正式部署保持pending，先依exact檔名及索引唯讀對帳，不補造成功、不手動重傳、不為發布放寬已審條件。後續本輪若完成有界處理與正式鏈，另以真實證據回填，不沿用本段當部署完成。

完整收據位於`/Users/steven/Project/SRPP/reports/risk-evidence-alignment-2026-11-11/resume/`：`source-validation.json`、`source-freeze-receipt.json`、`spec-review-round1.md`、`spec-review-round2.md`、`asset-implementation.diff`、`asset-artifact-hashes.json`、`asset-golden-calculator-parity.json`、`asset-offline-checks.json`、`asset-focused-java.json`及各log、`asset-package.json`、`asset-first-implementation-failure.json`、`asset-offline-fixtures.json`、`deployment-preflight.json`、`deployment-backup-check.json`與`deployment-schema-drift.log`。沒有秘密原文；一般測試與HTTP連線各只證明其範圍，不是金融採用或交易授權。
