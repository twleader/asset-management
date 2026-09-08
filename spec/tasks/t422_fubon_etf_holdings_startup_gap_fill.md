# [t422] business-services 啟動後只補齊尚未成功保存的富邦 ETF 成分股

**對應 Requirements:** Requirement 144（富邦 ETF 成分股在 ApplicationReady 補齊 table 缺口，讓 Dashboard 恢復股票型 ETF 穿透的前 10 大＋「其它」）
**前置任務:** t389（富邦 ETF 查詢、正規化與排程）、t390（台股 ETF 本地 pure-read）、t412（Dashboard 降級註記）
**Liquibase changeset:** 無

## 背景

既有 business-services 只有兩個台北時區固定排程：交易日 08:50 與 15:30。2026-09-08 22:49 重建容器時，當日兩個時段都已錯過；2026-09-09 00:00 實查即使
`FUBON_ETF_HOLDINGS_SYNC_ENABLED=true`，`fubon_etf_holdings_snapshot` 仍為 0 筆。Dashboard 的
`GET /api/bff/dashboard/tw-stock-lookthrough/{snapshotId}` 只會 pure-read 本地資料，因此所有 ETF 都退回
以整檔 ETF 計入，畫面看不到應有的成分股穿透。

同輪以既有唯讀 `POST /internal/market-data/etf-holdings` 查交易雷達範圍內 18 檔台股 ETF，18 檔皆回
`SUCCESS`；股票型 ETF 各有 29–113 筆成分股，債券型 ETF 為合法空集合。這證明 provider 與正規化契約
可用，根因是服務 lifecycle 沒有補錯過排程的空窗。本輪人工救援是直接呼叫 adapter 取得 normalized
payload，再依既有 table schema 用 SQL transaction/upsert 補入 18 筆，**未經 Java
`FubonEtfHoldingsSyncService`／`FubonEtfHoldingsWriter`**，僅恢復當下資料；不得把這 18 個代碼寫死或當
seed，也不得把人工 SQL 當成正式管線驗收。永久修正必能處理日後雷達集合新增、沒有 successful row 或只有
failure row 的 ETF，且正式保存必走 `SyncService → FubonBrokerClient → parser → FubonEtfHoldingsWriter`。

正確行為是：每個 business-services application lifecycle 在 `ApplicationReadyEvent` 後嘗試一次缺口補齊；
保留 ETF feature flag 與 Fubon READY gate，沿用既有雷達 ETF 集合、batch、parser、client 與逐檔 writer，
但只查 table 尚無 successful row 的 code。啟動補缺刻意不套「今天必須是交易日」gate，因 adapter 已保證
`sourceDate` 不晚於台北今日，可在週末、休市日或錯過排程後取得最近有效來源。既有 08:50／15:30 全量
排程的 cron、時區與交易日 gate 完全不變。

## 要做什麼

- [ ] **422.1 Repository 提供一次 successful IDs 批次 projection。** 在
  `FubonEtfHoldingsSnapshotRepository` 新增 collection input、限定 `success=true` 的查詢方法，只回候選
  集合中已有成功結果的 `etfStockCode`，以一個 `IN (:codes)`（或等價單次 collection query）完成。回傳
  型別使用 `Set<String>`
  或其他能正確求差集的集合；Service 只在 candidates 非空時呼叫。禁止對每個 code 呼叫
  `existsById`／`findById`，禁止 N+1，也不要載入完整 Entity／JSONB 只為判斷 ID 是否存在。Repository
  查詢例外須 fail closed：固定消毒後 log、零 provider 呼叫、零 mutation。

- [ ] **422.2 同步 Service 新增明確的 startup missing-only 入口，scheduled full 入口不變。** 保留
  `syncScheduled()` 的既有順序：ETF flag → Fubon READY → `isTwTradingDayKnown(today)==true` →
  `collectTwRadarEtfCodes()` → 全量同步。另新增例如 `syncMissingOnStartup()` 的入口，順序固定為：
  ETF flag → Fubon READY → `collectTwRadarEtfCodes()` → Repository 一次讀 successful IDs →
  `candidates - successfulIds` → 只同步 missing/retry list。startup 入口完全不得呼叫交易日曆；disabled 時不得讀
  config/calendar/radar/snapshot repository，not-ready 時不得讀 calendar/radar/snapshot repository。
  candidates 為空時不得查 successful IDs，missing 為空時不得呼叫 provider 或 writer。

