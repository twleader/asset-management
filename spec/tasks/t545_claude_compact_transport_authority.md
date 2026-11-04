# [t545] 發布 Claude 精簡內嵌傳輸與未完成附錄規則的規則包

**對應 Requirements:** Requirements 187／193（SRPP 唯讀報告計算與精確 authority 身分發布）
**前置任務:** SRPP 精簡傳輸與附錄未完成規則經測試、no-ff 合併並推送（Task 543 已發布前一版規則包）
**Liquibase changeset:** 無

## 背景

2026-11-04 Claude 09:05 與 12:00 兩輪都沒有附錄：Claude 的 Gmail 工具只能把整份 HTML 內嵌在單次 create_draft，09:05 核心 101KB 寫入約6分鐘，12:00 核心 59KB 被模型判為「放不下」只建純文字草稿，兩輪都在第60分鐘上限前（剩33／41分鐘）收工且未建立「未完成」附錄草稿。SRPP 已修：`render_daily_report.py` 新增 `--transport`（預設 auto，Claude 輸出精簡 HTML、Codex 維持完整樣式），共用規格補上傳輸規則、「未完成」附錄為必交付項與 Gmail 轉址說明。精簡 HTML 與完整版的差別只在樣式：全部文字節點、表格／列／格結構與純文字版逐字相同；省略各 td 行內樣式，th 保留檢核要求的淺灰背景與深色字但去掉 border／padding，表格以 border／cellpadding 屬性呈現（不再帶 role="presentation"）；同一個 `verify_email_html.mjs` 逐項檢核。以 2026-11-04 兩輪 `report_facts.json` 在本機渲染實測：09:05 核心 101,465→38,077 位元組、12:00 核心 59,186→29,188（渲染收據保存在發布證據目錄；共用規格只記載 12:00 數字）。規則包因此由 Task 543 的 `396a1335528b53059e603cc7a06623a65b8e7a1b44306351b98016d425e51170` 變為新 bundle，資產端要同步 manifest 與 insert-only 註冊，否則新 hash 對 9090 report-calculations 回 409，兩軌退回本機計算器。

## 要做什麼

