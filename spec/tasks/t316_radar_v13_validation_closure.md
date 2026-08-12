# [t316] 交易雷達 Treasury freshness、完整回歸與真實 holdout 結案

**對應 Requirements:** Requirement 65（資料時效與樣本外 gate 決定是否發布，拒絕亦須可解釋）
**前置任務:** t314（joint-fold/cost integrity）、t315（valuation provenance surfaces）
**Liquibase changeset:** `v1.101.0-radar-notification-action-policy-version.sql`（只新增通知 action-policy version；不改行情／回測資料）

## 背景

Task 318 已完成並落地主線：`TreasuryYieldServiceTest` 已直接證明收盤前／後、週末與連續休市、長假、恰 3／第 4 個 completed sessions、future curve fail-closed 與 provenance 保留；本任務不得重做或冒稱那些是待完成工作。真正尚未閉環的是兩條 production 邊界：第一，現行 `MarketDataService.isTradingDayKnown("美股", date)` 對非 null 日期固定回 `Optional.of(...)`，所以 `UNKNOWN_CALENDAR` 只靠 mock 可達，尚無 typed production adapter 把 authority failure 轉成 UNKNOWN；第二，Requirement 65 要求 V12 opportunity fallback 的**最終 action**也套 evidence gate，但 production 現況仍 pass-through，且通知只用 `RULE_VERSION` 判 baseline，若直接改 action 又維持 `TW_RULES_V12`，會把新 action 與舊 baseline 比較而產生假通知。

V13 停止點另只跑過 backend 127 個、external 20 個定向測試，尚未完成全模組回歸、frontend build、由本 feature image recreate 的實機服務、台美 API、逐分量估值輸出或真實 70/30＋walk-forward holdout。

本任務的完成判準是「取得可信結論」，不是「一定發布 V13」。真實 report 若 `REJECTED` 或 `INSUFFICIENT`，production 保留 `TW_RULES_V12` 就是正確結案；不得修改資料、縮小 holdout、降低 n/codes/fold gate 或改 candidate grid 來湊 promotion。

## 要做什麼

- [ ] **316.1 建立 production 可達的權威日曆 port，並保留 Task 318 回歸。** 新增純 backend `TradingRadarSessionCalendarPort`（名稱可等價，但不得直接暴露 nullable boolean），回傳 immutable `DayResolution(date,status,provider,reason)`；`status` 只有 `OPEN|CLOSED|UNKNOWN`：
  - production `MarketDataTradingCalendarAdapter` 是唯一 Spring adapter，委派既有 `MarketDataService.isTradingDayKnown(market,date)`；`Optional.of(true/false)` 分別映成 OPEN/CLOSED，null、`Optional.empty()` 或任何 authority/runtime failure 一律映成 UNKNOWN 並帶穩定 reason `MARKET_CALENDAR_UNAVAILABLE`。不得在 adapter 或 caller 用 weekday 猜測、空假日表當平日或吞錯後回 OPEN。
  - `TreasuryYieldService` production constructor 改為必要注入此 port；尋找 expected completed session 與計算 lag 的每個日期都只讀同一 port。任一 UNKNOWN 立即回 stale reason `UNKNOWN_CALENDAR`（可附 adapter reason/provider），同時保留 batch/provider/manifest/available-at provenance；不得再走 compatibility constructor 的 weekday fallback。Spring wiring test 必須載入真實 adapter，證明 production constructor 沒有繞過 port。
  - 新增 adapter contract tests，分別驗 OPEN、已知 CLOSED、empty、null 與 delegate exception；再由真實 `TreasuryYieldService`＋該 port 驗 UNKNOWN 出現在 expected-session 搜尋及 lag 中途時皆 fail closed。這些是 production wiring／failure semantics 測試，不得只在 service 外層 mock 最終 `RateContext`。
  - Task 318 的收盤前／後、長假、3／4 sessions、`FUTURE_CURVE_DATE` 與 stale provenance 測試全部保留為 regression，不改寫既有結果，也不得把它們重新計入本任務新增完成量。