- [ ] **422.3 startup 只把 successful row 視為已覆蓋。** `success=true`＋非空 holdings 與
  `success=true`＋合法空集合都略過，且不得修改其 `raw_response_json`、`reason`、`fetched_at`、
  `updated_at`。`success=false` failure row 不算覆蓋；FULL failure 後若 STARTUP 已 pending，可立即再試，
  日後兩個正常 FULL cron 也可繼續重試。once guard 只限制 STARTUP source；bounded coalescing 只合併尚未
  消費的 burst intent。retry 的 provider failure 仍依既有規則更新 typed failure row，一檔不得連坐其他
  candidate；DB 保存失敗同樣留待已 pending 或日後合法 trigger。固定 08:50／15:30 FULL 是既有授權的
  獨立全量 intent，不受 application lifecycle 一次上限。

- [ ] **422.4 missing list 完整沿用既有同步管線。** 候選集合仍是「各 owner 最新快照台股持股 ∪ 台股
  警示」，排除 `0000`、格式不合法 code，再以 `MarketDataService.isEtf(code,"台股")` 篩選；不得改成
  全市場、configured-admin-only、單一 Dashboard snapshot 或寫死的 18 檔。只把 selection policy 抽開；
  後續仍使用既有每 batch 4 檔（internal route 仍允許 1–50）、`FubonBrokerClient.readEtfHoldings`、
  batch/code/status/payload 驗證、`FubonEtfHoldingsParser` 版本 1 正規化、reason 消毒、
  `FubonEtfHoldingsWriter.save` 的逐檔 `REQUIRES_NEW` 保存。不得新增第二套
  WebClient、parser、DTO 或 writer；整批/單檔 failure、額外/缺少/重複 code 與單筆 DB failure 隔離語意不變。
  使用者僅在效能證據需要時條件允許 shared SDK slots 5→6；目前實測 Python ETF
  `MAX_CONCURRENCY=4`、18 檔約 1 秒全成功，未證明 5 slots 不足。因此本任務不修改 backend batch 4、
  Python ETF concurrency 4 或 global `SdkGateway.MAX_BLOCKING_CALLS=5`；這是明確範圍排除，不是遺漏。

- [ ] **422.5 ApplicationReady listener exactly-once、非阻塞且只開一條背景 thread。** 在
  `FubonEtfHoldingsSyncScheduler` 監聽 `ApplicationReadyEvent`；process-local once guard 成功後只建立一條
  具名 daemon／virtual background thread，把 STARTUP intent 交給共用 runner，listener 必立即返回，不得
  等待 provider、DB 或測試 latch。不得建立 executor pool、無界 executor queue或每個事件一條 thread；
  重複 ready event 不得再建立 thread、pending intent 或呼叫 Service。背景例外只記消毒後診斷，不得使
  application 啟動失敗；once guard 不因例外重置。這些 guard 只符合 Compose 單 replica 現況，不宣稱為
  跨 replica distributed lock。

- [ ] **422.6 startup 與 cron 共用 O(1) 有界 coalescing single-flight，碰撞不得永久遺失 intent。** Scheduler
  只保存 `pendingStartup`、`pendingFull` 兩個 boolean 與一個 running flag／lock；尚未消費的同類 burst 只合併成
  一個 pending intent，禁止 Runnable/request queue，也不得新增 `attemptedBeforeStartup` 或任何 per-code
  attempted 集合。取得 runner 者以迴圈 drain，永遠先取 FULL、再取 STARTUP；取走時清除該 bit，action
  執行期間新合法 trigger 可再次設 bit，後續必再執行。兩者皆空才原子釋放 running，所以 state 恆 O(1)，
  但不承諾 application lifecycle 每類 intent 只執行一次。STARTUP source 由 ready once guard 限一次；FULL
  仍有每天 08:50／15:30 兩個 cron。FULL 後 pending STARTUP 必執行：全部成功時 STARTUP 零 provider，
  部分 provider／writer failure 可由 STARTUP 立即 retry，closed／unknown FULL no-op 後 STARTUP 仍補缺。
  STARTUP 執行中收到 FULL，之後必接著執行 FULL。failure 不得自我重新 submit；日後正常 cron 仍可重試。
  任一 action 例外須被隔離並繼續 drain 已存在的 intent；release race 不得漏掉臨界點新 trigger。