- [x] 545.1 唯一 source root 為 `/Users/steven/Project/SRPP/.claude/worktrees/claude-appendix-report-issue-9efbee`（SRPP feature commit `2662c0570d66cc73d61af823268b043b0711e4b3`，其樹與 main／origin `f09a5812ee1431e50744d47a4a1b65adefd0b8e1` 相同；乾淨、非 main）。若該目錄之後被清除，備援為同樹的 SRPP 主機目錄，但 `sync-srpp-swagger-identity.py` 會拒絕 primary checkout，此時只能重建同 commit 的乾淨非 main worktree，不可改用別的內容。新 POLICY_BUNDLE_SHA256 為 `a157b7676e8efdd4f42c65aec4e8cfe03470f9942c4de85404d6c094d3eaa6c7`，manifest bytes SHA256 為 `41a724cf88184a7d2fe6f8d286b509f4021c4ed95de0abb77b84256ccbb36ea5`；model_inputs.json（SHA256 `b414138ce25e9d9824964aaad4dbeb7bd3a70c4be8ced728c89f5b47034d69db`）與 Swagger（SHA256 `a7ce2c890c437199334b98fb914626ac955d98fbe729ec6c9a498564d52550b4`，版本 1.27.0）逐位元不變。SRPP 側變更僅限郵件 renderer 輸出格式、共用規格、兩個 Claude 入口、README、對應測試與由此重算的 runtime contract／manifest，不改金融規則、模型、公式、targets、API、producer、權限。
- [x] 545.2 閘門：本任務的 spec 機械檢查與獨立 subagent 審查必須在寫任何 runtime resources（`backend/**`）之前完成。通過後，完整複製最終 manifest：`cp <source root>/assumptions/policy_manifest.json backend/src/main/resources/srpp/decision/policy_manifest.json`。以原工具重產 registry SQL（順序有硬性依賴：報告 golden 產生器會重跑 ruby 並比對 SQL，所以 SQL 必須先重產；先寫暫存檔，退出碼 0 且非空才移入 `backend/src/main/resources/srpp/report/d201-registration.sql`，避免 ruby 失敗留下空檔）：`ruby scripts/srpp-report-policy-register.rb > <暫存檔>`；再重產兩份 golden：`python3 -B scripts/generate-srpp-report-golden.py --srpp-root <source root>` 與 `python3 -B scripts/generate-srpp-long-short-exit-golden.py --srpp-root <source root>`。產生 golden 所需的 gitignored 日曆輸入（`交易日曆_2026.json`、`交易日曆_2027.json`）只可從 SRPP 實際主機目錄 `/Users/steven/Project/SRPP/data/input` 複製到 source root 的 `data/input`（不進 Git）；已存在且 SHA256 相同則沿用。model、formal policy document、formula manifest 及所有 golden cases／calculator hashes 不變，golden 只允許 bundle metadata 差異。Python 使用 -B 防止 SRPP bytecode 寫入。舊 hash `396a1335…` 僅允許留在 t543 任務檔與其 RELEASE.md 等歷史文件，不可改寫。
- [x] 545.3 原 authority checker、兩份 golden `--check`、Swagger renderer／identity `--check`、Ruby registry 測試、release gate regression 及聚焦 Java authority／calculator 測試均通過；複製新 manifest 之前 `sync-srpp-swagger-identity.py --check` 預期回 `PUBLISHED_POLICY_ARTIFACT_DIFFER`，複製後必須通過。來源乾淨提交後只做 Swagger check，不執行無關 mutation。
- [x] 545.4 兩 repo 先 feature commit、main no-ff merge、push 與 remote refs 精確讀回，才單次執行已合併受追蹤 insert-only SQL（單一交易：以 compose 的 postgres 容器 `asset-postgres` 的 `POSTGRES_USER`／`POSTGRES_DB` 環境值，執行 `docker exec -i asset-postgres psql -X -q -v ON_ERROR_STOP=1 -U <POSTGRES_USER> -d <POSTGRES_DB>`，stdin 依序餵 `BEGIN;`、已合併的 SQL 原 bytes、`COMMIT;`）。登錄前後各以 `SELECT policy_bundle_sha256, formula_version, policy_document, formula_manifest, registered_at FROM public.srpp_policy_registry ORDER BY policy_bundle_sha256` 的完整 row JSON 比對，新 row 的 bundle、formula version、policy document 及 formula manifest 四欄精確相同；相同 hash 已存在且相同可沿用，矛盾即停止，不更新、不刪除歷史。SRPP 側 main 已於 2026-11-04 推送；asset 側 push 需使用者明確授權後才執行。
- [x] 545.5 只由乾淨 asset main（`/Users/steven/Project/asset-management-main`，使用該目錄正式 `.env`）執行 `docker compose -p asset-management build business-services` 與 `docker compose -p asset-management up -d --no-deps --force-recreate business-services`，不重啟 BFF 或其他容器。每次 recreate 前，依當下台北日期與實際 scheduler 條件，唯讀逐項證明所有啟動及有界觀察期間的外抓／寄信／資產修改入口均 no-op；任一項無法證明就停止部署並具名回報，不修改資料或旗標繞過，也不沿用舊收據（跨日重新查證）：
  1. analysis：`MarketAnalysisScheduler` 啟動自癒不觸發——下列任一成立即可：今日 `daily_market_analysis.status=OK` 且 `email_sent_at` 存在、非交易日、尚未到最早啟用寄送時間、無啟用寄送時間、功能停用。
  2. index：十個 index 皆最新（`IndexDailyRefreshScheduler` 啟動後30秒自癒不補抓）。美股五檔（DJI、SPX、IXIC、SOX、SP500TR）最新日線不得早於最近一個已完成美股交易日；非美股五檔（TPEX、FTSE、DAX、KOSPI、N225）容忍4個日曆天。
  3. 匯出：所有 enabled 的各類匯出排程（資產、交易日曆、匯率、指數、已實現損益、商品、資產交易、交易雷達）啟動時都會補跑「已啟用、排程時間已過、`lastRunDate` 不等於今天」者（交易雷達補跑還會外抓行情並可能上傳 Drive）；必須證明每一筆今天都已 `lastRunDate=今日`，或排程時間尚未到；另避開正在執行的時窗（2026-11-04 觀察約 11:46–11:55、21:25–21:43，僅供參考，以重啟當下唯讀查到的 enabled schedules 為準）。避開時窗只防中斷，不能代替此證明。
  4. 配息回補：`app.dividend-backfill.enabled`（`DIVIDEND_BACKFILL_ENABLED`）必須為 false，否則 `DividendBackfillStarter` 會逐檔外抓 FinMind。
  5. 備份：台股補備份（晚於 15:30 才觸發）與美股補備份（美股運行日且晚於 07:00、前一日為美股交易日時觸發）兩條各自證明當日備份已存在或不適用。
  6. 快照與資金：所有日期 PROCESSING=0；Fubon radar ETF 皆有成功 snapshot（或原設定停用／未 READY）；全 owner 最新快照與到期在途款符合原 roll no-op（`SnapshotDateRollScheduler`）；期間不跨任何啟用的寄送分鐘。
  7. 其餘啟動入口已查：`GdriveSelfCheckStarter` 只做唯讀 rclone 自檢；`DataInitializer` 為冪等 seed；`AiFlowRetentionService` 與 `ApiErrorLogRetentionStartupRunner` 只清過期資料；`SrppReportProducerScheduler.restartTick()`／`UsReportProducerScheduler.ready()` 於啟動時立即 `produce()`，只在內部由已登錄 policy 產生不可變衍生封包，無外抓、寄信或交易，結果須原樣記錄。`TradingRadarNotificationService` 屬執行期輪詢而非啟動入口，其記憶體佇列重啟後清空。
  部署前後各以 `docker inspect` 釘住 business-services 的 container／image id（防止被其他 session 覆蓋）。部署後觀察有界：business-services 健康最多等120秒，經 BFF 的安全 GET（`/api/public/market-index?market=TWSE&range=1m`）最多等30秒，並親核 analysis 與 index 的 startup skip logs 及無新增相關寄送／補抓；BFF 若逾時未恢復，記錄原始錯誤並回報，不自動重啟 BFF。不得宣稱歷史零副作用（先前 feature 重啟曾自動寄兩封非 SRPP 分析通知並補抓 TPEX 2453 筆）。確認新 image／container healthy、其餘容器 ID／image 不變、container `Config.Env` 與 mounts 與重啟前逐項相同（不硬編環境變數數量）、`.env` SHA256 不變；執行 JAR 的 manifest／model／Swagger／registration SQL／formal document 逐位元等於已合併來源。
