VERDICT: CHANGES_REQUESTED

（備援審查紀錄：Codex 額度用完，依 Steven 授權由 Claude Sonnet 5.5 代審；唯讀，只用 shasum／grep／git／python3 --check／唯讀 SELECT 查證，未寫入任何 repo 檔案。）

## 判定
critical: 0   major: 1   minor: 1

## Findings
### [Major] 627.1 標記完成，但 t626 的 order-plan v2 golden 對凍結 SRPP 已漂移（`--check` 失敗），且 t627 的驗證清單漏列 t623–t626 新增的四支 golden `--check`，因此發布前的驗證抓不到
位置：spec/tasks/t627_srpp_d234_release.md:17（627.1，已勾 `[x]`）、:21-34（驗證區塊，只列 report-same-input／long-short-exit／state-topup 三支 golden）；漂移檔 backend/src/test/resources/srpp/order-plan-v2-golden.json:323-327
證據：
- 在凍結的 SRPP root（/Users/steven/Project/SRPP/.claude/worktrees/asset-management-logic-review-d1cbbc，HEAD 204d4ae）實跑 `python3 -B scripts/generate-srpp-order-plan-v2-golden.py --srpp-root <該 root> --check`，結果 `ORDER_PLAN_V2_GOLDEN_DRIFT`，exit 1。
- 以 `importlib` 載入產生器呼叫 `build(srpp_root)` 與現存 golden 逐 key 比對（沒有寫檔）：只有頂層 key `srppSources` 不同，22 個案例的內容完全相同。差異是 `trade_blocker_policy.py`：golden 內記 `fd45e66937ec2a07…`，凍結 SRPP 實際為 `c4c51f95f06604d5…`（`shasum -a 256 scripts/trade_blocker_policy.py`，與 `backend/src/main/resources/srpp/decision/policy_manifest.json:237` 的 `scripts/trade_blocker_policy.py` 雜湊一致）。
- 成因：SRPP 提交 204d4ae 對 `scripts/trade_blocker_policy.py` 新增 `pooled_headroom`／`api_pooled_headroom`（`git show 204d4ae -- scripts/trade_blocker_policy.py`）；golden 檔 mtime 為 11/22 14:13，早於該檔 18:22 的修改。
- 同樣的四支新 golden 裡，`generate-srpp-d215-fx-golden.py --check` 通過（D215_FX_GOLDEN_PASS:17）、`generate-srpp-pooled-headroom-golden.py --check` 通過（POOLED_HEADROOM_GOLDEN_PASS:16）、`generate-srpp-satellite-build-golden.py --check` 通過（SATELLITE_BUILD_GOLDEN_PASS:70）；只有 order-plan-v2 失敗。
- t627 的 627.1 只寫「重產 report-same-input、long-short-exit、state-topup golden」，驗證區塊也只有這三支；t623–t626 各自的驗證清單明列的 `generate-srpp-satellite-build-golden.py`／`generate-srpp-pooled-headroom-golden.py`／`generate-srpp-d215-fx-golden.py`／`generate-srpp-order-plan-v2-golden.py --check`（t626 spec 第 222 行）全部沒有進 t627 的驗證。
- 先例：t620 的 620.1 同樣把「state-topup golden 只變計算器雜湊」這類只變來源指紋的 golden 納入發布任務重產，所以這類漂移屬 release 任務的範圍。
被違反的出處：t626 spec 第 222 行把 `generate-srpp-order-plan-v2-golden.py --srpp-root <D-234 SRPP root> --check` 列為必過；t626 626.21 要求以同一 SRPP root 重產本任務 golden 並 `--check` 證明；t627 自己的「完成」宣告（`[x]` 627.1）與「golden 只變 bundle 與計算器雜湊行」敘述（627.1，第 17 行）在 order-plan-v2 上不成立。
影響：不修的話，t627 照寫的驗證區塊會全部通過，但已進 main 的 t626 golden 與其產生器不一致；之後任何人跑 t626 規定的 `--check` 都會失敗，且 `srppSources` 這個「對照哪一版 SRPP 原始函式」的出處宣稱是錯的。Java 測試不會擋（`SrppOrderPlanServiceTest.java:183` 只斷言 `srppSources.size()==3`），所以只有 spec 驗證能擋。
修法：
1. 在凍結 SRPP root 以寫入模式重產：`python3 -B scripts/generate-srpp-order-plan-v2-golden.py --srpp-root /Users/steven/Project/SRPP/.claude/worktrees/asset-management-logic-review-d1cbbc`，預期 diff 只有 order-plan-v2-golden.json 第 326 行 `trade_blocker_policy.py` 那一個雜湊（案例內容已驗證不變）。
2. 627.1 補一句「order-plan-v2 golden 只變 `srppSources.trade_blocker_policy.py`（SRPP D-234 `pooled_headroom` 新增）；d215-fx／pooled-headroom／satellite-build golden 不變」。
3. t627 驗證區塊補四行：`python3 -B scripts/generate-srpp-{satellite-build,pooled-headroom,d215-fx,order-plan-v2}-golden.py --srpp-root <SRPP root> --check`，並補 `ruby scripts/render-9090-openapi-docs.rb --srpp-root <SRPP root> --check` 與 `python3 -B scripts/sync-srpp-swagger-identity.py --srpp-root <SRPP root> --check`（627.1 文字已宣稱這兩者 `--check` 通過，但驗證區塊沒有；兩者我已實跑，皆通過）。

