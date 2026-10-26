# [t514] 發布 SRPP 八項判斷矛盾修正後的精確規則包

**對應 Requirements:** Requirement 193（SRPP 現行規則包 authority 發佈鏈）
**前置任務:** t499、t504；SRPP 八項修正須完成獨立審查與測試
**Liquibase changeset:** 無

## 背景

2026-10-26 使用者明確要求修正 SRPP 八項矛盾（牛市開窗與回撤條件、退休支出與戰術訊號耦合、落袋債券固定比例、跨市場完成交易日選取、跨市場局部缺值、重複除息金額衝突、SGOV 適用範圍、00882 分批退出），重算規則包 hash，驗證 9090 相容並將所有變更專案 commit/merge/push。SRPP 修改由另一受控 worktree 完成；本任務只發布已驗證最終 authority 與既有 API 的必要計算 parity。

歷史 D-204/2.53.0/3.22.0 與舊 hash 不能當成本輪 authority。現有 Java/Ruby report gate 已用十欄候選語意驗證，沒有 D-204 版本硬鎖；不得為發版新增硬鎖或放寬驗證。今天非台股交易日，業務 runtime 驗收必須如實記 NOT_YET_EXECUTED。

## 要做什麼

- [x] 514.1 唯一來源是 `/Users/steven/.codex/worktrees/srpp-logic-repair-20261026/SRPP` 完成獨立審查與測試後的最終 bytes。先執行 daily/Codex policy-bundle check 及 daily-runtime-contract check；從通過檢查的 model/manifest 原始 bytes 讀回並記錄新 decision、policy/model version、model SHA、manifest SHA 與 POLICY_BUNDLE_SHA256，不預填、不手改 hash、不混納未完成 source。
- [x] 514.2 將最終 model/manifest 逐位元同步 `backend/src/main/resources/srpp/decision/`。保留 strict JSON duplicate/raw-byte/JCS 驗證；report candidate 必只有十欄並維持精確值：publication_status=PUBLISHED_SWAGGER_1_26_0、daily_adoption_status=ENABLED_TRIAL_RUNTIME_PENDING、enabled_for_daily=true、report_slots=[09:05,12:00]（順序敏感）、uses_legacy_api_slot_mapping=false、calculator_version=SRPP_REPORT_CALCULATIONS_V1、consumer_verifier=scripts/verify_srpp_report_calculations.py、prerequisites=[CURRENT_POLICY_REGISTRATION,INDEPENDENT_CONSUMER_VERIFIER]（順序敏感）、observations_pending=[SCOPED_SAME_INPUT_GOLDEN_PARITY,TRADING_DAY_RUNTIME_ACCEPTANCE]（順序敏感）、fallback_reason=REPORT_CALCULATION_SCOPE_UNAVAILABLE。read_only_routes 只要求包含 exact `/api/public/srpp/report-calculations`，保留合法 projection routes。
- [x] 514.3 從同一 model 產生 formal targets document，若 bytes 相同不製造內容變更；以既有 Ruby generator 產生並追蹤冪等 INSERT-only `d201-registration.sql`。再從該 source root 顯式重產 `report-same-input-golden.json`，保留原22 deterministic cases。若八項修正影響 Java 已支援 calculator，先以真實相同輸入 parity 測試修正 Java 純計算與必要回歸，不能只更新 expected 掩蓋差異。保留歷史檔名、不改歷史 rows/receipts，不新增 API、DB migration、producer 或 scope。
- [x] 514.3a `scripts/generate-srpp-state-topup-golden.py` 改為必要的 `--srpp-root <absolute-path>`，拒絕relative、缺省或不存在的來源；不得保留primary的隱式fallback。先驗同一root官方daily/Codex bundle與backend embedded model/manifest bytes一致，再載入該root原計算器，mutation僅重產既有 `backend/src/test/resources/srpp/report-state-topup-golden.json`；新增唯讀 `--check` 逐位元比較完整預期fixture（含三個originalCalculatorHashes與全部22 case），任何hash/case/格式漂移非0，check不寫source/backend/fixture。因本輪execution_policy.py變更，同時重產主report22案例golden與state-topup22案例source hashes，再由Java測試驗同輸入parity；不得改income/payroll/short/us fixtures。新增 `scripts/tests/generate-srpp-state-topup-golden-test.py`，覆蓋顯式指定本輪feature root、缺省/relative/missing root拒絕、官方來源或embedded bytes mismatch拒絕、check通過零寫入、stale source hash及case mutation各失敗且零寫入。可在測試內使用隔離檔案副本與mock限定輸出以驗拒絕，不能改真來源/已追蹤fixture。所有需真來源的測試命令都顯式帶 `SRPP_RELEASE_WORKTREE` 指向本輪root。
- [x] 514.4 本次 OpenAPI 路由/schema/Swagger bytes 不變。SRPP source 先提交為乾淨 feature worktree，再執行 renderer --check --srpp-root、sync --check --srpp-root 驗三份 Swagger 與 consumer identity。只有真正的 Swagger identity 變更才能在乾淨 managed feature worktree 按 renderer→sync→renderer --check→sync --check 順序 mutation；仍只允許 mirror/schema/runtime-contract/manifest 四個固定 dirty 產物，不放寬 gate。authority release gate 使用同一 source root，完整 backend suite、Ruby registry tests 與既有 API contracts 必通過。
- [x] 514.5 驗收後各 repo 完成 feature commit、no-ff merge、push 及 remote-ref readback；只從乾淨 asset-management main rebuild/recreate business-services，保留 main 部署設定、BFF/gateway 與券商 flags。之後由授權 release operator 執行追蹤的本輪 insert-only SQL，唯讀核對 registry 精確 row、running jar embedded model/manifest/bundle、live Swagger 身份。全程不得 UPDATE/DELETE 歷史 registry、手動 producer、建立計算包、刷新行情、observer POST、寄信或券商操作。
- [ ] 514.6 先按已讀 OpenAPI 執行 safe GET `/api/public/trading-calendar?year=2026`，要求200及 availability.tw.status=AVAILABLE，依 days[].twTrading=true 選下一目標交易日。2026-10-26 的 report-calculations 業務成功驗收記 NOT_YET_EXECUTED，不發驗收請求。只有目標日台北09:05（含）至13:30（不含）、新 registry/jar 都已讀回時，才以相同精確 policyBundleSha256/swaggerSha256 唯讀 GET 驗收。健康、靜態hash、calendar200、歷史package均不能證明金融API成功；未執行不得寫200或「已確保成功」。