- [x] 545.6 從 SRPP 實際主機 Python 執行唯讀 9090 連線檢查，並保存精確新 bundle 的 report-calculations HTTP 原文與獨立契約／身分驗證。先 `GET /api/public/trading-calendar?year=<YYYY>` 要求 200 與 `availability.tw.status="AVAILABLE"`，只以 `days[].twTrading==true` 選出目標交易日；僅在該合法交易日台北 `09:05 <= now < 13:30` 且新 hash 的 registry row 與 jar authority 都已唯讀回讀時，才可用同一組 `consumer`、`tradingDate`、`reportSlot`、`policyBundleSha256`、`swaggerSha256` 對 `GET /api/public/srpp/report-calculations` 做唯讀回讀並視為正式採用的前置，其餘一律只算診斷並記 `NOT_YET_EXECUTED`；回應以 SRPP 的 `scripts/verify_srpp_report_calculations.py` 做獨立契約／身分驗證。403／409／503 或缺包不能稱正式採用；新容器日曆 cache 為空時先回 503 `CALENDAR_UNAVAILABLE` 屬預期，不算採用。重啟後由原每分鐘排程或啟動時的 `restartTick()`／`ready()` 自然發布的 package 不算觸發 producer，須原樣記錄；不得手動 POST observer。不觸發 producer、補做或重跑報告、建立或更改草稿、寄信或交易。私人 HTTP／資產原文不提交公開 repo，只提交去識別發布摘要。不宣稱 Claude 遠端 routine 的保存指令已更新，也不宣稱下一輪已實跑（維持 REMOTE_SCHEDULE_UNVERIFIED）。

## 驗證

```bash
bash scripts/spec-check.sh
python3 -B scripts/check-srpp-report-authority.py --srpp-root /Users/steven/Project/SRPP/.claude/worktrees/claude-appendix-report-issue-9efbee --check
python3 -B scripts/generate-srpp-report-golden.py --srpp-root /Users/steven/Project/SRPP/.claude/worktrees/claude-appendix-report-issue-9efbee --check
python3 -B scripts/generate-srpp-long-short-exit-golden.py --srpp-root /Users/steven/Project/SRPP/.claude/worktrees/claude-appendix-report-issue-9efbee --check
ruby scripts/render-9090-openapi-docs.rb --check --srpp-root /Users/steven/Project/SRPP/.claude/worktrees/claude-appendix-report-issue-9efbee
python3 -B scripts/sync-srpp-swagger-identity.py --check --srpp-root /Users/steven/Project/SRPP/.claude/worktrees/claude-appendix-report-issue-9efbee
ruby scripts/tests/srpp-policy-register-test.rb
ruby scripts/tests/srpp-report-policy-register-test.rb
SRPP_RELEASE_WORKTREE=/Users/steven/Project/SRPP/.claude/worktrees/claude-appendix-report-issue-9efbee python3 -B scripts/tests/check-srpp-report-authority-test.py
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -Dtest=SrppReportModuleCalculatorTest,DecisionPolicyAuthorityTest,DailyDecisionCalculatorTest,SrppReportShortUsCalculatorTest test
git diff --check
```

