# [t573] D-212 AI循環牛市修正低接與台積電曝險豁免的SRPP規則包發布

**對應 Requirements:** Requirements 187／193／199（不可變報告計算、正式authority與美股計算）
**前置任務:** t570
**Liquibase changeset:** 無

## 背景

2026-11-12 Steven 核准 SRPP D-212：AI循環未轉折時，市場基準自近24個月高點回撤15%視為牛市修正，以前重後輕五批低接；AI轉折訊號成立或無法驗證時維持原D-202熊市窗口與D-141階梯。同一決策另含台積電曝險豁免（2330暫停清零、0050上限10%、00881上限6%、低接VOO：0050改1：1）。新判定與批次計算全部由SRPP本機確定性計算器`scripts/correction_dip.py`執行；9090既有熊市梯驗證所讀的`bear_ladder_d141`與D-211區塊保持不變，資產端不新增或修改任何計算邏輯。

凍結SRPP feature為`/Users/steven/Project/SRPP/.claude/worktrees/taiwan-stock-dip-buying-5ca513`（分支`claude/taiwan-stock-dip-buying-5ca513`，commit `428d439b0e0f7f0d40ccd60c59736d73fb5f8d1e`），基準main為`98d577a7954d6451ddd3d7d083b29ba0793dd4d0`。舊bundle為`afe2c29ef765ff29733adf35aa134b7a5f8d78cd3b62cdedc8db740fae5d63e9`；新bundle為`326e18dfaa441e9090c065aa971adec087768832aa57d4e27b378efdec1cda7f`，manifest檔案SHA-256為`1671e999586082d1aa9724e381cf5bcd6463c57f871ae31a99564e84ed61278c`，model為`b01c4ec55ddd37f05abd30ea0418af42c716791dc53393ca8631c3131ae86bc9`，未變Swagger為`34abac7c97ae51d8e930145c0d2453d01506512712f8f152bf11f338745052a1`。重新產生與驗證後若身分改變，撤銷舊收據並重新對齊全部相依產物，不能只替換hash字串。

## 要做什麼