- [ ] **422.7 provider 結果與來源日不變。** missing/retry code 成功時保存版本 1 正規化成分股與 provider
  `sourceDate`；合法 `data: []` 仍是 `success=true`、`sourceDate=null`、空 holdings 的可區分結果，不是
  provider failure，也不得在 restart 再抓。provider sourceDate 不晚於 `LocalDate.now(Asia/Taipei)` 的既有
  parser gate 保留。單檔/整批 failure 仍保存固定 typed reason 且不得連坐其他 missing code；不得以人工
  清單、人工 SQL payload、舊 payload、Dashboard fallback、目前日期或 fetched time 偽造正式 pipeline 的
  sourceDate／成功資料；若另一種 intent 已 pending，failure code 可立即再試，日後正常 FULL 也可重試；
  failure 本身不得自我排程循環。

- [ ] **422.8 兩個既有 BFF 資訊 consumer 都更新文案，不新增清單項目。** 更新
  `SchedulePublicBffController.JOBS` 中唯一一筆「富邦 ETF 成分股持股同步」description，以及
  `FubonApiInfoBffController` 的既有 `ownership.etf_holdings` consumer，兩處都明確寫出「交易日
  08:50／15:30 全量同步；ApplicationReady missing-only／failure retry」。Schedule job 的 name、category、
  service、`schedule="交易日 08:50、15:30"`、兩個 cron、Asia/Taipei、business job 數 28、全系統 job 數
  66 都不變；Fubon API inventory 的 item count、connected count 與分類統計都不變。ApplicationReady
  lifecycle event 不是 `@Scheduled` job，不新增清單列或 API item。

- [ ] **422.9 Dashboard request path 與聚合演算法不改，只驗證資料補齊後恢復既有契約。** 不修改
  `GET /api/bff/dashboard/tw-stock-lookthrough/{snapshotId}` response shape，不在 BFF/request path 呼叫
  Fubon。既有演算法仍須把股票型台股 ETF 依成分權重正規化分配整筆 currentValue，以股名合併直接持股
  與不同 ETF 的同名成分，value 降冪取前 10 筆，所有尾段只放一個 `others`；成功穿透的股票型 ETF 自身
  不得殘留在 items。債券型 ETF 的合法空集合沒有股票可拆，維持既有 degraded fallback 與顯示註記，
  不得被標成 provider failure。ETF 配息仍以整檔基金資料為準，不從成分股推算或重複計算。

- [ ] **422.10 Compose 冷啟動等待 Fubon adapter process healthy，但不把功能 READY 當啟動條件。**
  `docker-compose.yml` 的 `business-services.depends_on` 新增
  `fubon-broker-service: condition: service_healthy`。現有 `/internal/health` 與 healthcheck 只驗 process
  liveness `status=UP`；既有測試已證明 Fubon disabled／MISCONFIGURED 時仍回 200／UP，因此 optional
  Fubon 設定不會阻止 business 啟動。功能是否可查仍由 Java `FubonConfigState.READY` fail closed；不得把
  health 改成 SDK login、credential READY 或真人 provider 成功探測。Compose dependency 以 static contract
  test 驗證；disabled／MISCONFIGURED health 直接跑既有精確 pytest，不因固定 `container_name` 另建兩套
  隔離 Compose runtime。正式 stack 只驗目前 `.env` 的當前 config。

- [ ] **422.11 不擴張公開面或金融權限。** 不新增 endpoint、controller、manual run-now、BFF/public route、
  9090/Tailscale route、OpenAPI operation、host port 或 DB schema；不修改 `.env`、secrets、Fubon master／
  ETF feature flag 預設或帳務同步。不新增或呼叫下單、改單、撤單、圈存、匯款或任何券商寫入能力。
  startup 唯一新增的外部副作用是既有授權範圍內、missing-only 的 ETF 成分股唯讀查詢及本地 table 保存。

- [ ] **422.12 單元與真實 PostgreSQL 回歸。** 擴充 `FubonEtfHoldingsSyncServiceTest`，至少包含：
  disabled、DISABLED/MISCONFIGURED、empty candidates、all-successful、failure-row retry、missing-only、
  successful-ID repository failure、provider failure、合法空集合；mixed fixture 必同時有「已有成功、已有
  合法空集合、已有 failure、完全無 row」，斷言 successful lookup 恰好一次、broker 只收到 failure 與無 row
  兩類、兩種 successful row 零 writer interaction。擴充 `FubonEtfHoldingsSyncSchedulerTest`，用 deterministic
  latch 證明 listener 在 provider latch 未釋放時已返回、重複 ready event 只開一條 thread／一個 STARTUP
  source、FULL winner 後 STARTUP、STARTUP winner 後 FULL；FULL winner 再分 known-open success、closed、
  unknown。另以 burst 與 release-race latch 證明尚未消費的同類 trigger 只保留一個 bit、bit 取走後新 trigger
  可再次設 pending且不 lost wakeup、state 恆 O(1)；partial/provider/writer failure 不自我排程循環，而後續
  合法 FULL cron 仍可執行。保留兩個 cron annotation 精確值。擴充
  `FubonEtfHoldingsPostgresTest`，用真實 PostgreSQL 驗 successful-code collection projection、差集查詢
  failure 與無 row、successful rows 的 payload/reason/timestamps 不變、failure row 下一 lifecycle 可重試，
  以及合法空集合 JSONB round-trip 可被 pure-read parser 辨識；不可只以 mock 或 H2 取代。