## 驗證

```bash
python3 /Users/steven/.codex/worktrees/srpp-logic-repair-20261026/SRPP/scripts/policy_bundle.py check --purpose daily --consumer Codex
python3 /Users/steven/.codex/worktrees/srpp-logic-repair-20261026/SRPP/scripts/daily_runtime_contract.py check
ruby scripts/render-9090-openapi-docs.rb --check --srpp-root /Users/steven/.codex/worktrees/srpp-logic-repair-20261026/SRPP
python3 scripts/sync-srpp-swagger-identity.py --check --srpp-root /Users/steven/.codex/worktrees/srpp-logic-repair-20261026/SRPP
ruby scripts/srpp-report-policy-register.rb > /tmp/srpp-task514-registration.sql
cmp /tmp/srpp-task514-registration.sql backend/src/main/resources/srpp/report/d201-registration.sql
python3 scripts/generate-srpp-report-golden.py --srpp-root /Users/steven/.codex/worktrees/srpp-logic-repair-20261026/SRPP
python3 scripts/generate-srpp-state-topup-golden.py --srpp-root /Users/steven/.codex/worktrees/srpp-logic-repair-20261026/SRPP
python3 scripts/generate-srpp-state-topup-golden.py --srpp-root /Users/steven/.codex/worktrees/srpp-logic-repair-20261026/SRPP --check
SRPP_RELEASE_WORKTREE=/Users/steven/.codex/worktrees/srpp-logic-repair-20261026/SRPP python3 scripts/tests/generate-srpp-state-topup-golden-test.py
python3 scripts/check-srpp-report-authority.py --srpp-root /Users/steven/.codex/worktrees/srpp-logic-repair-20261026/SRPP --check
SRPP_RELEASE_WORKTREE=/Users/steven/.codex/worktrees/srpp-logic-repair-20261026/SRPP python3 scripts/tests/check-srpp-report-authority-test.py
ruby scripts/tests/srpp-report-policy-register-test.rb
ruby scripts/tests/srpp-policy-register-test.rb
bash scripts/spec-check.sh
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test
git diff --check
```

以上從本任務 asset feature worktree 執行；SRPP source 需先完成獨立修正與審查、bytes同步及SQL重產，再跑兩組 golden 與 release gate；renderer/sync check 要等來源提交乾淨。本任務無 schema 變更，不重產 schema.sql。先由獨立 subagent 做 spec 審查，再改 backend；實作後獨立架構審查完整 diff。依專案 run-stack 及 commit-merge-push 流程完成驗收/落地，正式重建在兩 repo main 已推送後從 clean main 執行：

```bash
docker compose -p asset-management build business-services
docker compose -p asset-management up -d --no-deps --force-recreate business-services
curl --fail --silent --show-error 'http://127.0.0.1:9090/api/public/trading-calendar?year=2026'
```

SQL 執行及精確 registry/jar/hash 讀回使用現有授權 release 入口；不得在證據輸出顯示任何 secrets。非交易日 report GET 不執行，不因驗收而建立 package。

