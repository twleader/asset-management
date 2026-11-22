# [t627] D-234：SRPP 衛星建倉、合併資金、D-215 債券匯率與 order-plan v2 的規則包身分發布

**對應 Requirements:** Requirements 220／221／222／223（t623–t626），以及 187／193／199（不可變報告計算、正式 authority 與美股核心計算）
**前置任務:** t623、t624、t625、t626
**Liquibase changeset:** 無

## 背景

Steven 2026-11-22 核准 D-234：VT 比照 009826 衛星建倉到 2.5%（2026-11-23～2027-06-30、每日最多 5 股、每週最多 15 股、每筆至少 5 股、月基準 NT$53,333／上限 NT$80,000、國泰台幣複委託），三筆美元定期定額在美元存款用完後改扣台幣並計入 D-152 台幣底線；並要求把 009826／VT 建倉張數、D-152 全行合併買後額度、D-215 匯率偏好（債券／VT 腿）與 order-plan v2 的固定公式由 9090 計算。t623–t626 已在同一分支 `claude/srpp-d234-calc-migration` 實作並通過 spec 審查（Codex 額度用完，依 Steven 指示以 Claude Sonnet 5.5 xhigh 代審三輪）與實作後架構審查（同備援，PASS）。本任務只做規則包身分同步與發布。

## SRPP 凍結來源

SRPP worktree `/Users/steven/Project/SRPP/.claude/worktrees/asset-management-logic-review-d1cbbc`（分支 `claude/asset-management-logic-review-d1cbbc`）。第一個凍結 commit `204d4ae` 的 bundle `86a0df0e04827a646b8557d349a931659c878e8aacb6a3ad391f5bc643a02c6a`；因 9090 OpenAPI 1.28.4→1.29.0（t626），經 renderer 與 identity sync 後最終 bundle `a64154457d4f325b193b25aa429bcad672126aa3345fb5d26a766af928c3d2a9`（前一版正式 bundle `bef329db726ee948c0608ef5f2674f9f9471b6c94ae5b175f7a81a74dca29049`）。Swagger `8350049985468307db0a52f57c687c0aa762c643ce6d72b2e65d357c2b602ea5`；`model_inputs.json` `e9e561a9fe5d8febffc480a20b2b2c013bf9c0d1469e9f37a000978e60c7f46d`；policy document 雜湊 `c6d1d10aa5c6a7f2ec0b4c2ec6d751cf81ba01c0ed70377aa0d303140fa5488a`；formula V3 `9962ff17192a4b4f51525f1520a29b46e2a66c393236a5054ea3f078ee1e8a67`。`policy_manifest.json` 檔案 sha256 `c1521fdf0cec96736d61ec584d54365236e234ec80639309f5802043cdc000fe`。最終 bundle 對應的 SRPP commit 於落地（627.3）時回填。

## 要做什麼