- [ ] **316.2 Treasury → evidence → V12 final-action safety layer 與通知版本閉環。** 對 strict BOND profile 構造 fresh、stale、UNKNOWN 與 missing batch：fresh complete context 可為 AVAILABLE，但未 promoted beta 的 riskUnit 仍 null；stale/UNKNOWN 映成 `ASSET_SPECIFIC/bond_rate=STALE` 並保留 reason；missing batch 為 MISSING。各情境的 V12 **opportunity score、candidate action、`RuleParameters` 與 `ruleVersion`** 不得被 Treasury beta 改寫；registry 缺 key、任一 required horizon rejected/insufficient 時仍 resolve `V12_DEFAULT`。最終 action 則依 Requirement 65 統一呼叫現有 `TradingRadarEvidenceGate.apply(...)`，stale/UNKNOWN/missing 只能把 BUY／ADD／TRIAL_BUY 或缺風險證據的 REDUCE／EXIT 向 WATCH/HOLD 降級；頁面、快照、匯出與 `evaluateForNotification` 必須共用同一個 post-gate `StockDecision`，不得通知另一套 action。

  為避免維持 `TW_RULES_V12` 時把新 gated action 與舊通知 baseline 比較，新增獨立 `TradingRadarEvidenceGate.ACTION_POLICY_VERSION="EVIDENCE_GATE_V1"`（或同義單一常數），並完成以下契約：
  - `TradingRadarDto.Response`、快照與 JSON/Excel metadata 增加 `actionPolicyVersion`，值與 production gate 常數相同；舊快照缺欄顯示 null，不推導。
  - `trading_radar_notification_setting` 以 `v1.101.0-radar-notification-action-policy-version.sql` 新增 nullable `action_policy_version VARCHAR(40)`，並在 `db.changelog-master.yaml` 明確 include；entity 同步欄位。既有列保留 null，禁止 migration 猜填目前版本；不新增其他 notification/pending 欄位。
  - `TradingRadarNotificationService` 只有在 `initialized=true`、persisted `ruleVersion == RULE_VERSION` **且** `actionPolicyVersion == ACTION_POLICY_VERSION` 三者都成立時才把 baseline 視為有效。任一條件不符的首輪只保存目前 gated action/counter-trend、兩個版本並設 `initialized=true`，不 enqueue、不寄信，也不修改 `trading_radar_notification_state.last_notified_at`；下一輪才恢復既有 transition/cooldown 流程。
  - 測試直接構造舊列（V12＋null／舊 action policy）、新列及「兩版本皆相符但 `initialized=false`」的設定更新情境：首輪均零 enqueue，且 baseline/versions/initialized 正確重建、notification-state cooldown 零寫入；下一個真實 transition 才可通知。另斷言頁面與通知對同一 snapshot 的 final action bit-identical。`TradingRadarRuleEngine.RULE_VERSION` 與 ruleVersion DTO 仍固定 `TW_RULES_V12`，不藉 action-policy 版本偷升 V13。

- [ ] **316.3 全部本地回歸。** 在同一 commit 前依序執行 backend（含 ByteBuddy flag）、external-materials-service、BFF 全測試與 frontend `npm test`＋production build。若失敗，先判斷是否由本變更造成；本變更造成者修到通過，既有／環境失敗則保存完整命令、首個根因與受影響範圍，不得用定向測試冒充 full regression。

- [ ] **316.4 由本 worktree 重建實機 stack。** 不開 dev server；從本 feature worktree rebuild 並 force-recreate `business-services` 與 `frontend`，若 external/BFF source 未改可不 build。重建前、重建後及最終結論前都記錄 `asset-business-services`／`asset-frontend` 的 running image SHA、container start time、Compose project，並以 `docker compose -p asset-management images -q <service>` 比對當下 tag 指向；任何一次不一致都不得沿用先前證據。business recreate 後必須 restart BFF，待 healthy，再檢查該次 restart 以後的 BFF log 無 `Connection refused|500 Server Error`。另以 jar class/list 或前端中文字面值證明容器產物確實含本輪變更，不能只憑 health 下結論。驗證期間若 image ID 被另一 worktree 改寫，重新 build/recreate 受影響服務後再驗證。

- [ ] **316.5 實機資料面抽查。** 優先使用 in-app browser 已登入 session 開啟 `/trading-radar`，並在同一 session 呼叫 `GET /api/bff/trading-radar`；若環境沒有可用 session，才由 business container 呼叫 `GET /api/trading-radar`，顯式帶入由本機既有使用者解析出的 `X-User-Id`／`X-User-Role`／`X-User-Status`，不得硬編或輸出個資，且完成報告須標示未涵蓋 BFF session filter 的限制。抽查台股與美股交易雷達：
  - `ruleVersion=TW_RULES_V12`，除非 316.7 的全部 gate 實際通過且已另行核准發布；
  - `actionPolicyVersion=EVIDENCE_GATE_V1`，且頁面 final action 與同 snapshot 的通知評估 final action 一致；
  - 至少一檔適用個股的 PE/PB/殖利率逐 component status/provider/asOf/source URL 不串線，缺漏時有 reason；
  - 至少一檔台灣掛牌外幣債與一檔美股債的 Treasury batch/provider/curveDate/staleReason/riskUnit；
  - xlsx 與 JSON 同一快照的 18 個 valuation provenance 欄一致；
  - frontend 首頁與交易雷達頁可載入，瀏覽器 console 無本變更造成的 error。

