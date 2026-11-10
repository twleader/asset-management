# [t569] Claude比較軌9090入口固定Tailscale的SRPP規則包身分發布

**對應 Requirements:** Requirements 187／193／199（不可變報告計算、正式authority與美股核心計算）
**前置任務:** t568
**Liquibase changeset:** 無

## 背景

使用者指出「Claude call API應該都是走Tailscale」。查證2026-11-10 09:05 Claude早報：9090 gateway當日Claude的全部請求（報價、資產、雷達、指數、匯率、商品、report-calculations等）都回200，沒有4xx／5xx；被看成9090呼叫失敗的，是SRPP共用規格（D-095／D-142）要求每輪先探測loopback `http://127.0.0.1:9090`，而Claude遠端routine的執行環境對它必回connection refused（exit 7，0.02秒），再退到Tailscale（成功，0.2秒），失敗字樣因此寫進`result.json.entry`、`report_facts.json`的核心摘要（核心郵件出現「loopback失敗→Tailscale HTTPS成功」）、`data_quality.entry`與附錄表（不在`api_calculations`）。2026-11-04至11-10有入口紀錄的七輪皆同，Mac端瀏覽器入口未曾使用。同日`api_calculations`的`PASS_PARTIAL`與三項`API_SCOPE_NOT_SUPPORTED`回退是report-calculations範圍缺口，不是呼叫失敗。SRPP規則因此改為Claude軌（`srpp-0905-claude`、`srpp-1150-claude`）固定使用`DATA_FETCH_RULES`規則一之二的Tailscale入口、不探loopback、不開Mac端瀏覽器（curl沿用`--max-time 10`，同輪不更換入口）；Mac端瀏覽器入口（規則一之三）整體退役。取捨：tailnet不可達時沒有瀏覽器後備，依規則一之四第6點同入口最多重試1次、API整體不可用才進規則二與JSON後備。Codex軌行程在Mac本機，維持loopback→Tailscale兩段。

這只是SRPP政策文字（入口transport）的調整，資產端只換規則包身分：9090任何API、Java計算器、DTO、DB、排程、model、formula、formal targets、Swagger與schema鏡像全部不變。已遷移的Claude遠端routine所存的舊指令需裝置綁定proof才能更新，本任務不處理也不宣稱已更新（維持`REMOTE_SCHEDULE_UNVERIFIED`），改以SRPP `DAILY_TRADING_AUTOMATION.md` §8.2與`DATA_FETCH_RULES`新文字明示「舊三段順序以新文字為準」。

## SRPP凍結來源

root `/Users/steven/Project/SRPP/.claude/worktrees/morning-report-9090-api-5083eb`，基準為SRPP main／origin/main `da6db85`。未提交變更共9個檔案：七份文字檔（`CHANGELOG.md`、`docs/DAILY_TRADING_AUTOMATION.md` §8.2、`docs/DATA_FETCH_RULES.md`、`docs/DECISION_LOG.md`、`docs/schedule_entries/README.md`，及兩個Claude入口`docs/schedule_entries/srpp-0905-claude.SKILL.md`、`srpp-1150-claude.SKILL.md`）與原工具重產的`assumptions/daily_runtime_contract.json`（SHA `cb9e75d175e92053c90fadd64cfca87659bf972229a8b0700b6d58d860a61f08`）及`assumptions/policy_manifest.json`（檔案SHA `04f9d2674f96298de0e00a03c611309953f412c1c94161bbd703ba0d85c20375`）。新bundle `b09f9e760124f2777a30a1fa09d8082f736cfb4b0e80dd4135de61b686883cae`，舊bundle `c00d5fd9624a7e5f6e0568fef05bad130d66c7b3441157bcc492c4e655ebc525`。`model_inputs.json`（`64e3f9fc…`）、Swagger（`34abac7c…`）與全部`scripts/*.py`（含各calculator hash）位元組不變；Task 504的Swagger renderer／identity sync鏈因Swagger未變不適用。

