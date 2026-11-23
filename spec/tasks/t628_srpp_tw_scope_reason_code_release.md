# [t628] SRPP 早報預檢 tw-scope-only 放行 9090 原文海外持股理由碼的規則包身分發布

**對應 Requirements:** Requirements 187／193／199（不可變報告計算、正式authority與美股核心計算）
**前置任務:** t627
**Liquibase changeset:** 無

## 背景

2026-11-23 09:05 Claude 早報沒有建立核心與附錄草稿。D-234（t623–t627）上線後第一個交易日，9090 `report-calculations` 的 D-215 腿阻擋理由碼帶出無法估值的海外持股（例如 `D215_SOURCE_UNAVAILABLE:market:美股:VOO:VOO`、`EXACT_HOLDING_TARGET_SESSION_VALUATION_UNAVAILABLE:美股:VOO:45302`），SRPP 渲染器把 API 原文原樣印進「交易候選分析與阻擋分類」表，`verify_email_html.mjs` 的 `tw-scope-only`（D-093／D-096）判成海外標的而 FAIL，整輪不能建草稿（Codex 同日因 usage limit 3 秒中止，與本任務無關）。

Steven 2026-11-23 指示：早報、午報是台股報告，不該檢查美股；晚報是美股報告，不該檢查台股。

SRPP 修正（commits `cd591f8`、`83ce30c`、`69a2bfb`，非投資規則、無新 Decision）：`overseasHitsInTable` 只在該表「硬阻擋／必要缺口」欄剝除完整樣式的 API 原文理由碼（含底線的大寫理由碼、可接小寫段，再接 `:美股|英股|港股:代號`，尾段只可為數字或同一代號，前綴任一段不得等於該代號；前綴其餘內容不檢查，`FOO_BAR:美股:NVDA` 視為 API 原文，為已知殘餘風險）；標的欄、其他欄、其他表或樣式不完整的海外代號與市場字樣仍判違規。改動檔：`scripts/verify_email_html.mjs`（＋測試）、`docs/EMAIL_HTML_STYLE.md`、`docs/DAILY_TRADING_AUTOMATION.md` 各一句、重建 `assumptions/daily_runtime_contract.json` 與 `assumptions/policy_manifest.json`。

9090 不新增或改變任何路由、計算、DTO、DB、排程、formula、`model_inputs.json` 或 Swagger；計算包照原規則產生理由碼。資產端只換規則包身分並重建 business-services。

## SRPP 凍結來源

root `/Users/steven/Project/SRPP/.claude/worktrees/morning-report-missing-a652f1`，凍結 commit `69a2bfb`（首版 `cd591f8`、第2版 `83ce30c` 經獨立審查後收緊理由碼樣式，首版 bundle `e65c5eb1…` 與第2版 `044fe65a…` 作廢、未登錄），bundle `885bfcffbda0c57b63763e093eb854bf6e66c29b4f52743dc85edd5a4c05c956`（前一版 `a64154457d4f325b193b25aa429bcad672126aa3345fb5d26a766af928c3d2a9`）；manifest 檔案 SHA-256 `8b62e26facb01233c40e2e48f29e846ec6f2526bf16469a638b26181de5a12fd`；`model_inputs.json`（`e9e561a9…`）與 Swagger（`8350049985…`）位元組不變，只同步 `policy_manifest.json`。

## 要做什麼

- [x] 628.1 同步凍結 `policy_manifest.json` 原始 bytes 至 backend 決策 resource；原 Ruby 產 `d201-registration.sql`（先寫暫存檔確認非空），原 Python 重產 report-same-input 與 long-short-exit golden（只變 bundle 行）；authority gate、全部 golden `--check`、formula V3 `--check`、Ruby registry 測試通過。不改 Java、DB、formula、Swagger。
- [x] 628.2 雙 repo feature commit／main `--no-ff` merge／push 與 `git ls-remote` 讀回 → 已合併 SQL insert-only registry（單一 transaction，前後 row JSON 比對，舊 rows 全文不變）→ run-stack 只 build／force-recreate business-services（重啟前以唯讀 SQL 證明啟動入口皆 no-op，避開報告窗與匯出排程分鐘）→ JAR bytes 讀回並立即 `--ff-only` 快轉日報工作區 → 從 SRPP 實測 9090。