- [ ] **316.6 真實 70/30＋5-fold holdout。** 先在 DB 執行只讀 preflight，逐市場輸出 `min(trading_date)`、`max(trading_date)`、distinct codes 與每檔日期涵蓋；共同期間固定為兩市場 market-min 的較晚者至 market-max 的較早者，若起訖不存在或反轉即 `INSUFFICIENT`。在執行前把固定 `from/to`、`universe=FULL_MARKET` 與 request SHA-256 寫入完成報告；不得看結果後移動。從 business container 呼叫 `POST /internal/backtest/rules`，request 必須實際帶入 `from`、`to`、`markets=[台股,美股]`、`horizons=[5,20,60,120]`、`calibrationRatio=0.70`、`walkForwardFolds=5`、`includeCloseFallbackSensitivity=false`，並**省略 `codes`**，使用服務按市場解析的完整 universe；不得只挑有利標的。

  `BacktestDto.V13Report` 新增 typed `universeMode=FULL_MARKET|BOUNDED_DIAGNOSTIC`，JSON/CSV 都須 echo。依既有 `BacktestDto.Request` 相容契約，`codes` 缺欄、null 或空陣列皆解析為 FULL_MARKET；非空陣列一律為 BOUNDED_DIAGNOSTIC。本任務不新增第二套 production-profile preflight：canonical `productionProfile(...)` 會依 decision-time valuation/profile evidence 解析，不能靠名稱或一次 SQL 重建。若完整 universe 因 timeout/OOM／資源限制無法完成，可另外保存 deterministic sorted bounded request 作診斷，但必須在建立 report-local promotion registry **之前** fail closed：`productionPromoted=false`、`promotedCandidateCount=0`、頂層 `selectedCandidates`／`selectedParameterSnapshots` 皆為空，每個 production key 一律標 `INSUFFICIENT_DIAGNOSTIC_ONLY`；診斷 candidate 只可留在既有 per-key execution row，不得新增可被 production resolver 解析的 registry/map，也不得補全 full-universe 證據。只有 FULL_MARKET report 可進 316.7 發布判定。
  - 新增 request/report 契約測試：`codes` 缺欄、null、空陣列三者皆為 FULL_MARKET 且保留原 promotion 流程；非空陣列為 BOUNDED_DIAGNOSTIC，並逐項斷言上述 zero/empty/no-registry invariant。
  - 保存每個 production key 的 required horizons、holdout n/codes、valid/passing folds、catastrophic fold、candidate ID、parameter snapshot、joint train/sigma/purge、status/reason。
  - close sensitivity、全期間統計、單一 horizon 或另一 profile 不得補 promotion 證據。
  - timeout/OOM/資料不足是 `INSUFFICIENT`，不可縮短 horizon、降低 gate 或刪除失敗市場後重稱通過。

- [ ] **316.7 發布判定與完成狀態。** 依 report 原樣分三類：
  - `REJECTED`：樣本足夠但 practical/consistency gate 未通過；保留 V12。
  - `INSUFFICIENT`：任一 required horizon、joint fold、sigma、n/codes 或 runtime evidence 不足；保留 V12。
  - `PROMOTED_CANDIDATE`：所有 required horizons/holdout/folds 通過；本任務仍不自動寫 production registry或改 `RULE_VERSION`，先輸出 exact key＋完整 parameter snapshot，因 production 發布會改動每日建議與通知，必須以另一次明確核准執行。

  最終完成報告逐項列出已完成與無法完成工作。不得把 focused tests、report-local registry count 或 `productionPromoted=false` 說成 production V13 已發布。

## 驗證