## 要做什麼

- [x] 569.1 先spec-check及作者以外的唯讀spec審查，記錄通過後才同步backend。來源只用上述root凍結；D211／policy2.60.0／model3.22.0維持，model_inputs、formula manifest、formal targets、Swagger、Java計算器與所有SRPP腳本bytes必須不變，不新增分數常數、交易權限或硬閘門例外，不改任何API、欄位門檻與35／40／60分鐘時限。
- [x] 569.2 同步凍結`assumptions/policy_manifest.json`原始bytes至backend決策resource（model同值不改）。原Ruby stdout產受追蹤d201-registration.sql，原Python重產report-same-input-golden與report-long-short-exit-golden，不手改expected、不更動generator。預期差異：SQL只有bundle字串一處；兩份golden各只有`policyBundleSha256`一行；state-topup與US parity golden位元組不變；manifest只有各文件雜湊、runtime contract雜湊與bundle欄位。
- [x] 569.3 authority gate、golden checks、Ruby registry、authority／long-short／state-topup／core-scope-consumer負向回歸、focused Java與backend package通過；保存本輪新的source／artifact hashes及實際輸出，不能沿用上輪容器或收據作新proof。
- [x] 569.4 順序為離線驗收→雙repo各feature commit／main no-ff merge／push及精確remote讀回→已合併SQL insert-only registry。精確核新hash的policy_bundle_sha256／formula_version／policy_document／formula_manifest四欄全文，所有舊rows保持；不UPDATE／DELETE歷史資料。新JAR重建並讀回前不得快轉日報實際使用的SRPP工作區：伺服器只認目前JAR內的bundle（登錄新row之後、重建之前仍只認舊hash），新JAR上線後舊hash回409，所以快轉須緊接在JAR讀回之後，兩者之間不得落在任何報告時窗。
- [x] 569.5 由run-stack從乾淨正式asset main與原.env／mount／network設定，只build／force-recreate business-services；先核實真Compose project與活動報告／啟動工作，並以唯讀SQL證明啟動入口皆為no-op（今日analysis OK、owner快照當日已roll、各匯出`last_run_date`為今日、備份、`DIVIDEND_BACKFILL_ENABLED=false`），避開各匯出排程分鐘（台北約11:46至11:55與21:25至21:43，重建前須重新唯讀查`*_export_schedule`與`trading_radar_export_time`）、12:00台股報告（含Claude約12:07起跑）至13:10守門員結束，以及今晚美股報告窗（Codex `22-00`自台北22:35起跑、讀取窗至23:45，守門員22:05／23:05；美股報告同樣綁bundle）。因此重建落在13:10守門員之後、21:25之前。確認新container／image、healthy與JAR全文後，才`--ff-only`快轉日報實際使用的`/Users/steven/Project/SRPP`（保留未追蹤資料），再從SRPP實機做`verify_9090.py`連線讀回；依CLAUDE.md Task 504：若讀回時仍在交易日台北09:05至13:30內且registry row與JAR都已讀回，才可用同一組`policyBundleSha256`／`swaggerSha256`做`report-calculations`唯讀GET讀回（重建後該GET先503，約2至3分鐘後才200）；超出時窗記`NOT_YET_EXECUTED`，由2026-11-11 09:05的台股日報與今晚Codex美股報告首次自然採用。不手動POST observer、重跑報告、寄信、刷新或交易。`verify_9090.py`位於`~/.claude/skills/srpp-policy-release/scripts/verify_9090.py`（不在任一repo內）。Claude本機SKILL部署檔（`scripts/deploy_schedule_entries.sh`）不屬本任務，遠端routine不讀它；`verify_runtime_policy.py`在部署前報host skill differs為預期。

## 驗證

