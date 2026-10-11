# [t490] API logs API 名稱加入「全部」並確認 30 天保留

**對應 Requirements:** Requirement 188（管理者可明確選擇所有 API 名稱，且 API error log 固定只保留 30 天）
**前置任務:** 無
**Liquibase changeset:** `v1.152.0-api-error-log-retention-insert-guard.sql`

## 背景

API logs 查詢已有來源、時間排序、API 名稱三個下拉選單；第三個選單只有動態 catalog 選項與可清除 placeholder，沒有能明確選取的「全部」。既有前端以 `operationKey=null` 表示沒有 API 名稱限制，BFF 因此不傳送 `operationKey`，business query 讀取所選來源的所有紀錄。這次要讓同一語意在畫面上可見，不能以 `ALL` 或任何假 operation key 改變既有 API 契約。

使用者同時要求 API logs 只保留 30 天。2026-10-11 的執行中 PostgreSQL 在資料庫時間 16:48+08 有 8 筆嚴格超過 30 天的資料（最舊 2026-09-07），雖然 retention guard 與每日 03:15 的 scheduler 都已存在。原因一開始看似服務在每日時點後重啟時，cron-only 設計不會立刻補刪 backlog；但第二次實際 rebuild 證明 startup delete 回傳 `deleted=8` 後，BFF `NginxGatewayFailureLogTailer` 會重掃 immutable error.log 並重寫同八筆（新 row ID 4017281–4017317）。因此單靠 cleanup 不足，寫入面必須同樣禁止已過期事件。本任務以 Spring Boot 的明確 runner 階段補清，並由 DB trigger 做 30 天寫入不變量、BFF tailer 做減載預篩；不提供任何對使用者的手動刪除能力。

## 要做什麼