```bash
bash scripts/spec-check.sh
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -DextraArgLine=-Dnet.bytebuddy.experimental=true test
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f external-materials-service/pom.xml test
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f bff/pom.xml test
(cd frontend && /Users/steven/.nvm/versions/node/v22.21.0/bin/npm test)
(cd frontend && /Users/steven/.nvm/versions/node/v22.21.0/bin/npm run build)

docker inspect asset-business-services --format '{{index .Config.Labels "com.docker.compose.project"}} {{.Image}} {{.State.StartedAt}}'
docker inspect asset-frontend --format '{{index .Config.Labels "com.docker.compose.project"}} {{.Image}} {{.State.StartedAt}}'
docker compose -p asset-management images -q business-services frontend
docker compose -p asset-management build business-services frontend
docker compose -p asset-management up -d --no-deps --force-recreate business-services frontend
docker compose -p asset-management restart bff
docker compose -p asset-management ps
# business-services 未啟用 actuator；以真實交易雷達 read path 驗活，OWNER_ID 只在本機解析且不寫入報告。
docker compose -p asset-management exec -T business-services curl -fsS \
  -H 'X-User-Id: <OWNER_ID>' -H 'X-User-Role: ADMIN' -H 'X-User-Status: ACTIVE' \
  http://localhost:8080/api/trading-radar
# host 8080 是 BFF，只有這裡的 actuator 是有效 health probe。
curl -fsS http://localhost:8080/actuator/health
curl -fsS http://localhost:80/

# 記錄 BFF restart 的 UTC 起點後再查該時點以後的 log；任何命中都必須先釐清並重驗。
docker logs asset-bff --since '<BFF_RESTART_UTC>' 2>&1 | grep -E 'Connection refused|500 Server Error'

# DB preflight：先固定共同 from/to 與完整 universe coverage，完成報告保留輸出摘要。
docker compose -p asset-management exec -T postgres sh -c \
  "psql -U \"\$POSTGRES_USER\" -d \"\$POSTGRES_DB\" -v ON_ERROR_STOP=1 -c \"SELECT market, min(trading_date), max(trading_date), count(DISTINCT stock_code) FROM stock_price_history WHERE market IN ('台股','美股') GROUP BY market ORDER BY market;\""
docker compose -p asset-management exec -T postgres sh -c \
  "psql -U \"\$POSTGRES_USER\" -d \"\$POSTGRES_DB\" -v ON_ERROR_STOP=1 -c \"SELECT market, stock_code, min(trading_date), max(trading_date), count(*) FROM stock_price_history WHERE market IN ('台股','美股') GROUP BY market, stock_code ORDER BY market, stock_code;\""

docker compose -p asset-management exec -T business-services sh -c \
  'wget -qO- --header="Content-Type: application/json" --post-data="{\"from\":\"<PREFLIGHT_FROM>\",\"to\":\"<PREFLIGHT_TO>\",\"markets\":[\"台股\",\"美股\"],\"horizons\":[5,20,60,120],\"calibrationRatio\":0.70,\"walkForwardFolds\":5,\"includeCloseFallbackSensitivity\":false}" http://localhost:8080/internal/backtest/rules'
```

## 完成報告

### 本地實作與契約證據（2026-08-12）

- 316.1 已實作 typed `TradingRadarSessionCalendarPort` 與唯一 Spring production adapter
  `MarketDataTradingCalendarAdapter`。OPEN／CLOSED／empty／null／delegate exception 均有直接
  contract test；穩定原因 `MARKET_CALENDAR_UNAVAILABLE` 由 port contract 擁有，
  `TreasuryYieldService` 不參照 concrete adapter。production constructor 必須注入 port，
  expected-session 搜尋與
  lag 中途任一 UNKNOWN 皆回 `UNKNOWN_CALENDAR`，不再有 weekday fallback，並保留
  batch/provider/manifest/available-at provenance。真實 Spring context wiring test 證明 port 只有一個
  production bean。Task 318 既有收盤前／後、長假、3／4 sessions、future curve 與
  provenance regression 維持原結果。
- 316.2 已把 production `buildStock` 的最終 action 收旂到單一
  `TradingRadarEvidenceGate.apply(...)`；頁面、快照／匯出與通知共用同一 post-gate
  `StockDecision`。`TradingRadarRuleEngine.RULE_VERSION` 仍是 `TW_RULES_V12`，score、candidate
  action 與 V12 parameters 不變。strict BOND fresh／stale／UNKNOWN／missing 已直接驗證
  AVAILABLE／STALE／MISSING 及 reason/provider 保留；未 promoted beta 的 riskUnit 仍為 null，
  stale／UNKNOWN／missing 的買進 candidate 只降級最終 action，candidate action 保留。
  另以真實 production `evaluateForNotification` → `buildStock` mapper 注入 stable AVAILABLE
  beta 與上升利率 shock：fresh `bond_rate` 仍為 AVAILABLE disclosure，但 V12 一律
  `contextOnly`、`riskUnit=null`；即使整體 risk coverage 已達 70%，`asset_rate` 仍不是
  可用風險單位，原 `EXIT_CANDIDATE` 只保留 candidate，最終保守降為 `HOLD`。