```bash
TMP=$(mktemp -d)   # 兩個repo之外的暫存目錄
mkdir -p "$TMP/scope"
bash scripts/spec-check.sh
python3 -B scripts/check-srpp-report-authority.py --srpp-root /Users/steven/Project/SRPP/.claude/worktrees/morning-report-9090-api-5083eb --check
python3 -B scripts/generate-srpp-report-golden.py --srpp-root /Users/steven/Project/SRPP/.claude/worktrees/morning-report-9090-api-5083eb --check
python3 -B scripts/generate-srpp-long-short-exit-golden.py --srpp-root /Users/steven/Project/SRPP/.claude/worktrees/morning-report-9090-api-5083eb --check
python3 -B scripts/generate-srpp-state-topup-golden.py --srpp-root /Users/steven/Project/SRPP/.claude/worktrees/morning-report-9090-api-5083eb --check
python3 -B scripts/generate-us-srpp-calculator-golden.py --srpp-root /Users/steven/Project/SRPP/.claude/worktrees/morning-report-9090-api-5083eb --output "$TMP/us-calculator-parity.json" && cmp "$TMP/us-calculator-parity.json" backend/src/test/resources/srpp/us-calculator-parity.json
ruby scripts/tests/srpp-policy-register-test.rb
ruby scripts/tests/srpp-report-policy-register-test.rb
SRPP_RELEASE_WORKTREE=/Users/steven/Project/SRPP/.claude/worktrees/morning-report-9090-api-5083eb python3 -B scripts/tests/check-srpp-report-authority-test.py
SRPP_RELEASE_WORKTREE=/Users/steven/Project/SRPP/.claude/worktrees/morning-report-9090-api-5083eb python3 -B scripts/tests/generate-srpp-long-short-exit-golden-test.py
SRPP_RELEASE_WORKTREE=/Users/steven/Project/SRPP/.claude/worktrees/morning-report-9090-api-5083eb python3 -B scripts/tests/generate-srpp-state-topup-golden-test.py
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -Dtest=SrppReportModuleCalculatorTest,DecisionPolicyAuthorityTest,DailyDecisionCalculatorTest,SrppReportShortUsCalculatorTest,UsCoreSellCalculatorTest -Dsrpp.core.scope.exportDir="$TMP/scope" test
SRPP_RELEASE_WORKTREE=/Users/steven/Project/SRPP/.claude/worktrees/morning-report-9090-api-5083eb SRPP_CORE_SCOPE_FIXTURE="$TMP/scope/core-scope-consumer.json" python3 -B scripts/tests/srpp-core-scope-consumer-test.py
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -DskipTests package
shasum -a 256 backend/src/main/resources/srpp/decision/policy_manifest.json /Users/steven/Project/SRPP/.claude/worktrees/morning-report-9090-api-5083eb/assumptions/policy_manifest.json   # 兩者須相同
git diff --check
```

本任務無Java變更，上述focused測試外另補跑不涉資料庫的`SrppReportExtraCaptureAdapterTest`、`SrppReportStateTopupCalculatorTest`、`SrppRadarPolicyCompatibilityTest`、`DailyDecisionCaptureServiceTest`、`DecisionInputCaptureAdapterTest`；使用Testcontainers的`SrppDecisionRepositoryPostgresTest`不在本輪執行。SRPP端`verify_policy_consistency.mjs`完整模式因歷史模型輸出（`data/output`仍是舊bundle `109a8512…`）本就失敗（`MODEL_OUTPUT_MISMATCH`，SHARED_RULES明定不阻擋日報），非本次造成，不列入通過項。驗證指令綁定凍結root路徑，該worktree清理後無法逐字重跑，以本檔完成報告記錄的實際輸出為準。

SRPP端另在凍結root執行`python3 scripts/policy_bundle.py check --purpose daily --consumer Codex`（及Claude、simulation／model）與`python3 scripts/daily_runtime_contract.py check`、`python3 scripts/test_daily_runtime.py`。雙repo落地及registry後，僅從正式main使用`docker compose -p asset-management build business-services`與`docker compose -p asset-management up -d --no-deps --force-recreate business-services`；真project先由現行container標籤核對。

## 完成報告