- [ ] **422.13 兩個 BFF 資訊 consumer、Compose 與 runtime 驗收。** 擴充
  `SchedulePublicBffControllerTest`：總數仍為 66、
  business 仍 28、ETF job 恰一筆，cron/schedule/zone 不變，description 同時含全量固定排程與 startup
  successful-missing retry；擴充 `FubonApiInfoBffControllerTest` 驗 `ownership.etf_holdings` consumer 同步
  新文案，且 API item/connected/category counts 不變。Compose static contract test 證明 business 等待
  Fubon healthy；透過 Fubon Dockerfile `test` stage 執行兩個既有精確 pytest，分別證明 disabled／
  MISCONFIGURED health 仍為 UP，不用 host pytest、不啟動兩套隔離 Compose runtime。Docker 驗收只跑
  目前 `.env` 的正式 stack，仍分兩階段：合併前從 feature
  worktree build/recreate Fubon、business、bff 並完成 DB／Dashboard 驗收；成功 commit＋
  `git merge --no-ff` 後，先確認 main 已同步合併 commit，再從 main 目錄與 main `.env` build/recreate 相同
  服務並重驗。任何階段 `.env` 與 build source 不符即停。讀回 `fubon_etf_holdings_snapshot` 的 code、
  success、sourceDate、holdings 筆數與 `updated_at`；restart 前後 successful rows 的 `updated_at` 必相同，
  雷達 ETF − successful IDs 差集若只剩 failure rows，須以可控測試證明 pending intent 或後續合法 FULL
  可以重試、failure 不自我排程，不能誤報全覆蓋。
  以已登入 session 呼叫 Dashboard Taiwan lookthrough API，驗 items 最多 10、只有一個 others 且總額守恆、
  股票型 ETF 不殘留在 items、合法空集合債券 ETF 只列在 degraded 語意。人工補入的 18 筆只可作
  all-successful runtime case，不能取代 missing-only/provider-failure 或 Java writer pipeline 的自動化測試。

## 驗證

先執行機械規格檢查與受影響模組測試；所有命令保留非零退出碼：

```bash
bash scripts/spec-check.sh
bash scripts/tests/fubon-etf-holdings-compose-contract-test.sh
docker buildx build --platform linux/amd64 --target test \
  -f fubon-broker-service/Dockerfile fubon-broker-service \
  --load -t asset-management-t422-fubon-test:local
docker run --rm asset-management-t422-fubon-test:local pytest -q \
  tests/test_app_routes.py::test_disabled_health_is_up_without_reading_sdk_or_secrets \
  tests/test_app_routes.py::test_enabled_missing_shared_token_is_healthy_but_functionally_misconfigured
JAVA_HOME=$(/usr/libexec/java_home -v 21) \
  PATH="$(/usr/libexec/java_home -v 21)/bin:$PATH" \
  /usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml \
  -Dtest=FubonEtfHoldingsSyncServiceTest,FubonEtfHoldingsSyncSchedulerTest,FubonEtfHoldingsPostgresTest test
JAVA_HOME=$(/usr/libexec/java_home -v 21) \
  PATH="$(/usr/libexec/java_home -v 21)/bin:$PATH" \
  /usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f bff/pom.xml \
  -Dtest=SchedulePublicBffControllerTest,FubonApiInfoBffControllerTest test
JAVA_HOME=$(/usr/libexec/java_home -v 21) \
  PATH="$(/usr/libexec/java_home -v 21)/bin:$PATH" \
  /usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test
JAVA_HOME=$(/usr/libexec/java_home -v 21) \
  PATH="$(/usr/libexec/java_home -v 21)/bin:$PATH" \
  /usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f bff/pom.xml test
```

`fubon-etf-holdings-compose-contract-test.sh` 是本任務新增的純靜態 YAML contract test；它不得啟動容器，且須
精確斷言 `business-services.depends_on.fubon-broker-service.condition == service_healthy`。已核實
`fubon-broker-service/Dockerfile` 的 stage 名稱是 `test`，其工作目錄 `/app` 含 `tests/` 且安裝
`requirements-test.txt`；上述 `docker run ... pytest` 使用既有 fake config／TestClient，不呼叫真實 SDK，
也不依賴 host pytest 或建立會與固定 `container_name` 衝突的 Compose project。