來源凍結後另驗兩份 golden 除 bundle metadata 外與基線完全相同。正式 serve 驗收包含僅 business-services build／recreate、registry 四欄及 JAR 全文比對、其他容器與設定保持、SRPP 主機真 HTTP；不以 healthy 或日曆代替計算包採用。既有測試名稱如與當前版本不符，先核對實際類名並修正，不略過。

## 完成報告

2026-11-04（Asia/Taipei）規則包發布完成；正式當輪報告採用仍為 **NOT_YET_EXECUTED**，Claude 遠端保存指令仍為 **REMOTE_SCHEDULE_UNVERIFIED**。任務勾選代表本輪明定的維護交付，不代表這兩項未驗證範圍。

- SRPP feature `2662c0570d66cc73d61af823268b043b0711e4b3`，main／remote `f09a5812ee1431e50744d47a4a1b65adefd0b8e1`；asset feature `3b947c3da10f3bf5173fdb3c67b3b2e8051d4c51`，main／remote `e2862fb68ba82a29f8976a78b46b3cfa57f1a6a3`。兩 repo 皆 no-ff 合併並以 `git ls-remote` 精確讀回。
- 最終 bundle `a157b7676e8efdd4f42c65aec4e8cfe03470f9942c4de85404d6c094d3eaa6c7`；manifest bytes `41a724cf88184a7d2fe6f8d286b509f4021c4ed95de0abb77b84256ccbb36ea5`。Model 與 Swagger（1.27.0）身分、正式 targets、formula、golden 金融輸出與 calculator hashes 不變。中途因共用規格內「精簡 HTML」描述不精確而在登錄前重建一次 bundle（`cd0ce66e…` 作廢，從未登錄）。
- 前置驗證：spec-check BLOCK0／CHECK0；兩輪獨立 subagent 審查，第一輪1 major＋5 minor、第二輪1 major＋多項 minor 均已併入任務檔（第二輪修正後未再跑第三輪審查）；authority checker PASS；兩份 golden 22＋15 案例僅 bundle metadata 差異；Swagger renderer／identity check 通過；Ruby 8／46 與 5／261 assertions；release regression 4 項；聚焦 Java 測試 exit 0。
- Registry 單次 insert-only transaction 20:59:08，29→30 列，舊29列含時間戳全文不變，新 row 四欄精確相同。登錄後、部署前立即從 SRPP 主機以新 hash 讀 report-calculations 回 409（未採用，原文保留）。
- 重啟前以唯讀 SQL 證明啟動入口 no-op：各 enabled 匯出排程今日已完成或尚未到時（下一個排程 21:25）、今日 analysis OK 且已寄出、PROCESSING=0、台美當日備份存在、三個 owner 最新快照為今日、`DIVIDEND_BACKFILL_ENABLED=false`；並以前次（20:25）重啟的啟動日誌作對照。
- 21:04 從乾淨 asset main 僅 build／force-recreate business-services 一次，container `b434cedd549007c5b0455302c1d8c43968707f1544966e2b3b27be85cde251b0`、image `sha256:da8e23d8a57f8a16da6d9654a08ac83823f4cdbc9cfd5d9628c9672dd7e29136`，healthy；BFF 正常；其餘8個容器 ID／image、52項 effective env、`.env` 與 mounts 保持。執行 JAR SHA256 `5ab6ffe758631ea2240a10f2402e9dc1fd6914b295f4e92052754683a63b6197`，五份 authority 檔全文與合併來源逐位元相同。啟動日誌：台美補備份 Skip、指數日線「皆為最新，略過」、分析「無需補跑」、快照 `rolled=0 skipped=3`、無寄信或補抓。
- 9090：SRPP 主機連線檢查（日曆、TWSE 日線、台股報價）全 200。新 hash 計算包於 21:07:33 由原排程自然發布（Claude／Codex 各一份 FINAL，沒有呼叫 producer）。21:08 兩 consumer 各一次精確 hash GET 均 HTTP 200，完整 schema／hash 鏈與 owner／日期／12:00／policy／Swagger 身分通過；coverage 7 PARTIAL＋5 UNAVAILABLE、0 COMPLETE，adopted_modules={}、trade_authorization=false，僅屬 INTEGRITY_ACCEPTED_DIAGNOSTIC_ONLY。時窗外且無本輪直接 L0／完整 required_reads，不作正式日報採用。
- 不補做報告、不建立或修改草稿、不寄信、不下單、不手動觸發 producer 或刷新來源；不宣稱 Claude 遠端 routine 保存指令已更新或下一輪已實跑。私人完整證據 `/private/tmp/claude-compact-release-20261104/`；公開摘要 `reports/srpp-claude-compact-transport-20261104/RELEASE.md`。