- [x] 627.1 同步凍結 `policy_manifest.json` 與 `model_inputs.json` 原始 bytes 至 `backend/src/main/resources/srpp/decision/`；`ruby scripts/render-9090-openapi-docs.rb --srpp-root <SRPP>` 與 `python3 scripts/sync-srpp-swagger-identity.py --srpp-root <SRPP>`，兩者 `--check` 通過；原 Ruby 產 `d201-registration.sql`（先寫暫存檔確認非空）；原 Python 重產 report-same-input、long-short-exit、state-topup golden（金融輸出不變，只變 bundle 與 calculator hash 行）；formula V3 `--check` 與 `check-srpp-report-authority.py --check` 通過。
- [x] 627.2 全套 backend／BFF 測試與乾淨 main 基準比對無新增失敗。基準（`/private/tmp/asset-baseline`，main `dc7720a6`）backend 既有 28 個失敗（9 failures＋19 errors，12 個類別：ApiErrorLogServiceTest、HistoryDtoJsonCompatibilityTest、MarketDataPortDelegationTest、MarketDataPortSpringWiringTest、CanonicalDividendDisplayReadServiceTest、SrppRadarPolicyCompatibilityTest、UsReportCalendarTest、UsReportRepositoryPostgresTest、TransactionAssetClassPostgresTest、TradingRadarServiceOwnerScopeTest、BacktestWeeklyWiringTest、DualFormatSingleTableExportTest）；本分支 4135 個測試失敗集合與基準逐筆相同。BFF 1039 個 0 失敗。`ruby scripts/tests/unified-9090-openapi-renderer-test.rb` 在 main 基準即 1 failure（寫死契約版本 1.28.3），為既有失敗，本任務不修。故 `mvn test` 與該 ruby 測試在基準下即非 0，驗收以失敗集合比對為準。
- [ ] 627.3 雙 repo feature commit／main `--no-ff` merge／push 與 `git ls-remote` 讀回 → 已合併 SQL insert-only registry（單一 transaction，前後 row JSON 比對）→ run-stack 從乾淨 main build／force-recreate business-services 與 bff（t623–t626 改了 BFF validator 與 projection service）→ JAR bytes 讀回並立即 `--ff-only` 快轉日報工作區 → 從 SRPP 實測 9090。重啟前以唯讀 SQL 證明啟動入口皆為 no-op，避開正式匯出排程分鐘：重啟前以唯讀 SQL 讀出各 `*_export_schedule`／`export_schedule_setting` 的實際 run_hour／run_minute 與 last_run_date（2026-11-22 讀回：晚間匯出為 21:35 指數、22:25–22:32 交易紀錄／已實現損益／資產總覽／匯率／油價金價，其餘當日已跑），只在 enabled=true 且當日時點已過者皆 last_run_date=今日、且距下一個匯出至少 15 分鐘時重啟；不寫死時窗。其他啟動入口（台美補備份、分析與指數 self-heal、快照 roll、富邦 ETF 持股 STARTUP 同步、配息回補須 `DIVIDEND_BACKFILL_ENABLED=false`、SRPP／美股 producer `restartTick()`）以唯讀 SQL 與上次重啟啟動日誌對照證明為 no-op。整段重建、JAR 讀回與日報工作區 `--ff-only` 快轉不得跨入台股報告與守門員窗 09:05–13:10 或美股報告窗；證據不足或時間不夠安全時停止，保留已落地的 main 與 registry row，不強行切換。

## 驗證

```sh
bash scripts/spec-check.sh
export LANG=en_US.UTF-8 LC_ALL=en_US.UTF-8
ruby scripts/render-9090-openapi-docs.rb --check --srpp-root <SRPP root>
python3 -B scripts/sync-srpp-swagger-identity.py --check --srpp-root <SRPP root>
ruby scripts/tests/docker-external-api-openapi-test.rb
ruby scripts/tests/unified-9090-openapi-renderer-test.rb   # 基準即 1 failure（寫死 1.28.3），比對失敗集合
python3 -B scripts/check-srpp-report-authority.py --srpp-root <SRPP root> --check
python3 -B scripts/generate-srpp-report-golden.py --srpp-root <SRPP root> --check
python3 -B scripts/generate-srpp-long-short-exit-golden.py --srpp-root <SRPP root> --check
SRPP_RELEASE_WORKTREE=<SRPP root> python3 -B scripts/generate-srpp-state-topup-golden.py --srpp-root <SRPP root> --check
python3 -B scripts/generate-srpp-formula-v3.py --srpp-root <SRPP root> --check
python3 -B scripts/generate-srpp-order-plan-v2-golden.py --srpp-root <SRPP root> --check
python3 -B scripts/generate-srpp-satellite-build-golden.py --srpp-root <SRPP root> --check
python3 -B scripts/generate-srpp-pooled-headroom-golden.py --srpp-root <SRPP root> --check
python3 -B scripts/generate-srpp-d215-fx-golden.py --srpp-root <SRPP root> --check
ruby scripts/tests/srpp-report-policy-register-test.rb
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f bff/pom.xml test
git diff --check
```

## 完成報告

（發布完成後回填：registry row 讀回、容器／JAR 驗證、9090 實測收據路徑。正式 report-calculations 採用待 2026-11-23 09:05 合法時窗實跑。）