- 已新增 `ACTION_POLICY_VERSION=EVIDENCE_GATE_V1`，Response／快照／JSON／Excel metadata
  均顯示該版本；舊快照缺欄維持 null，不由 ruleVersion 推導。Liquibase
  `v1.101.0-radar-notification-action-policy-version.sql` 只新增 nullable
  `action_policy_version VARCHAR(40)`，無 default／backfill／其他 pending 欄位。通知舊列、
  舊 policy 與 `initialized=false` 的首輪均只重建 baseline，不 enqueue、不寫 cooldown；
  integration test 使用真實 `TradingRadarNotificationTransition`：首輪建立 HOLD baseline、
  下一輪仍 HOLD 時零通知，再真實轉入 `EXIT_CANDIDATE` 才通知並寫 cooldown。
- 316.6 的 request/report 契約已實作：`codes` 缺欄／null／空陣列為
  `FULL_MARKET`；非空陣列為 `BOUNDED_DIAGNOSTIC`，並在建 production registry 前
  fail closed。bounded report 的 `productionPromoted=false`、`promotedCandidateCount=0`、
  頂層 selected maps 為空，每列為 `INSUFFICIENT_DIAGNOSTIC_ONLY`，且不建立可供
  production resolver 解析的 registry；JSON／CSV 均 echo `universeMode`。

### 本地 full regression

- backend（含 ByteBuddy flag）：849 tests，0 failures，0 errors，0 skipped。
- external-materials-service：規格列出的原命令在 Java 25 因 Byte Buddy 1.15.11 只官方
  支援到 Java 24，311 tests 中 203 個 Mockito instrumentation errors；這是環境／測試
  runtime 錯誤，不是本變更的 assertion failure。以同 backend 相容旗標
  `-DextraArgLine=-Dnet.bytebuddy.experimental=true` 重跑後：311 tests，0 failures，
  0 errors，0 skipped。
- BFF：54 tests，0 failures，0 errors，0 skipped。
- frontend：8 tests 全過；production build 完成。此 worktree 原先無 `node_modules`，
  首次 build 因 `vite: command not found` 無法啟動；執行 `npm ci` 後以同一 build 命令
  成功，未改 package manifests。
- focused 契約另覆蓋 typed calendar/wiring、Treasury evidence matrix、final-action gate、
  notification baseline/cooldown、page/notification bit-identical、snapshot/export backward compatibility、
  V13 universe mode/zero-map/no-registry invariant。

### 尚未完成，不得宣稱為 runtime／發布證據

- 架構審查 Round 1 的 2 Major／1 Minor 已修正，尚待 fresh Round 2 複審；
  本報告只是 implementation 與本地測試證據。
- 316.4 Docker image rebuild／container recreate，image SHA／start time／Compose project，BFF restart／
  health／log 與容器產物證據尚未執行。
- 316.5 已登入頁面、台美 API、通知同 snapshot、Treasury／valuation provenance、
  JSON/XLSX 一致與 browser console 的實機抽查尚未執行。
- 316.6 真實 DB 只讀 preflight、固定 from/to、request SHA-256 與完整市場
  70/30＋5-fold holdout 尚未執行；因此目前沒有可回填的真實期間、逐
  production key 證據或 FULL_MARKET 結論。
- 316.7 尚不能作 `REJECTED`／`INSUFFICIENT`／`PROMOTED_CANDIDATE` 發布
  分類。production 仍明確保留 `TW_RULES_V12`，`productionPromoted=false`；在 runtime
  與真實 FULL_MARKET holdout 證據完整前，保留 V12 是 fail-closed gate 的正確結果。

### Runtime／holdout 固定前置（2026-08-12）

- **Docker runtime：** 由乾淨 feature worktree `2b4abe5f`（包含 `origin/main`
  `49f6f065`）重建並 force-recreate `business-services`、`bff`、`frontend`；Postgres、Redis
  與 external-materials-service 未重建。BFF final recreate 後 health 為 `UP`、frontend HTTP
  200；最終 image/tag／start time 與 BFF logs 仍待本次 holdout 和全部 runtime 檢查完成後一併回填。