- [ ] 573.1 在資產feature`/private/tmp/asset-d212-correction-20261112`先執行spec-check，避開其他worktree的同檔未提交／未合併變更，完成作者以外的唯讀spec審查及通過記錄，才寫backend。Task573建立前已掃描所有有效worktree未被佔用（main已有t572）；不新增Requirement或schema。
- [ ] 573.2 只採上述SRPP受控來源。model新增`execution_policy.bull_correction_dip_d212`（含`tsmc_exposure_waiver`），`policy_version`升2.61.0；頂層`decision`及其餘原為現行D-211的12個巢狀現行決策標記（`simulation_contract`、`execution_policy`、`two_track_consistency_d144`、`market_spend_adjustment_d193`、`stock_purchase_funding_policy_d150`、`high_dividend_execution_policy`、`daily_topup_d170`、`llm_orchestrated_api_d197`、`living_runway_policy_d145`、`emergency_reserve_policy_d193`、`annual_review_reserve_maintenance`的`decision`，及`daily_topup_tier_policy.current_entry_rule_decision`）一併升D-212；巢狀標記Java不比對，頂層`decision`只做model／manifest一致性核對並由`DecisionPolicyAuthorityTest`斷言。`execution_policy.core_sell_guard_d174.ordinary_equity_high_sell_d211`的`decision`仍為D-211（`OrdinaryEquitySellGate`依此嚴格判定）；`execution_policy.cash_first_rotation_d211`的`decision`亦仍為D-211（9090不讀，僅保持）；`dynamic_allocation_policy_d141.bear_ladder_d141`、`bear_stock_purchase_order_d143`、formal targets、model3.22.0、API／Swagger、排程、時限與交易權限不變。9090不判定修正窗口或台積電豁免，不新增Java計算、DTO、DB或公式。
- [ ] 573.3 同步SRPP `assumptions/model_inputs.json`及`assumptions/policy_manifest.json`精確bytes至`backend/src/main/resources/srpp/decision/`。`backend/src/main/resources/srpp/report/d201-policy-document.json`仍由原generator的canonical targets投影產生（預期bytes不變）；`spec/fixtures/srpp_formula_manifest_v2.json`不變。原Ruby完整stdout產`backend/src/main/resources/srpp/report/d201-registration.sql`，stderr獨立保留，僅準備SQL不執行。原Python重產`backend/src/test/resources/srpp/report-same-input-golden.json`及`report-long-short-exit-golden.json`，兩份golden只允許`policyBundleSha256`改變；所有case金融輸出、`originalCalculatorHashes`、state-topup及US parity golden保持（本輪SRPP改動的scripts均不在三支generator的calculator hash清單內），出現其他差異即停止查因，不手改expected、不改generator或Java計算。正式產物一律在本feature重新產生；`/private/tmp/asset-d212-scratch`僅為審查前的身分試算，不提交、不作為來源。`DecisionPolicyAuthorityTest`的現行決策與政策版本斷言改為D-212／2.61.0（比照t566）。不涉及Swagger變更，三份mirror只做唯讀parity。
- [ ] 573.4 authority gate、golden checks、Ruby registry generator回歸、三支Python authority／long-short／state-topup回歸、focused Java與backend package通過；保存實際退出碼、來源及產物hash與完整diff，進行作者以外diff-scoped architecture查核並記錄。測試只證明其涵蓋範圍，不是正式日報採用。
- [ ] 573.5 雙repo各自feature commit→main `--no-ff` merge→push既有origin並精確`ls-remote`讀回，main必須乾淨；為避免影響當日09:05與12:00兩輪日報，雙repo推送、登錄與部署都在台北時間13:30之後進行。從資產已合併main受追蹤SQL作正式insert-only登錄；既有相同hash先全文比對，不相同即停止；不UPDATE／DELETE。逐欄讀回`policy_bundle_sha256`、`formula_version`、`policy_document`及`formula_manifest`全文，與generator原文一致，舊registry rows全文保持。首個實際失敗即保留，不循環重送SQL或推送。
- [ ] 573.6 依run-stack重新查真Compose project與服務，正式main`/Users/steven/Project/asset-management-main`乾淨、原.env／effective env／mounts／network相符後只build及force-recreate `business-services`，保留其他服務與正式設定。重啟前唯讀重核今日analysis、owner快照rollover、export last_run_date、備份與`DIVIDEND_BACKFILL_ENABLED=false`，確認寄信、外抓、rollover及備份等啟動入口為no-op，避開報告與匯出排程分鐘；不沿用t570的歷史結果。`SrppReportProducerScheduler`的restartTick自然produce，以及14:00後新bundle的`AFTER_CLOSE_INITIALIZATION`可能由既有背景流程建立自然診斷包；14:00前每分鐘排程亦可能建立當日12:00時段的新bundle包；以上皆允許觀察但不手動觸發，亦不視為日報採用。120秒內等待healthy；核新image／container及JAR hash，運行JAR內`srpp/decision/{model_inputs,policy_manifest}.json`、`srpp/9090-api-swagger.md`、`srpp/report/{d201-registration.sql,d201-policy-document.json}`與合併來源逐位元相同，其他容器id／image／有效設定不變。JAR讀回後於同一維護窗以`--ff-only`安全同步日報SRPP工作區`/Users/steven/Project/SRPP`至合併main，保留未追蹤資料，不強制reset。
- [ ] 573.7 先讀當前Swagger分層目錄及`GET /api/public/trading-calendar`、`GET /api/public/market-index`、`GET /api/quotes`路由詳情，再由SRPP實際host／Python環境執行發布技能的`verify_9090.py`並存收據；三GET須HTTP200、合法JSON、日曆AVAILABLE、TWSE序列非空對齊及台股報價非空且identity不重複。這是維護連線驗收；正式日報採用未自然實跑前記`NOT_YET_EXECUTED`，不補造／重跑報告、POST observer、刷新來源、建立草稿、寄信或交易。

## 驗證

以下在資產feature執行；暫存輸出放兩repo之外，命令逐一核對退出碼。

```bash
bash scripts/spec-check.sh
python3 -B scripts/check-srpp-report-authority.py --srpp-root /Users/steven/Project/SRPP/.claude/worktrees/taiwan-stock-dip-buying-5ca513 --check
python3 -B scripts/generate-srpp-report-golden.py --srpp-root /Users/steven/Project/SRPP/.claude/worktrees/taiwan-stock-dip-buying-5ca513 --check
python3 -B scripts/generate-srpp-long-short-exit-golden.py --srpp-root /Users/steven/Project/SRPP/.claude/worktrees/taiwan-stock-dip-buying-5ca513 --check
python3 -B scripts/generate-srpp-state-topup-golden.py --srpp-root /Users/steven/Project/SRPP/.claude/worktrees/taiwan-stock-dip-buying-5ca513 --check
ruby scripts/tests/srpp-policy-register-test.rb
ruby scripts/tests/srpp-report-policy-register-test.rb
SRPP_RELEASE_WORKTREE=/Users/steven/Project/SRPP/.claude/worktrees/taiwan-stock-dip-buying-5ca513 python3 -B scripts/tests/check-srpp-report-authority-test.py
SRPP_RELEASE_WORKTREE=/Users/steven/Project/SRPP/.claude/worktrees/taiwan-stock-dip-buying-5ca513 python3 -B scripts/tests/generate-srpp-long-short-exit-golden-test.py
SRPP_RELEASE_WORKTREE=/Users/steven/Project/SRPP/.claude/worktrees/taiwan-stock-dip-buying-5ca513 python3 -B scripts/tests/generate-srpp-state-topup-golden-test.py
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -Dtest=SrppReportModuleCalculatorTest,DecisionPolicyAuthorityTest,DailyDecisionCalculatorTest,SrppReportShortUsCalculatorTest,UsCoreSellCalculatorTest,RiskEvidenceEvaluatorTest,SrppReportStateTopupCalculatorTest test
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -DskipTests package
git diff --check
```