## 驗證

```sh
bash scripts/spec-check.sh
python3 -B scripts/check-srpp-report-authority.py --srpp-root <SRPP root> --check
python3 -B scripts/generate-srpp-report-golden.py --srpp-root <SRPP root> --check
python3 -B scripts/generate-srpp-long-short-exit-golden.py --srpp-root <SRPP root> --check
SRPP_RELEASE_WORKTREE=<SRPP root> python3 -B scripts/generate-srpp-state-topup-golden.py --srpp-root <SRPP root> --check
python3 -B scripts/generate-srpp-order-plan-v2-golden.py --srpp-root <SRPP root> --check
python3 -B scripts/generate-srpp-satellite-build-golden.py --srpp-root <SRPP root> --check
python3 -B scripts/generate-srpp-pooled-headroom-golden.py --srpp-root <SRPP root> --check
python3 -B scripts/generate-srpp-d215-fx-golden.py --srpp-root <SRPP root> --check
python3 -B scripts/generate-srpp-formula-v3.py --srpp-root <SRPP root> --check
ruby scripts/tests/srpp-report-policy-register-test.rb
mvn -q -f backend/pom.xml -Dtest='SrppReport*Test,DecisionPolicyAuthorityTest,DailyDecisionCalculatorTest' test
git diff --check
```

審查記錄：Codex 用量上限（2026-11-24 00:32 恢復），未執行 Codex spec 審查；由作者以外的 Claude 審查代理依 `spec-auditor` 判準審閱第 1 輪：發現驗證器理由碼前綴過寬（`NVDA:美股:NVDA` 可冒充理由碼）與本檔驗證清單不完整，皆已修（SRPP `83ce30c`、本檔驗證區塊）；第 2 輪複核發現前綴夾帶代號（`SELL_NVDA:美股:NVDA`）仍被剝除，已於 SRPP `69a2bfb` 修正並把 `FOO_BAR:美股:NVDA` 殘餘風險明載；第 3 輪結果見完成報告。SRPP `scripts/verify_email_html.test.mjs` 在無本機資料的 worktree 有 25 個既有失敗（修前修後相同），不宣稱整檔全綠，僅新增的 D-234 回歸測試通過。

## 完成報告

2026-11-23 10:43–10:49 完成（SRPP main `0aa83c8`、asset main `771cdd22`，兩者 `git ls-remote` 讀回一致）：

- registry：單一 transaction insert bundle `885bfcffbda0c57b63763e093eb854bf6e66c29b4f52743dc85edd5a4c05c956`；舊 75 列全文不變、新 row 四欄與 SQL 原文一致。
- 部署：從乾淨 asset main build／force-recreate business-services（image `5bad6038…`），12 秒 healthy；JAR 內 policy_manifest、model_inputs、9090-api-swagger.md、d201-registration.sql、d201-policy-document.json 與合併來源逐位元相同。啟動入口全部 no-op（指數 self-heal 略過、分析無需補跑、快照 roll 0、無備份補跑）。
- 日報工作區 `/Users/steven/Project/SRPP` `--ff-only` 至 main；`verify_9090.py` PASS；report-calculations 精確指紋 GET（Claude／Codex）皆 200，僅為診斷。
- 驗證：9 條 `--check`、ruby 6 runs／264 assertions／0 failures、`spec-check` 0 BLOCK、Java 針對性測試 213 個 0 失敗。
- 審查：Codex 額度用完，Claude 審查代理三輪（前兩輪各修一處驗證器漏洞，第 3 輪可通過）。
- 收據：SRPP `reports/t628-release-20261123/`。正式 report-calculations 採用待今天 12:00 午報實跑。