- [ ] 490.1 在 `frontend/src/views/ApiErrorLogsView.vue` 的第三個 `el-select` 新增 `aria-label="API 名稱"`、精確 `:value-on-clear="null"` 與 `@clear="restoreAllOperationFilter"`，並把唯一的 `<el-option label="全部" :value="ALL_OPERATION_FILTER"/>` 放在既有 `v-for` catalog options 之前。保留 `clearable`、`placeholder="單一 API"`、既有 1:2 grid、`@change="load"` 和所有 catalog option 的中文名稱加 method/path 顯示；不得改動前兩個選單。
- [ ] 490.2 API 名稱畫面 state 的初始、source-change reset 及「全部」選擇是 local-only `ALL_OPERATION_FILTER='ALL'`；這避免 Element Plus 把 `null` 當 empty value 而隱藏「全部」。清除時元件合法地暫設 `null`，`@clear` 必須立即恢復 sentinel，不可將非-empty sentinel 作為 `value-on-clear`。`load()` 必把此 sentinel 映射成 `operationKey=null` 才交給既有 `bffApi.apiErrorLogs.list`，使 request 只送 `source` 與 `sort`、絕不送 `operationKey`；不得送 `ALL`、空字串或新的特殊值。選「全部」或清除只讀取 list；切換來源仍先重設這個 UI state，再讀 operations catalog，最後讀 list；排序或 API 名稱選取不得重讀 catalog。
- [ ] 490.3 不新增或修改 BFF/business endpoint、controller、security、catalog、排程登錄、9090 gateway、Tailscale、OpenAPI、券商／Fubon SDK 或任何外部 I/O。畫面繼續只能經 `bffApi.apiErrorLogs` 讀取既有 BFF list／operations／detail；不得呼叫 catalog 顯示的任何 API。唯一例外是本任務列明的 existing `NginxGatewayFailureLogTailer` 預篩與 retention trigger migration；兩者都不得擴大 ingress route 或安全身份。
- [ ] 490.4 新增 `backend/src/main/java/com/steven/assets/apierrorlog/ApiErrorLogRetentionStartupRunner.java` 作為薄 `@Component`，並實作 Spring Boot `ApplicationRunner`。它只注入 managed `ApiErrorLogRetentionScheduler`，每次 `run` 呼叫 `deleteExpired()` 一次，讓 runner 階段的跨 bean 呼叫保留 `@Transactional` proxy；不得在 scheduler 類別內 self-call。scheduler 回傳 `OptionalInt`：成功時（含 0）為刪除數、已吞下的 repository failure 為 empty；runner 對有值 outcome 只能記錄精確 `API error log startup retention completed deleted=<non-negative integer>`，不能記錄任何 API log 內容或把 empty 表示成 0。runner 必須在 scheduler proxy call 外捕捉 `RuntimeException`，只記 exception class 的 warning，使 transaction begin／commit failure 不致阻斷服務啟動。保留既有 cron（`Asia/Taipei` 每日 03:15）與 repository SQL（DB `current_timestamp`，嚴格超過 30 天）；重啟補跑與 cron 共用完全相同的 delete，0 列刪除正常。
- [ ] 490.5 新增 `backend/src/main/resources/db/changelog/changes/v1.152.0-api-error-log-retention-insert-guard.sql` 並註冊 master：必有 `--liquibase formatted sql` 與唯一 `--changeset steven:v1.152.0-api-error-log-retention-insert-guard splitStatements:false`，讓整段 PL/pgSQL function 不會被分號切斷。以此新 changeset `CREATE OR REPLACE` `guard_api_error_log_retention`，使 `INSERT` 僅在 `NEW.occurred_at >= transaction_timestamp() - interval '30 days'` 通過、`UPDATE` 一律拒絕、`DELETE` 僅在 `OLD.occurred_at < transaction_timestamp() - interval '30 days'` 通過；drop/recreate `api_error_log_retention_guard` 為 `BEFORE INSERT OR UPDATE OR DELETE`。這是 30 天的 DB-time 寫入不變量，不能把 app clock 當權威，不得改表／欄位或新增手動 delete。套用 migration 後依 `db/schema.sql` 檔頭指引重產 schema-only 基準線。
- [ ] 490.6 修改 BFF `NginxGatewayFailureLogTailer`：Spring production constructor 保持既有三個依賴、以 `@Autowired` 明確選定，並以 `Clock.systemUTC()` 委派到 package-visible、額外接收 Clock 的測試 constructor；不得要求 BFF context 提供 Clock bean，也不得令 Spring 因兩個 constructor 而猜測。在 parse 完 `occurredAt` 後跳過嚴格早於 `clock.instant().minus(30 days)` 的行；精確 cutoff 行仍呼叫既有 ingest client。這是 preventing every-tick replay load，不是寫入權威，且不得新增 cursor、持久化狀態、route、外部 I/O 或改變 existing stable dedupe key。
- [ ] 490.7 更新 `frontend/src/utils/apiErrorLogsView.contract.test.js`，以 source 契約驗證 API 名稱 aria-label、精確 `:value-on-clear="null"`、`@clear` 恢復 local sentinel、唯一 `label="全部"`／`:value="ALL_OPERATION_FILTER"` option、其排列在動態 catalog options 前，以及 local sentinel 在送 request 前映射為 `null`，並保留 source reset、lazy detail cache 與禁止 `v-html` 的既有斷言。BFF test 必須反射驗證三依賴 production constructor 有 `@Autowired`，避免 package-visible Clock test constructor 令 Spring 啟動時無法選擇。新增 startup runner 的 retention unit test，以 mock scheduler 驗證每次 `ApplicationRunner.run` 恰好委派一次，並覆蓋成功 0、成功有刪除及 scheduler proxy 拋出 `RuntimeException` 時 runner 不拋；保留／擴充既有 scheduler test，驗證 repository failure 回 empty 並留下 retryable warning。真 PostgreSQL test 依 production 升級順序：先執行 v1.123 guard、建立過期 legacy backlog、再執行實際 v1.152.0 migration；隨後在同一 DB transaction 以 `current_timestamp` 建立精確 cutoff row 與呼叫 repository delete，驗證 backlog 刪除且 cutoff 保留；最後以三個各自獨立 transaction 驗證新過期 insert、任一 update、與未到期 delete 都被 trigger 拒絕。BFF tailer test 以 fixed Clock 驗證過期行無 ingest、精確 cutoff 行仍 ingress，並把既有 2026-09-07 positive fixture helper 固定為仍在 retention window，保留原有 502／504／stable dedupe coverage。測試不得連線任何 broker、Fubon SDK、外部 API；只有隔離 Testcontainers PostgreSQL 可以連線資料庫。
- [ ] 490.8 Docker 驗收前依 run-stack preflight 找出乾淨的 main worktree 與本 feature `TARGET`。不得輸出 `.env` 值或 secret：先確認 main `.env` 存在；若 TARGET 缺 `.env`，只可由 main 同步到這個隔離驗收 worktree；若 TARGET 已有 `.env`，先以排序後的內容比較，發現差異先確認原因，只同步已確認缺漏／過期鍵並保留其他既有設定。設定未確認相同不得 build。feature 尚未 merge 時從 TARGET build；merge 後只能從乾淨、已追平 `origin/main` 的 main worktree rebuild。不得將 feature 或主 clone `.env` 回寫 main。