2026-11-10（Asia/Taipei）完成Claude比較軌9090入口固定Tailscale的SRPP規則包身分發布。**新hash的正式日報採用仍為 NOT_YET_EXECUTED；13:22的業務GET是時窗內的診斷讀回，不是日報採用。**

- **範圍與身分。** D211／policy2.60.0／model3.22.0維持，新bundle `b09f9e760124f2777a30a1fa09d8082f736cfb4b0e80dd4135de61b686883cae`（舊 `c00d5fd9624a7e5f6e0568fef05bad130d66c7b3441157bcc492c4e655ebc525`）。SRPP九個檔案：七份文字檔與原工具重產的runtime contract（SHA `cb9e75d175e92053c90fadd64cfca87659bf972229a8b0700b6d58d860a61f08`）及manifest（檔案SHA `04f9d2674f96298de0e00a03c611309953f412c1c94161bbd703ba0d85c20375`）；`model_inputs.json`（`64e3f9fc…`）、Swagger（`34abac7c…`）與全部`scripts/*.py`位元組不變。前兩次凍結的中介hash `39fe2112…`、`3c43adeb…`因審查文字修正而作廢，從未登錄。
- **起因與事實。** 2026-11-10 09:05 Claude早報的9090請求全回200；「失敗」是每輪先探loopback必被拒（0.02秒）再退Tailscale，失敗字樣出現在`result.json.entry`、核心摘要、`data_quality.entry`與附錄表（不在`api_calculations`）。2026-11-04至11-10有入口紀錄的七輪皆如此。Codex 11-10的09:05／10:20／11:57／13:13四次排程都在開頭2秒因用量額度用盡中止（到11-11 10:20才恢復），與9090無關。
- **審查。** 作者以外的子代理兩輪唯讀審查：第一輪PASS_WITH_NOTES（major：失敗歷程位置寫成`api_calculations`、規則一之四／雷達／報價段寫死「三個入口皆失敗」與瀏覽器入口地位矛盾、569.5重建避讓清單漏今晚美股窗；minor：六輪應為七輪、0.5秒短探測、569.4理由），全數修正；第二輪PASS_WITH_NOTES（minor措辭六項，含寫死loopback腳本「離線處理」過寬、DECISION_LOG「Codex入口未改」與瀏覽器退役字面衝突），亦全數修正並重建最終bundle。
- **離線驗收。** spec-check BLOCK0／CHECK1（origin/main時效提醒，已fetch確認t569無撞號）；authority gate PASS；report／long-short／state-topup golden `--check`（111／15／22案）與US parity `cmp`位元組相同；SQL與兩份golden相對主線只差bundle字串；Ruby 8＋5項、Python authority 4＋long-short 5＋state-topup 5＋core-scope consumer 3項；focused Java十個類別97項0 failures／0 errors，`mvn package`成功；SRPP端`policy_bundle.py check`（Codex／Claude／model）、`daily_runtime_contract.py check`、`test_daily_runtime.py` 35項、`test_shared_policy.py` 17項通過。`verify_policy_consistency.mjs`完整模式因`data/output`舊模型輸出（bundle `109a8512…`）本就`MODEL_OUTPUT_MISMATCH`，非本次造成（SHARED_RULES明定不阻擋日報），不列入通過項。本任務無Java變更。
- **Git與registry。** SRPP feature `793c2d2`、main／remote `4ebde69`；資產feature `7f7129a6`、main／remote `6b427c51`；兩repo各`--no-ff` merge、push並以`ls-remote`精確讀回，合併後manifest位元組與SRPP main相同。11:28由已合併SQL單一交易insert-only登錄：registry 39→40列，舊rows全文不變（dict相等），新row四欄（`policy_bundle_sha256`、`formula_version=ASSET_MGMT_SRPP_V2`、511字元`policy_document`、1145字元`formula_manifest`）與SQL原文相同。
- **部署。** 重建避開12:00台股報告（Claude 12:42完成）、13:10守門員與各匯出分鐘；預檢確認各匯出`last_run_date`皆為今日、今日分析OK（08:50）、owner快照3筆為今日、今日美股備份已存在且台股備份15:30未到期、`DIVIDEND_BACKFILL_ENABLED=false`、計算包皆FINAL、閘道3分鐘無報告流量；main有效設定（47個env鍵、3個mount、重啟策略）與運行容器相同。13:18只build與force-recreate `business-services`：container `2ff77406908e`→`ef1ed407b65c`，image `e71bf9cd86dc`→`1ee4c394d434`，JAR SHA-256 `0d1a0f5f5a625fd9b197c344d898a528e369bcc5684f1586dbd1607507dff938`，healthy。運行中JAR的manifest、model_inputs、Swagger、`d201-registration.sql`、`d201-policy-document.json`與合併來源逐位元相同，舊bundle出現0次；其餘8個容器id、image、啟動時間與設定不變。啟動日誌無匯出補跑、分析、寄信或備份，僅例行api_error_log保留期刪除（0筆）與「今日美股備份已存在」略過，30秒後的指數／分析／快照self-heal皆為no-op（`rolled=0 skipped=3`）。日報實際使用的`/Users/steven/Project/SRPP`原在`codex/us-radar-universe-20261102`（等於舊main `da6db85`），在JAR讀回後立即`--ff-only`快轉到`4ebde69`並保留未追蹤資料，之後`policy_bundle.py check`（Codex／Claude）與`daily_runtime_contract.py check`通過。
- **9090實測。** 先讀完Swagger分層目錄與`trading-calendar`、`market-index`、`quotes`路由詳情，再由SRPP實機執行`verify_9090.py`：日曆、TWSE日線與台股報價（23列）三個GET皆PASS。背景producer於13:21:53發布新hash的12:00計算包（Claude、Codex各一，FINAL）；13:22在交易日09:05至13:30時窗內、registry row與JAR皆已讀回後，對Claude與Codex各做一次精確指紋GET（不帶email、不重試）皆200，identity為新bundle與Swagger `34abac7c…`，`placesOrders=false`、`tradeAuthorization=false`，12模組狀態（PARTIAL 7／UNAVAILABLE 5）與同日舊hash的12:00包逐模組一致。這只證明連線與身分，**不是日報採用**：正式採用待2026-11-11 09:05起的台股日報（Claude 09:05遠端routine與Codex 09:05，其中Codex到10:20前仍受用量額度限制）與今晚Codex美股報告（同樣受該額度限制）。
- **Claude本機入口與遠端排程。** 應使用者要求，兩個Claude本機SKILL部署檔已備份（`SKILL.md.bak-20261110-121728`）並覆蓋為main入口正本、`cmp`逐位元相同；`verify_runtime_policy.py`為`host_policy_status: PASS`、整體`REMOTE_SCHEDULE_UNVERIFIED`（exit 2，預期）。Claude遠端routine（09:05 `trig_0129LNUaUKKbDkGBrKP6ZeiJ`、12:00 `trig_01XF4hPo1DCB3oRnMyxh5tj3`）所存的舊指令仍寫「規則一之一→一之二→一之三」，需裝置綁定proof才能更新，本任務未改也不宣稱已更新；該舊指令靠`DAILY_TRADING_AUTOMATION.md` §8.2與`DATA_FETCH_RULES`的「以新文字為準」覆蓋。今日12:00的Claude報告在快轉前已起跑，仍照舊規則；新規則最早自2026-11-11 09:05生效。若之後Claude報告仍出現loopback失敗字樣，代表模型照舊指令探測，需更新遠端routine。

收據在`/Users/steven/Project/SRPP/data/output/diagnostics/2026-11-10-claude-tailscale-entry-release/`（registry前後快照、容器前後摘要、建置與啟動日誌節錄、JAR SHA-256、`verify_9090`的`9090-connectivity.json`、`verify_runtime_policy.json`、兩個consumer的report-calculations請求／標頭／原始回應），未保存秘密原文。
