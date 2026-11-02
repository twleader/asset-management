# [t528] 發布 Claude 入口提早收工修正的規則包身分

**對應 Requirements:** Requirement 187（背景不可變報告包與純讀採用）、Requirement 193（現行規則包 authority 可重現發布）
**前置任務:** t522（已發布 da75635e… 身分的同一條發布鏈）
**Liquibase changeset:** 無

## 背景

2026-11-02 Claude 09:05 比較軌因「預估 51 批權威條文讀不完」在第 2 分鐘以 INCOMPLETE 收工、零草稿。SRPP 已修正兩份 Claude 入口與日報規格 §2A（讀取包規模不是停止理由，僅實際工具失敗可停），規則包 hash 由 `da75635ef720e00776690b0c9566538cacc2580f9539865beb55952a39cb37f8` 變為 `1880df4d7081b7d2c7d2b2694a4a6ae63151889677ca8df1049c678738b523fd`。新 hash 在 9090 registry 未登錄前，`GET /api/public/srpp/report-calculations` 以新 hash 查詢回 409 `POLICY_UNSUPPORTED`，Claude 與 Codex 兩軌的 API 優先計算整輪回退本機計算器。本任務只發布新 bundle 身分，不改任何投資規則、金融公式、API、schema、Swagger、producer、排程或資料表。

## 要做什麼

- [x] 528.1 來源固定為 SRPP main `26602a98571f2e70b7dcdac221e46b38995de650`（已 push，origin/main 讀回一致；feature `81219a6cb935cc3afdef11d6d8a8413f237003ce`），使用乾淨 feature worktree 作 `--srpp-root`。同步完整 `policy_manifest.json` bytes 到 `backend/src/main/resources/srpp/decision/`；`model_inputs.json` 位元組不變（SHA `8ee4eb614a73099455b25d163fce6b675e543e1120963add38d0d7d96f8a8b6d`）。新 manifest raw SHA `f9ff141a3bafee78e4b83b752522eac967d5a3369d2b54ebfebd207f5c5a636f`。先在該 worktree 驗 `python3 scripts/policy_bundle.py check --purpose daily --consumer Codex` 與 `--consumer Claude`、`python3 scripts/daily_runtime_contract.py check` 皆 PASS，並讀回 manifest SHA；兩個 consumer 的 `POLICY_BUNDLE_SHA256` 須同為新 hash。
- [x] 528.2 以原 `ruby scripts/srpp-report-policy-register.rb` 重產 `backend/src/main/resources/srpp/report/d201-registration.sql`（insert-only、`ON CONFLICT (policy_bundle_sha256) DO NOTHING`；與舊檔僅 bundle 字串不同）；再以原 `generate-srpp-report-golden.py` 重產 `report-same-input-golden.json`（僅 `policyBundleSha256` 一欄變）。不手填 expected、不改 calculator hash。
- [x] 528.3 不修改 API／schema／Swagger／producer／排程／資料表；Swagger SHA 維持 `40819f617271e21b33efbca040134c3024e4915d1d34c735fa3b6a2489d45afe`。renderer／sync 只做 `--check --srpp-root`，不做跨 repo mutation。
- [x] 528.4 完整 backend Maven、authority／golden 檢查、兩支回歸測試（`scripts/tests/check-srpp-report-authority-test.py`、`scripts/tests/generate-srpp-state-topup-golden-test.py`，需 `SRPP_RELEASE_WORKTREE`）、Ruby registry 測試、spec-check、`git diff --check` 全綠，並經獨立 subagent 的 `/spec-review` 與 diff-scoped 審查。
- [ ] 528.5 依 commit-merge-push 完成 asset feature commit、no-ff main merge、push 與 remote-ref readback；之後才對 registry 單次 INSERT 受追蹤 SQL，唯讀完整讀回新 row 並確認舊 rows 不變。不 UPDATE／DELETE、不新增 migration、不 refresh／補抓／寄送。
- [ ] 528.6 僅從乾淨 asset main 經 run-stack rebuild／recreate business-services；main 的 `.env`／secrets 是部署基準，保留 BFF 與正式資料。重啟前依當下台北日期唯讀逐項證明所有啟動及有界觀察期間的外抓／寄信／資產修改入口皆 no-op：analysis self-heal（未到最早啟用寄送時間、無啟用時間、停用或今日已有 OK；時間已過且今日無 OK 會觸發外抓／寄信則停止部署）、所有日期 analysis PROCESSING=0 且期間不跨任何啟用寄送分鐘、十個 index 皆最新、Fubon ETF startup 原設定停用或 radar candidates 全有 success snapshot、SnapshotDateRoll 對所有 owner 最新快照與到期在途款符合原 roll no-op、配息 backfill 原設定未啟用、其他 startup／backfill／export 入口逐項查既有 gate。任一入口無法證明會略過則維持部署 pending 並具名回報，不得靠事後發現副作用代替事前證明，也不得修改旗標或補造資料繞過。部署後有界等健康 120 秒與安全 GET 30 秒，親核 analysis／index 略過 logs 且前後無新增寄送／補抓，不拿舊收據冒充本輪；跨日須重新查。
- [ ] 528.7 分開記：registry 精確 row、JAR embedded manifest／model／Swagger 身分、服務 serve。`report-calculations` 讀回只在目標交易日台北 09:05≤now<13:30 且 registry row 與 JAR authority 都已唯讀回讀後進行：先唯讀 `GET /api/public/trading-calendar?year=2026` 確認 200、`availability.tw.status="AVAILABLE"` 與目標日 `twTrading=true`，再以必填的 `tradingDate`（Asia/Taipei 今天）、`consumer`、`email=tw.leader@gmail.com`、新 `policyBundleSha256`、原 `swaggerSha256` 與**當下合法 slot**（09:05≤t<12:00 為 `09:05`，12:00≤t<14:00 為 `12:00`，不得早於 slot）讀回，要求 200 且 `identity.policyBundleSha256` 為新 hash。producer 只在各 slot 以當前 JAR 的 bundle 產包，重啟後若該 slot 的新 hash 包尚未產生（`CONTEXT_NOT_READY`），等下一個 slot 起點後再讀；09:05 前、13:30 後或尚未到目標日均記 `NOT_YET_EXECUTED`，不得手動 POST observer、重跑報告、寄信或交易。