## 驗證

```bash
/Users/steven/.nvm/versions/node/v22.21.0/bin/npm --prefix frontend test -- apiErrorLogsView.contract.test.js
/Users/steven/.nvm/versions/node/v22.21.0/bin/npm --prefix frontend run build
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test -Dtest='*ApiErrorLogRetention*,ApiErrorLogDedupeKeyPostgresTest' -DextraArgLine=-Dnet.bytebuddy.experimental=true
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f bff/pom.xml test -Dtest=NginxGatewayFailureLogTailerTest -DextraArgLine=-Dnet.bytebuddy.experimental=true
bash scripts/spec-check.sh
TARGET=$(git rev-parse --show-toplevel)
MAIN_WT=$(git worktree list --porcelain | awk '/^worktree / { path = substr($0, 10) } /^branch refs\/heads\/main$/ { print path; exit }')
test -n "$MAIN_WT" && test -f "$MAIN_WT/.env" && test -f "$TARGET/.env"
cmp -s <(sort "$TARGET/.env") <(sort "$MAIN_WT/.env")
(cd "$TARGET" && docker compose -p asset-management build bff business-services frontend)
(cd "$TARGET" && docker compose -p asset-management up -d --no-deps --force-recreate bff)
(cd "$TARGET" && docker compose -p asset-management up -d --no-deps --force-recreate business-services)
(cd "$TARGET" && docker compose -p asset-management up -d --no-deps --force-recreate frontend)
docker ps --filter name=asset-bff --format '{{.Names}}\t{{.Status}}'
docker ps --filter name=asset-business-services --format '{{.Names}}\t{{.Status}}'
docker ps --filter name=asset-frontend --format '{{.Names}}\t{{.Status}}'
docker logs asset-business-services --since 5m | grep 'API error log startup retention completed deleted='
docker exec asset-frontend sh -c 'grep -l "API 名稱" /usr/share/nginx/html/assets/*.js'
docker exec asset-postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Atc "SELECT count(*) FROM api_error_log WHERE occurred_at < current_timestamp - interval '\''30 days'\'';"'
bash scripts/tests/schema-sql-drift-test.sh
```

Docker 驗收時，BFF 與 business-services container 必為 healthy；frontend 沒有 healthcheck，必為 running 且通過 HTTP／asset 成品檢查。BFF 必先替換以停止舊 tailer replay，business-services 的 startup runner log 必須記錄清理筆數，最後 SQL 只可輸出 `0`，`db/schema.sql` drift test 必須通過。SQL 是唯讀檢查，不得以手動 delete 使結果成立。business-services healthy 後透過既有安全 GET 確認 BFF recovery。以既有管理者登入 `/api-error-logs` 時，第三個選單可明確選「全部」，選後讀取所有符合來源／排序的紀錄；前端 network 只可出現既有 BFF read request，且「全部」不得使 request 出現 `operationKey`。

## 完成報告

（實作者完成後回填：實際改動檔案、前端測試／build、frontend Docker recreate、read-only retention SQL 結果、以及與本計畫的偏差。）