### [Minor] 「SRPP 凍結來源」把最終 bundle 說成凍結，但最終狀態不是任何一個 commit，且漏列 policy_manifest.json 檔案雜湊
位置：spec/tasks/t627_srpp_d234_release.md:13（凍結來源）、:19（627.3）
證據：
- 第 13 行寫「第一個凍結 commit `204d4ae` 的 bundle `86a0df0e…`；…經 renderer 與 identity sync 後最終 bundle `a6415445…`」。實查 `git -C <SRPP> show 204d4ae:assumptions/policy_manifest.json` 的 `POLICY_BUNDLE_SHA256` 是 `86a0df0e…`（與第 13 行一致），但 SRPP worktree 目前 `git status --short` 為 `M assumptions/daily_runtime_contract.json`、`M assumptions/policy_manifest.json`、`M assumptions/srpp_report_calculations_schema.json`、`M docs/9090 Port API Swagger.md`——最終 bundle `a6415445…` 與 Swagger `83500499…` 只存在於這四個未提交檔案中，沒有 commit SHA。對照 t622 第 13 行是「凍結 commit `dec5330`，bundle `bef329db…`」，一個 commit 對一個 bundle。
- 我已驗證的部分都屬實：資產端 `policy_manifest.json` 與 SRPP 未提交檔位元組相同（sha256 `c1521fdf0cec96736d61ec584d54365236e234ec80639309f5802043cdc000fe`）；`model_inputs.json` 為 `e9e561a9…`、Swagger 為 `83500499…`（SRPP 與 `backend/src/main/resources/srpp/9090-api-swagger.md` 相同）；`ruby scripts/srpp-report-policy-register.rb` 的診斷輸出為 `policy=c6d1d10a… formula=9962ff17… model=e9e561a9…`，且其 SQL 與 `d201-registration.sql` 逐位元相同；`generate-srpp-formula-v3.py --check` 回 `9962ff17…`；`check-srpp-report-authority.py --check` 回 `a6415445…`；前一版 `bef329db…` 為 `HEAD:policy_manifest.json` 第 260 行，且已在 `srpp_policy_registry`（唯讀 SELECT 可見），`a6415445…`／`86a0df0e…` 尚未登錄，與 627.3 的待辦一致。
- 缺少 `policy_manifest.json` 自身的檔案雜湊：t622 第 13 行列了 manifest 的檔案雜湊（`261a17d3…`），t627 只列 `model_inputs.json`，而 627.1 的動作是「同步 `policy_manifest.json` 原始 bytes」，驗收需要它。
被違反的出處：t622／t620 的「SRPP 凍結來源」寫法（凍結 commit、bundle、兩個資料檔雜湊皆列）；627.1 自述的「凍結」字面。
修法：627.3 的 SRPP 端 commit 做完後，把第 13 行補上「第二個 commit `<最終 SHA>`（含上列四個檔案）」，並補 `policy_manifest.json` 檔案雜湊 `c1521fdf0cec96736d61ec584d54365236e234ec80639309f5802043cdc000fe`；在 627.3 明寫「SRPP 端先把 identity sync 產出的四個檔案提交為第二個 commit，再 merge」，避免 `204d4ae` 被誤當最終凍結點。

