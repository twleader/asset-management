# [t531] 發布 009826 建倉腿確定性計算器的規則包身分

**對應 Requirements:** Requirement 187（背景不可變報告包與純讀採用）、Requirement 193（現行規則包 authority 可重現發布）
**前置任務:** t528（同一條發布鏈；`1880df4d…` 身分）；t530 為另一 worktree 進行中的獨立任務，本任務不依賴也不修改其內容
**Liquibase changeset:** 無

## 背景

2026-11-02 12:00 SRPP 兩軌輸入全同（規則包 `1880df4d…`、L0 資產、9090 計算包 coverage、009826 雷達 `NO_TRADE`、D-188 `NOT_HIGH_BUY_ALLOWED`），只有 009826 分歧：Claude 依 D-143 建議 1 張、Codex 判 0 張。根因是規格缺口：9090 計算包 `GLOBAL_EQUITY_PACE` 回 `legs:[]`、`STRATEGIC_BUY_GATES` 為 UNAVAILABLE，SRPP 也沒有確定性張數計算器。SRPP 以決策「2026-11-02：D-143 009826建倉腿由確定性計算器產出張數」新增 `scripts/execution_policy.py --global-build-009826`（每日1張、每週3張、月上限、買後不超5%、只計已確認成交、無法驗證即0張），並把上限明定為預設買量。規則包 hash 由 `1880df4d7081b7d2c7d2b2694a4a6ae63151889677ca8df1049c678738b523fd` 變為 `c7bd8a8a32851eda01a4dc659d84751f5c589e347e97fec781a589cbb1a4379f`。新 hash 在 9090 registry 未登錄前，`GET /api/public/srpp/report-calculations` 以新 hash 查詢回 409 `POLICY_UNSUPPORTED`（2026-11-02 12:48 唯讀實測；舊 hash 同參數回 200 FINAL）。本任務只發布新 bundle 身分，不改任何投資規則、金融公式、API、schema、Swagger、producer、排程或資料表；9090 計算包仍不含 009826 leg，009826 張數由 SRPP 本機計算器依 `API_CALCULATION_RULES.md` §2 回退。

## 要做什麼

- [ ] 531.1 來源固定為 SRPP main `3bd5acf655edd09e3caf25e2cc94ce5a68753ab7`（已 push，origin/main 讀回一致；feature `04513e733d0328f75f9fe36cecc89aeb52fc3ca2`），使用乾淨 feature worktree 作 `--srpp-root`。同步 `policy_manifest.json`（raw SHA `6a0458f4f21eb9558e89a340215820512bc3b81800780d729509e9f83dd1732c`）與 `model_inputs.json`（SHA `955ea724d111ce516d7f354fac705f309f293a5cc19fc00e9bcc7cf7c8127fd0`，舊 `8ee4eb614a73099455b25d163fce6b675e543e1120963add38d0d7d96f8a8b6d`）完整 bytes 到 `backend/src/main/resources/srpp/decision/`。與 t528 不同：本次 `model_inputs.json` 位元組有變（僅 `global_equity_build_cadence_d141.009826` 新增計算器契約五鍵，數值不變），必須一併同步，否則 registry 工具回 `POLICY_BYTES_MISMATCH`。先在該 worktree 驗 `policy_bundle.py check --purpose daily` 的 Claude／Codex 兩個 consumer 與 `daily_runtime_contract.py check` 皆 PASS。
- [ ] 531.2 以原 `ruby scripts/srpp-report-policy-register.rb` 重產 `d201-registration.sql`（insert-only、`ON CONFLICT DO NOTHING`、僅 bundle 字串不同）；以原 `generate-srpp-report-golden.py` 與 `generate-srpp-state-topup-golden.py` 重產兩份 golden。兩份 golden 只允許兩類欄位變動：`policyBundleSha256` 與 `originalCalculatorHashes["execution_policy.py"]`（`af21a834…` → `f408f8a1…`，因 SRPP 計算器檔案新增函式）；金融 expected 與其他 calculator hash 逐值不變，任何其他差異先停止回報，不得更新 expected 掩蓋。
- [ ] 531.3 不修改 API／schema／Swagger／producer／排程／資料表／Java calculator；Swagger SHA 維持 `40819f617271e21b33efbca040134c3024e4915d1d34c735fa3b6a2489d45afe`。9090 `GLOBAL_EQUITY_PACE` 仍 PARTIAL 且 `legs:[]`、`STRATEGIC_BUY_GATES` 仍 UNAVAILABLE，不得因本任務宣稱 API 已支援 009826 leg。
- [ ] 531.4 完整 backend Maven、authority／golden 檢查、兩支回歸測試（需 `SRPP_RELEASE_WORKTREE`）、Ruby registry 測試、renderer／sync 只讀 check、`bash scripts/spec-check.sh`、`git diff --check` 全綠。
- [ ] 531.5 依 commit-merge-push 完成 asset feature commit、no-ff main merge、push 與 remote-ref readback；之後才對 registry 單次 INSERT 受追蹤 SQL（`ON CONFLICT DO NOTHING`），唯讀讀回新 row 並確認舊 rows 不變。不 UPDATE／DELETE、不新增 migration、不 refresh／補抓／寄送。
- [ ] 531.6 僅從乾淨 asset main 經 run-stack rebuild／recreate business-services；重啟前依當下台北日期唯讀逐項證明啟動及有界觀察期間的外抓／寄信／資產修改入口皆 no-op（同 t528.6 清單）；任一項無法證明則維持部署 pending 並具名回報。部署前後各以 `docker inspect` 釘住 image id，確認未被其他 session 覆蓋。
- [ ] 531.7 分開記 registry 精確 row、JAR embedded manifest／model 身分、服務 serve。`report-calculations` 讀回只在目標交易日台北 09:05≤now<13:30 且 registry row 與 JAR authority 都已讀回後進行：先唯讀 `GET /api/public/trading-calendar?year=2026` 確認可用與目標日 `twTrading=true`，再以 `tradingDate`、`consumer`、`email=tw.leader@gmail.com`、新 `policyBundleSha256`、原 `swaggerSha256` 與當下合法 slot 讀回，要求 200 且 `identity.policyBundleSha256` 為新 hash。`CONTEXT_NOT_READY` 時等下一個 slot；窗口外為 `NOT_YET_EXECUTED`，不 POST、不重跑報告、不寄信、不交易。

## 驗證

```bash
SRPP=/Users/steven/Project/SRPP/.claude/worktrees/taiwan-stock-afternoon-diff-c9d21e
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

（531.1–531.4 離線驗收完成後填寫；531.5–531.7 另記，不提前宣稱完成。）