## 完成報告

2026-10-26 已完成測試、feature runtime 驗收、兩 repo 的 main 發布、正式 clean-main 部署與 registry/JAR 精確讀回；合法交易日時窗的 report-calculations 業務驗收仍為 `NOT_YET_EXECUTED`，514.6 保持未完成。

- 最終 SRPP source 已於 feature commit `30505db`、main no-ff merge `fd3e406` 完成遠端精確 readback；本次 authority 採該 source 的原始 bytes，decision `D-204`、policy `2.53.0`、model `3.22.0` 維持來源既有值，識別本輪發布以精確 bundle hash 為準。
- `POLICY_BUNDLE_SHA256=b1f5c448dbc3b67e13de93c4c23259e3f4bfeb63e01accd63990f1b28deba340`；model SHA-256 `8ee4eb614a73099455b25d163fce6b675e543e1120963add38d0d7d96f8a8b6d`；manifest SHA-256 `0b0866e6cd60454e3f288ee648d611ca28d4351a328dda67827d806caa5b7ace`。
- 兩組各 22 案例的 Java/Python 同輸入 golden parity 通過；完整 backend suite 共 3,315 tests、0 failures、0 errors、2 個既有略過。Python 9 項與 Ruby 13 項回歸通過；乾淨 source 的 renderer/sync checks、authority check 及 diff check 通過。完整 backend 摘要見 [`validation-summary.json`](../../reports/task514-srpp-logic-release-2026-10-26/validation-summary.json)。
- feature runtime 狀態 `PASSED_FEATURE_RUNTIME`：僅重建/recreate business-services，image `sha256:f081f4e9ba72c7dfcb8b213d4a8f1300033f5df32b28d066af075ac64edcbdf0`，容器 healthy；有效 env 與 mounts 對 main 一致，BFF/gateway 容器未動。9090 safe GET market-index 200、quotes 200（58 rows）；運行 JAR model/manifest/Swagger/SQL/formal document bytes 皆與本輪資源一致。詳見 [`feature-runtime.json`](../../reports/task514-srpp-logic-release-2026-10-26/feature-runtime.json)。
- 本專案 feature commit `1100ff1f068ee051e9cd34ffcfe7bf9c641bcbff`、no-ff main merge `6937cd30b150ab3f429bb0927256125a5ce0a0e8` 已 push，兩 remote refs 精確 readback。正式部署以乾淨 main `6937cd30b150ab3f429bb0927256125a5ce0a0e8` 為 build source，僅重建/recreate business-services；image `sha256:53f242e176d2fca38fc25ccb16e9d0facbef1f97890b005a8123e0f26d416fa4` healthy，驗收前後 image 穩定，env/mounts/broker flags 與 main 一致，BFF/gateway 未動。
- 正式 main runtime 狀態 `PASSED_MAIN_DEPLOYMENT_STATIC_AND_SAFE_GET_ACCEPTANCE`：safe GET market-index 200、quotes 200（58 rows）、calendar 200。registry 僅執行追蹤 SQL 的 INSERT，13→14 rows、exact row count 1，歷史 rows 未變；formula version `ASSET_MGMT_SRPP_V2`，formal document/manifest canonical 一致，calculation policy SHA-256 `621b6a14f4b86d9d4ebb9a0d1ce592c8ffd7b9dc73e2cef4fa91f22e3b98ad93`、formula set SHA-256 `0f3d9b67dcb7d519f8ef5e9ee378d86eaf26228409199bc487036732e0186e86`。
- 運行 JAR 五份資源（model、manifest、Swagger、registry SQL、formal document）逐位元對本輪 build source 一致；PublishedSwaggerIdentity 的來源是運行 JAR `9090-api-swagger.md` bytes，SHA-256 `40819f617271e21b33efbca040134c3024e4915d1d34c735fa3b6a2489d45afe`。本次沒有 report-calculations response，不能宣稱由 live report GET 讀回 Swagger identity。完整正式收據見 [`main-runtime.json`](../../reports/task514-srpp-logic-release-2026-10-26/main-runtime.json)。
- 後續提交僅保存收據與完成報告，backend tree 與部署 build source `6937cd30b150ab3f429bb0927256125a5ce0a0e8` 相同，無需重建；最終純紀錄 feature/main SHA 由發布回報提供。
- report-calculations 業務 runtime 驗收仍為 `NOT_YET_EXECUTED`。calendar 回讀 `availability.tw.status=AVAILABLE`、2026-10-26 `twTrading=false`，下一個 `twTrading=true` 為 2026-10-27；只允許該日台北 09:05（含）至 13:30（不含）以同組 policyBundleSha256/swaggerSha256 唯讀 GET 驗收。今天未發該 GET、未建立 package；calendar 200、靜態資源一致與服務健康不能替代業務驗收。