## 已查無發現
- 可查證性（hash／身分）：`shasum -a 256` 比對 SRPP 與資產端 `policy_manifest.json`（c1521fdf…）、`model_inputs.json`（e9e561a9…）、Swagger（83500499…）全部位元組相同；`POLICY_BUNDLE_SHA256` 在資產端為 a6415445…（`policy_manifest.json:262`，`d201-registration.sql`、report-same-input／long-short-exit golden 的 `policyBundleSha256` 亦同）；`git show HEAD:…` 的前一版為 bef329db…；`git show 204d4ae:assumptions/policy_manifest.json` 為 86a0df0e…，皆與 t627 第 13 行相符。policy document 雜湊 c6d1d10a… 與 formula V3 9962ff17… 由 `ruby scripts/srpp-report-policy-register.rb`（只輸出 stdout）與 `generate-srpp-formula-v3.py --check` 兩邊交叉確認，且 SQL 輸出與 `d201-registration.sql` 完全相同。
- 機械驗證實跑（皆唯讀）：`check-srpp-report-authority.py --check`、`generate-srpp-report-golden.py --check`（111 案）、`generate-srpp-long-short-exit-golden.py --check`（15 案）、`generate-srpp-state-topup-golden.py --check`（22 案）、`generate-srpp-formula-v3.py --check`、`render-9090-openapi-docs.rb --srpp-root … --check`、`sync-srpp-swagger-identity.py --srpp-root … --check`（CURRENT，swagger 83500499…／bundle a6415445…）、`ruby scripts/tests/srpp-report-policy-register-test.rb`（6 runs, 264 assertions, 0 failures）、`git diff --check`，全部通過。例外只有上述 order-plan-v2。
- 內部一致性／對現況斷言：t627 背景的 D-234 數值逐項對 `model_inputs.json`（`global_equity_build_cadence_d141.VT`、`global_equity_usd_dca_d182.funding`）：期間 2026-11-23～2027-06-30、每日 5 股、每週 15 股、每筆至少 5 股、月基準 53,333／上限 80,000、停止比重 0.025、國泰台幣複委託、美元用完改扣台幣並計入 D-152 台幣底線——全部相符。`docker-external-api.yaml` 版本 1.28.4（`HEAD`）→1.29.0（工作樹）與 t626 敘述相符。Requirement 220／221／222／223 存在（spec/requirements.md:7285／7299／7309／7321）且各對應 Task 623／624／625／626；Requirement 187／193／199 存在。前置任務 t623–t626 檔案存在，t627 編號未撞號。「Liquibase changeset：無」——變更清單沒有 db/changelog 檔。
- 審查紀錄：docs/reviews/t623_t626_d234_spec_review/ 有 round1–3 審查（round1／2 為 CHANGES_REQUESTED，round3 為 PASS）、各輪處理紀錄與 arch-review-round1.md（VERDICT: PASS），與 t627 背景「三輪 spec 審查＋實作後架構審查 PASS」相符。
- 架構鐵則：t627 為純發布任務，不新增 Controller／DTO／頁面／endpoint／分類，沒有適用項；627.3 沒有寫繞過 BFF 或直連外部行情；沒有寫死排程時點以外的字面時間（21:25–21:43 匯出排程避讓與 t620 先例相同、09:05 為報告時段字面，非 DB 可設定時點）。
- 資料來源／DB schema：spec-check 已 PASS B10（db/schema.sql 與 asset-postgres 逐位元一致）；t627 不斷言 schema，沒有發現。
- 發布鏈範圍：627.3 的 BFF 重建與實際變更相符（`PublicSrppProjectionService`、`SrppProjectionQuery`、`SrppReportResponseValidator`、`formula_manifest_v3.json` 皆在工作樹變更）；gateway／frontend 無變更。BFF 新增 fixture 內的舊 `bef329db…`／`cd08ecbb…` 是合成測試資料，既有 fixture（`e9dbac63…`／`f9298c51…`）也不隨正式 bundle 更新，沒有程式比對它們，不列 finding。

## 自我挑戰
- Major（order-plan-v2 漂移）：是否可能是我誤判？我用同一組輸入在凍結 root 重算，`--check` 回 exit 1 並印 `ORDER_PLAN_V2_GOLDEN_DRIFT`，且逐 key 比對只有 `srppSources` 不同、成因（204d4ae 改了 `trade_blocker_policy.py`）有 git 輸出佐證，所以事實本身沒有疑義。仍有可能被視為「只是出處雜湊、金融輸出不變」而降為 Minor；我保留 Major，是因為 t626 把該 `--check` 列為必過、t627 的 `[x]` 與驗證區塊會讓這個失敗在發布前完全看不到，而修法只是一次產生器重跑加幾行驗證指令。
- Minor（凍結來源未釘到最終 commit）：t627 第 13 行用了「第一個凍結 commit」措辭，作者很可能本來就打算第二個 commit，所以這條較接近「補寫清楚」而非錯誤；保留為 Minor，不影響 VERDICT。
- 撤下的疑慮：（1）diff 摘要沒列 `report-state-topup-golden.json`，但工作樹實際有 1 行 `execution_policy.py` 雜湊變更，與 627.1「state-topup 只變計算器雜湊」相符，是摘要本身漏列，不是 t627 的錯，撤下。（2）formula V3 manifest 未收錄 `SrppPooledFunding`／`SrppReportD215FxPreference`／`SrppOrderPlanV2` 的來源指紋：t624.9／t625.20 只要求因 `SrppReportModuleCalculator` 位元組變動而重產，沒有要求新增 calculationId，`--check` 與 authority gate 都通過，t627 也未對此作斷言，撤下。（3）BFF fixture 內舊 bundle 雜湊：見上，撤下。