依專案 `/run-stack` 流程做兩階段部署驗收，兩次都只使用目前 `.env` 的正式 stack 與當前 config，不為
disabled／MISCONFIGURED 另起 runtime。第一階段在 feature worktree 核對 build source 與 `.env` 後，
重建/recreate Fubon、business、bff；不可用 `--no-deps` 跳過本任務新增的 Fubon healthy dependency：

```bash
docker compose -p asset-management build --no-cache fubon-broker-service business-services bff
docker compose -p asset-management up -d --force-recreate fubon-broker-service business-services bff
docker inspect -f '{{.State.Health.Status}}' asset-fubon-broker-service asset-business-services asset-bff
```

第一階段 DB／Dashboard 驗收通過後才 commit 並以 `git merge --no-ff` 合併。第二階段須先確認 main 已同步
該 merge commit，再從 main 目錄與 main `.env` 重跑上列 build/recreate、health、DB 與 Dashboard 驗收；
不得沿用 feature image/tag 結果充當 main 驗收證據。

兩階段重啟前後各保存一次下列唯讀結果，確認 successful rows 不被 startup 改寫；成功 payload 的 holdings 筆數與
合法空集合可清楚區分：

```bash
docker exec asset-postgres psql -U assets -d assets -P pager=off -c "
SELECT etf_stock_code,
       success,
       raw_response_json->>'sourceDate' AS source_date,
       CASE WHEN jsonb_typeof(raw_response_json->'holdings') = 'array'
            THEN jsonb_array_length(raw_response_json->'holdings') END AS holding_count,
       reason,
       updated_at
FROM fubon_etf_holdings_snapshot
ORDER BY etf_stock_code;"

docker exec asset-postgres psql -U assets -d assets -P pager=off -c "
WITH radar AS (
  SELECT stock_code FROM stock_holding
   WHERE market='台股'
     AND snapshot_id IN (
       SELECT DISTINCT ON (owner_user_id) id FROM asset_snapshot
       ORDER BY owner_user_id, snapshot_date DESC, id DESC)
  UNION
  SELECT stock_code FROM stock_alert WHERE market='台股'
)
SELECT r.stock_code AS missing_etf_code
FROM radar r
LEFT JOIN fubon_etf_holdings_snapshot e
  ON e.etf_stock_code=r.stock_code AND e.success=true
WHERE r.stock_code ~ '^00[0-9]{2,3}([0-9A-Z])?$'
  AND r.stock_code<>'0000'
  AND e.etf_stock_code IS NULL
ORDER BY r.stock_code;"
```

此差集會刻意列出 failure row，因它在下一個 lifecycle 是 retry candidate；SQL regex 必與
`validEtfCode` 等價，不得退回寬鬆 `LIKE '00%'`。是否真為 ETF 仍以正式 Service 既有 `isEtf` 判定為準。

最後以已登入瀏覽器 session 取得某個含台股 ETF 快照的 ID，再讀 Dashboard API。不得為了驗收刪除正式
ETF rows、改 flag 或人工造成功 payload；missing-only 已由自動化與 Testcontainers 證明：

```bash
export ASSET_SNAPSHOT_ID='<含台股 ETF 的快照 id>'
export ASSET_SESSION_COOKIE='<已登入 session 的完整 Cookie header value>'
curl -fsS -H "Cookie: ${ASSET_SESSION_COOKIE}" \
  "https://localhost/api/bff/dashboard/tw-stock-lookthrough/${ASSET_SNAPSHOT_ID}" \
  | tee /tmp/t422-tw-stock-lookthrough.json
jq '{snapshotDate,totalTwStockValue,itemCount:(.items|length),items,others,degradedEtfs}' \
  /tmp/t422-tw-stock-lookthrough.json
```

人工核對 `itemCount <= 10`、`others` 唯一、`sum(items[].value)+others.value` 與
`totalTwStockValue` 僅有逐項四捨五入容許差；有非空 holdings 的股票型 ETF 自身不在 items，合法空集合的
債券 ETF 只出現在 degraded 語意。瀏覽器 Dashboard 的「台股個股」tab 應顯示合併後前 10 大＋「其它」。

## 完成報告

（實作者做完後回填：實際修改檔案、測試數量與輸出、ApplicationReady／single-flight／PostgreSQL／Docker／Dashboard readback 證據，以及任何與本任務規格的偏差及原因。）