- **schema／session runtime：** Liquibase `v1.101.0-radar-notification-action-policy-version`
  已執行；`trading_radar_notification_setting.action_policy_version` 為 nullable，既有 21 列
  均為 null。已登入 browser 的 `/trading-radar` 與同 session
  `GET /api/bff/trading-radar` 都回 200；回應涵蓋台／美股，`ruleVersion=TW_RULES_V12`、
  `actionPolicyVersion=EVIDENCE_GATE_V1`。同一回應抽到逐 component valuation provider／as-of／URL
  與台灣掛牌、 美股債券的 `US_TREASURY` batch 153、curve date `2026-08-11`、riskUnit null。
- **316.6 immutable preflight（送出前）：** 只讀 `stock_price_history`：台股範圍
  `2016-08-12..2026-08-12`、52 codes、106219 rows；美股範圍
  `2016-08-12..2026-08-11`、17 codes、40553 rows。依 market min/max 交集固定
  `from=2016-08-12`、`to=2026-08-11`、`universe=FULL_MARKET`；逐 code 覆蓋統計已查核（台股
  8..2438 rows、美股 1339..2512 rows），不得在看結果後移動期間或縮小 universe。
- **316.6 exact request（送出前）：**
  ```json
  {"from":"2016-08-12","to":"2026-08-11","markets":["台股","美股"],"horizons":[5,20,60,120],"calibrationRatio":0.70,"walkForwardFolds":5,"includeCloseFallbackSensitivity":false}
  ```
  Canonical UTF-8 request SHA-256：`fa32ea5a1d2517c28ed6759836c9076d55a401719a7ea23a6502666188ca8fa4`。

### 真實 FULL_MARKET 執行結論（2026-08-12）

- 上述 exact request 已由 business-services container 送至 `POST /internal/backtest/rules`，`codes` 確實省略；
  無改動 fixed from/to、markets、horizons、calibration ratio、folds、universe 或任何 gate。
- 以預先採用的 10 分鐘 bounded window 執行至 10 分 15 秒仍沒有 HTTP response／report，期間 container 保持
  healthy、約 2.94 GiB／7.65 GiB memory、約 2–2.6 CPU cores，未見 OOM、exception 或 completed report。
  為維持 bounded execution，只終止等待 response 的 HTTP client；未重送、未縮小 universe，亦未把任何 bounded
  diagnostic 當 promotion 證據。
- **分類：`INSUFFICIENT`（FULL_MARKET execution timeout）。** 因沒有 completed report，無從誠實填列各
  production key 的 required horizons、holdout n/codes、valid/passing folds、catastrophic fold、candidate ID、
  parameter snapshot、joint train/sigma/purge 或 status/reason；這些不是零或通過。`RULE_VERSION` 保持
  `TW_RULES_V12`，不建立／不寫入 production registry，不作 V13 promotion。

### Final runtime integrity（2026-08-12）

- final running image/tag provenance 均相符：business-services
  `sha256:e4d5f5bbd38f3ae17d04e317bff3b37032094bc7c018e49cfa8755ab6f6c12ef`
  （started `2026-08-12T12:37:47Z`）、BFF
  `sha256:cedb9eb01b4fdf3d0b5bfb52dfb085e7b6e669e35f8a9326bd5d00dec6641763`
  （started `2026-08-12T12:37:51Z`）、frontend
  `sha256:a06a3f64fbc455ec28390bec14e9b59dce46fb51789feea1abc08f80b4fdacf1`
  （started `2026-08-12T12:18:05Z`）。Compose project 為 `asset-management`；最終 BFF health `UP`、
  frontend HTTP 200，BFF 自 final start 後 `Connection refused|500 Server Error` count 為 0。
- business JAR、BFF JAR 與 frontend bundle 均驗到本輪新增 class／literal；`git diff --check` 通過，
  `scripts/spec-check.sh` 為 `BLOCK: 0 / CHECK: 0`。本輪只變更 t314／t315／t316／t317 完成報告，沒有 commit、
  merge、push 或 production rule-version change。
- **仍未完成／限制：** fresh runtime JSON/XLSX 18-column artifact 未能取得（browser manual-export download
  timeout，既有排程檔為 rebuild 前）；不安全地 live 注入 malformed downstream payload，故 Task 317 malformed
  502 僅有契約測試而無 runtime 人為故障證據。FULL_MARKET HTTP client timeout 後 server-side calculation 未立即
  回收，故以**同一 image** recreate business-services 並 final-recreate BFF 結束 bounded run；之後 service
  health 正常、約 1.07 GiB memory、低 CPU。沒有 completed report，不得事後補作本輪 promotion 證據。