## 驗證

```bash
SRPP=/Users/steven/Project/SRPP/.claude/worktrees/taiwan-stock-report-missing-ed0dfa
python3 scripts/policy_bundle.py check --purpose daily --consumer Claude   # 於 $SRPP 執行，另跑 --consumer Codex 與 daily_runtime_contract.py check
SRPP_RELEASE_WORKTREE=$SRPP python3 scripts/tests/check-srpp-report-authority-test.py
SRPP_RELEASE_WORKTREE=$SRPP python3 scripts/tests/generate-srpp-state-topup-golden-test.py
python3 scripts/check-srpp-report-authority.py --srpp-root $SRPP --check
python3 scripts/generate-srpp-report-golden.py --srpp-root $SRPP --check
python3 scripts/generate-srpp-state-topup-golden.py --srpp-root $SRPP --check
ruby scripts/render-9090-openapi-docs.rb --check --srpp-root $SRPP
python3 scripts/sync-srpp-swagger-identity.py --check --srpp-root $SRPP
ruby scripts/tests/srpp-report-policy-register-test.rb
ruby scripts/tests/srpp-policy-register-test.rb
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test
bash scripts/spec-check.sh
git diff --check
```

離線指令不取代部署驗收；registry 精確 row、JAR 身分與 9090 runtime 讀回另記收據。

## 完成報告

2026-11-02 離線實作與驗收完成；registry、main 部署與合法時窗 API 讀回由 528.5–528.7 另記，不提前宣稱完成。

- SRPP main `26602a98571f2e70b7dcdac221e46b38995de650` 已 push，origin/main 讀回一致；Codex／Claude daily policy 與 runtime contract check 均 PASS，新 bundle `1880df4d7081b7d2c7d2b2694a4a6ae63151889677ca8df1049c678738b523fd`。
- 僅三個實質 artifact 差異：完整 manifest bytes、原 Ruby 生成 SQL 的 bundle 字串、原 Python 生成主 22 案例 golden 的 bundle 欄；model、Swagger、formal targets、金融 expected 與 calculator hash 不變。
- 完整 backend Maven：351 suites／3319 tests／0 failures／0 errors／2 skipped；authority checker、state generator 回歸、Ruby 5＋8 runs、三份 Swagger renderer／sync 只讀 check、spec-check BLOCK 0、`git diff --check` 全綠。
- 獨立 subagent 審查：發現 Task 編號與另一 worktree 的 527 撞號（已改 528）、讀回參數缺 `tradingDate`／slot 規則與 `NOT_YET_EXECUTED`、驗證清單缺 Claude consumer 與兩支回歸測試，皆已修正；artifact 差異與 SHA 逐項核對無誤。