雙repo落地及registry驗證後，在乾淨正式資產main執行`docker compose -p asset-management build business-services`及`docker compose -p asset-management up -d --no-deps --force-recreate business-services`；project須先由運行標籤證實。從SRPP實機執行`python3 /Users/steven/.claude/skills/srpp-policy-release/scripts/verify_9090.py --srpp-root /Users/steven/Project/SRPP --expected-bundle 326e18dfaa441e9090c065aa971adec087768832aa57d4e27b378efdec1cda7f --output /Users/steven/Project/SRPP/reports/d212-correction-release-2026-11-12/9090-connectivity.json`。本輪不動Liquibase或schema。

## 完成報告

2026-11-12完成本輪全部階段；**正式日報採用為NOT_YET_EXECUTED**（待下一個合法交易時窗的實際日報）。以下只回填實際狀態，不修改已審核的需求、約束或步驟。

- **審查。** spec第一輪獨立審查major2／minor4（漏三支Python回歸、golden允許範圍過寬等），全部修正後由第二支新審查者複審critical0／major0，剩2項措辭minor亦已修正並記錄`spec-review-pass`（d419c09b7360）。實作後獨立diff-scoped架構審查critical0／major0／minor0並記錄`arch-review-pass`（6795f32a4d7e）。
- **離線驗收。** authority gate PASS；report／long-short／state-topup golden check 111／15／22案相符；Ruby 8＋5項、Python authority／long-short／state-topup三支皆OK；focused Java七類95項0 failures／errors／skips；backend package成功；`git diff --check`通過。兩份golden只有`policyBundleSha256`一行改變，正式產物與審查前scratch試算逐位元相同（scratch已移除）。
- **落地。** SRPP main `4d4140ae63d6d292a1c4be09bf390726cf394019`（feature `428d439`）、資產main `4858158d0d6ee46d350198e6549a683e5c65c494`（feature `b8c61d9b`），均以`--no-ff`合併並以`ls-remote`精確讀回；推送於台北13:37後，當日09:05／12:00日報均以舊hash完成FINAL。
- **登錄。** 從已合併main受追蹤SQL以單一交易insert-only登錄；登錄前41列全文不變、新增1列，四欄與SQL原文一致。
- **部署。** 預檢有效env47鍵／mounts／network／restart一致、`DIVIDEND_BACKFILL_ENABLED=false`、今日analysis OK、三owner快照為今日、當日已到期匯出皆已執行。今日07:00美股備份上傳結果未定，比照t570既有使用者核准，允許重啟時自然補跑：13:40:33開始、13:41:56上傳並索引完成，其後例行輪替。只build並force-recreate `business-services`（image `sha256:dc81ede2…`，container `ccf33264…`），約20秒healthy，其他容器不變；運行JAR內model／manifest／swagger／registration SQL／policy document與合併來源逐位元相同。index／analysis／snapshot self-heal皆no-op。
- **SRPP與9090。** JAR驗證後立即以`--ff-only`將`/Users/steven/Project/SRPP`同步至`4d4140a`（只有未追蹤資料，保留）；讀完Swagger目錄及三路由詳情後，由SRPP host執行`verify_9090.py`：日曆AVAILABLE（365日）、TWSE日線21筆、台股報價40筆皆PASS。新bundle於14:00前由既有排程自然產生12:00時段診斷包各1份，未手動觸發，不視為日報採用。

收據位於`/Users/steven/Project/SRPP/reports/d212-correction-release-2026-11-12/`（`deployment-preflight.json`、`registry-before.jsonl`、`registry-after.jsonl`、`image-build.log`、`recreate.log`、`containers-before.txt`、`containers-after.txt`、`9090-connectivity.json`）。
