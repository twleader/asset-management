# [t421] 開放 API 錯誤日誌擴大擷取：呼叫端 4xx 與 Nginx gateway 無法連線的失敗

**對應 Requirements:** Requirement 143（管理者需要看見呼叫端 4xx 與 Nginx 無法連線至 BFF/business-services 這兩類目前被排除在「API logs 查詢」之外的失敗）
**前置任務:** Task 417（`api_error_log` 表、catalog、既有 producer 與讀取 API；本任務純新增，不改動其既有欄位、trigger 或 FUBON_API producer）、Task 420（`apiUrl` catalog metadata 與三個 `el-select` 版面，僅供參考，本任務不修改）
**Liquibase changeset:** v1.125.0-api-error-log-status-and-dedupe.sql

## 背景

今天早上實際發生兩類失敗，但「API logs 查詢」頁都沒有留下任何紀錄：

1. `09:25:46`（台灣時間）呼叫端對 `GET /api/public/market-index` 連續 5 次帶入 `market=NASDAQ/SP500/DOW/FTSE100/NIKKEI225`，`MarketIndexChartService.resolveMarket(...)` 全部丟出 `PublicMarketIndexRequestException` 並映射為 HTTP 400。Task 417 的 `PublicApiErrorCaptureWebFilter` 刻意「只有最後 response 為 5xx 才記錄」，4xx 一律 zero row——這是當初的正確設計（避免把呼叫端帶錯參數誤記為系統錯誤），但也代表管理者完全看不到這種呼叫模式。
2. `01:19` 與 `09:49`（台灣時間）`asset-bff`／`asset-business-services` 各自重建（`--force-recreate`）時，`asset-api-gateway` 既有的 `wget -qO- http://127.0.0.1:9090/api/public/market-index` healthcheck（`docker-compose.yml` 的 `api-gateway.healthcheck`，每 10 秒一次）剛好打中容器還沒起來的空窗，Nginx 直接回 502 `Connection refused`（`api-gateway/nginx.conf` 的 `proxy_next_upstream off`）。這個請求根本沒有進到 BFF 應用程式，`PublicApiErrorCaptureWebFilter` 是 BFF 內部的 `WebFilter`，架構上看不到這種失敗。

本任務不重新設計既有 catalog、資料表或既有 5 個 FUBON_API producer；只放寬「開放 API 何時該記一筆」，新增「Nginx 自己失敗時由誰來記」，並讓清單多顯示一個失敗當下就已知、原本沒保存的事實：HTTP 狀態碼。

## 要做什麼

- [x] 421.1 **新增 `http_status`／`dedupe_key` 欄位與去重唯一索引。** 新增
  `backend/src/main/resources/db/changelog/changes/v1.125.0-api-error-log-status-and-dedupe.sql`：

  ~~~sql
  --liquibase formatted sql

  --changeset codex:v1.125.0-api-error-log-status-and-dedupe splitStatements:false
  ALTER TABLE api_error_log ADD COLUMN IF NOT EXISTS http_status SMALLINT
      CHECK (http_status IS NULL OR (http_status BETWEEN 100 AND 599));
  ALTER TABLE api_error_log ADD COLUMN IF NOT EXISTS dedupe_key VARCHAR(64);
  CREATE UNIQUE INDEX IF NOT EXISTS uq_api_error_log_dedupe_key
      ON api_error_log (dedupe_key) WHERE dedupe_key IS NOT NULL;
  ~~~

  兩欄皆 nullable，不進 `api_error_log_operation`、不改既有 composite FK、不改既有 retention/immutable trigger。
  `http_status` 由 421.3／421.4 的兩個 OPEN_API producer 填入；FUBON_API 既有 5 個 producer（`FubonHttpClient`、
  `FubonNormalizedQuoteClient`、`FubonTaiexIndexStreamClient`、`FubonScheduledMarketClient`、
  `FubonStockPushStreamClient`／`ExternalApiErrorLogWriter`）**不修改**，繼續寫入 NULL。`dedupe_key` 只由 421.4
  新增的 gateway-log producer 填入，其餘 producer 一律傳 NULL。去重靠「INSERT 因唯一索引違反而失敗」，
  不得改用 UPDATE（既有 `guard_api_error_log_retention` trigger 已對 `api_error_log` 的所有 UPDATE 一律
  `RAISE EXCEPTION`，本任務不改這個 trigger）。在
  `backend/src/main/resources/db/changelog/db.changelog-master.yaml` 尾端新增本 changeset 的 include。
  依 `db/schema.sql` 檔頭「重新產生」段的三步驟從當下 `asset-postgres` 重產該檔，並跑
  `bash scripts/tests/schema-sql-drift-test.sh` 確認為 0。

- [x] 421.2 **Backend：entity／recorder／internal ingest／read model 都要能收發新欄位。**
  - `backend/src/main/java/com/steven/assets/apierrorlog/ApiErrorLog.java`：新增
    `@Column(name = "http_status") private Integer httpStatus;` 與
    `@Column(name = "dedupe_key") private String dedupeKey;`；建構子改為
    `public ApiErrorLog(String source, String operationKey, String apiName, String messageHeader, String stackTrace, Instant occurredAt, Integer httpStatus, String dedupeKey)`，
    新增 `getHttpStatus()`／`getDedupeKey()`。
  - `backend/src/main/java/com/steven/assets/apierrorlog/ApiErrorLogRecorder.java`：
    `record(String source, String operationKey, String apiName, String messageHeader, String stackTrace, Instant occurredAt)`
    改為新增 `Integer httpStatus, String dedupeKey` 兩個尾端參數，原樣傳入新建構子（訊息仍先經
    `renderer.sanitize(...)`，`httpStatus`/`dedupeKey` 不經 sanitize，因為它們不是自由文字）。
    既有 `record(Throwable throwable, String source, String operationKey, String apiName, Instant occurredAt)`
    這個 5 參數 overload **簽章不變**（唯一呼叫端是 `FubonHttpClient.java:249`，不得修改該呼叫），內部改為呼叫
    新的 8 參數版本並固定傳 `httpStatus=null, dedupeKey=null`。
  - `backend/src/main/java/com/steven/assets/apierrorlog/ApiErrorLogInternalController.java`：
    `FIELDS` 從 6 個 key 擴為 8 個：新增 `"httpStatus"`、`"dedupeKey"`。新增兩個私有 helper：
    一個要求欄位存在且為 JSON 整數（`httpStatus`，並驗證 100–599，否則 400），另一個允許欄位為 JSON
    `null` 或字串（`dedupeKey`）。`ingest(...)` 內對 `ApiErrorLogOperationCatalog.require(...)` 通過後改呼叫
    `recorder.record(source,key,name,header,trace,Instant.parse(text(body,"occurredAt")),httpStatus,dedupeKey)`。
  - `backend/src/main/java/com/steven/assets/apierrorlog/ApiErrorLogService.java`：`ListItem`／`Detail` 兩個
    record 各自新增 `Integer httpStatus` 欄位（置於 `messageHeader`／`stackTrace` 之後、`occurredAt` 之前），
    對應 `from(...)` 補上 `r.getHttpStatus()`；**不要**在 `ListItem`／`Detail` 加 `dedupeKey`——它純屬
    producer 去重機制，不對外曝露。

- [x] 421.3 **BFF：`PublicApiErrorCaptureWebFilter` 放寬為「有 Throwable 時 4xx 或 5xx 都記，沒有 Throwable
  時仍只有 5xx 才記」，並把真實 status 傳下去。**
  `finalStatus(...)` 目前的判斷式：

  ~~~java
  private void finalStatus(ServerWebExchange exchange, Operation op) {
      if (exchange.getResponse().getStatusCode() != null && exchange.getResponse().getStatusCode().is5xxServerError()) recordOnce(exchange, op);
      else exchange.getAttributes().remove(CAPTURE_ATTRIBUTE);
  }
  ~~~

  改為：status 為 5xx 時一律 `recordOnce`（含既有「沒有 Throwable 的 status-fallback」分支，行為不變，因為這個
  分支現在只可能落在 5xx）；status 為 4xx **且** `exchange.getAttributes().get(CAPTURE_ATTRIBUTE) != null`
  （代表確實有 advice/resolver 捕獲了一個 Throwable，不是 controller 主動、非例外地回應某個 4xx，例如未來若
  `POST /api/public/crawler-data/rescan` 的節流改用非例外回應）時才 `recordOnce`；其餘一律移除 attribute、
  zero row。2xx（含 `GET /api/quotes/one` 合約化的 204 cache miss）不受影響，維持既有邏輯不變——它們本來就不會
  滿足新的 4xx 分支條件，因為那條分支只判斷 `is4xxClientError()`。`recordOnce` 新增 `int httpStatus` 參數，
  沒有 Throwable 時原本寫死的 `"status=5xx"` synthetic message 改成用這個實際值（此時必為 5xx）：

  ~~~java
  private void recordOnce(ServerWebExchange exchange, Operation op, int httpStatus) {
      if (exchange.getAttributes().putIfAbsent(RECORDED_ATTRIBUTE, Boolean.TRUE) != null) return;
      Capture capture=(Capture) exchange.getAttributes().get(CAPTURE_ATTRIBUTE);
      Throwable failure=capture == null ? new IllegalStateException("Public API boundary failure operation="+op.key+" status="+httpStatus) : capture.throwable();
      Instant occurredAt=capture == null ? clock.instant() : capture.occurredAt();
      ingest.ingest(op.key,op.name,renderer.message(failure),renderer.render(failure),occurredAt,httpStatus,null);
  }
  ~~~

  `bff/src/main/java/com/steven/assets/bff/apierrorlogs/ApiErrorLogIngestClient.java` 的
  `ingest(...)` 新增 `int httpStatus` 參數，固定額外帶 `dedupeKey=null`（filter-driven capture 從不去重，
  每次都是新的一次性事件）。**注意現有實作用 `Map.of(...)` 組 request body；`Map.of` 對 null value 會丟
  `NullPointerException`，而 `dedupeKey` 這裡永遠是 null，必須改用可放 null value 的 `Map`（例如
  `LinkedHashMap` 逐一 `put`），否則會在執行期整條路徑爆炸。**

- [x] 421.4 **BFF：抽出共用 route catalog，新增讀取 Nginx 錯誤 log 的排程 producer。**
  新增 `bff/src/main/java/com/steven/assets/bff/apierrorlogs/OpenApiRouteCatalog.java`：把
  `PublicApiErrorCaptureWebFilter` 現有私有的 13 筆 `ROUTES` map 與 `Operation` record 原封不動搬到這個
  新類別（`public static final Map<String, Operation> ROUTES`、`public record Operation(String key, String name)`），
  `PublicApiErrorCaptureWebFilter` 改為引用 `OpenApiRouteCatalog.ROUTES`／`OpenApiRouteCatalog.Operation`，
  刪除自己原本的私有副本——兩處共用同一份，日後新增/修改路由不會漏改。

  新增 `bff/src/main/java/com/steven/assets/bff/apierrorlogs/NginxGatewayFailureLogTailer.java`：
  `@Component`，`@Scheduled(fixedDelayString = "${api-error-log.nginx-tail-interval-ms:15000}")`。
  讀取 `@Value("${api-error-log.nginx-error-log-path:/var/log/nginx-shared/error.log}")` 指定的檔案。
  每個 tick 都從檔案開頭完整重讀（不持久化 offset／不記憶跨重啟游標；正確性完全依賴 421.1 的
  `dedupe_key` 唯一索引——這正是 BFF 自己重啟時最需要捕捉的情境：nginx 在 BFF 離線期間寫入的行，必須在
  BFF 回來後的下一個 tick 被讀到，而非被一個「只看新增內容」的 cursor 錯過）。檔案不存在（例如本機開發
  未掛這個 volume）時整輪略過、只寫一則 debug log，不得拋出例外或讓 BFF 啟動失敗。

  逐行處理規則：
  - 只處理含 `[error]` 的行；含 `[warn]`／`[notice]`／`[info]` 的行一律略過（例如既有
    `an upstream response is buffered to a temporary file` 是 `[warn]`，必須被跳過，不是失敗）。
  - 從行首擷取 `yyyy/MM/dd HH:mm:ss` 時間戳，容器系統時區固定 UTC，轉換為 `Instant`（不可假設本機時區）。
  - 從 `request: "<METHOD> <PATH...>"` 片段取出 HTTP method 與 path；path 若含 `?` 只取問號前半、並去掉
    結尾的 ` HTTP/1.1"`。用取出的 `"<METHOD> <PATH>"` 字串查 `OpenApiRouteCatalog.ROUTES`；查無比對（不是
    13 條白名單之一，或整行根本沒有 `request:` 片段）就整行捨棄、不呼叫 ingest。
  - `httpStatus`：整行文字含 `timed out` 時為 `504`，其餘一律 `502`（這個 gateway 各 `location` 都是對
    單一動態 upstream 變數 `proxy_pass`，未使用 `upstream {}` 群組，`proxy_next_upstream` 在此不適用；
    502/504 是 nginx 對單一上游 connect/read 失敗的預設行為。不用去解析 access log 比對真實 status，
    這個規則已與今天實際觀測到的 6 次 502 完全一致）。
  - `messageHeader` 固定樣式：`"Nginx 無法連線至上游服務 operation=" + operationKey + " status=" + httpStatus`。
  - `stackTrace`：整行原始文字（trim 過），呼叫既有 `ApiErrorLogDiagnosticRenderer` 的既有 sanitize 手續走
    一次作第二道防線（雖然這種行只含 container-internal IP/port/hostname，不含 token/certificate/account）。
  - `dedupeKey`：該行（trim 後）原始文字的 SHA-256 hex digest（小寫，64 字元）。

  比對到白名單的每一行呼叫 `ApiErrorLogIngestClient.ingest(operationKey, apiName, messageHeader, stackTrace, occurredAt, httpStatus, dedupeKey)`
  （421.3 已將 `ingest(...)` 擴為可傳 `dedupeKey`；filter 呼叫端固定傳 null，本排程呼叫端固定傳實際 hash）。
  同一行內容被下一個 tick 重複掃到並再次送出時，business 端因 `dedupe_key` 唯一索引違反而讓
  `ApiErrorLogRecorder` 既有的 `catch (RuntimeException failure)` 吞掉，只留一筆可見 row——本排程與
  recorder 都不需要额外寫任何去重邏輯。

  **`NginxGatewayFailureLogTailer` 是 `bff` 服務有史以來第一個 `@Scheduled` 元件，必須同步更新
  `bff/src/main/java/com/steven/assets/bff/schedulelist/SchedulePublicBffController.java`，否則會被
  `scripts/spec-check.sh` 的 B6 擋下（「改動了 @Scheduled/cron，但未同步 SchedulePublicBffController.JOBS」）。**
  該檔目前 `JOBS` 只有 `BUSINESS`／`EXTERNAL` 兩個 service 常數（分別 28／37 筆，合計「65 筆」寫在 class
  Javadoc 與 `list()` 方法 Javadoc 裡）。本任務須：新增第三個 service 常數（例如
  `private static final String GATEWAY = "BFF 閘道觀測服務";`）；在 `JOBS` 尾端新增一筆
  `new ScheduledJobDto(GATEWAY, "API 錯誤紀錄", "Nginx gateway 錯誤擷取", "定期讀取 Nginx 錯誤 log，比對既有 13 條白名單路由後寫入 API 錯誤紀錄", "每 15 秒", "fixedDelay=15000ms", "")`
  （`schedule`/`cron` 文字須與實際採用的 `api-error-log.nginx-tail-interval-ms` 預設值一致）；把 class
  Javadoc「排程分屬兩個服務...（28 個）...（37 個）」改成三個服務、新增第三服務的方法清單（僅
  `NginxGatewayFailureLogTailer` 一項）；把「全系統排程清單（65 筆）」與 `list()` Javadoc 的「65 筆」都改成
  「66 筆」。

  **這個新增會連帶影響兩個現有、規劃階段才發現的消費端，都必須同步修改，否則會製造新的計數漂移：**
  - `bff/src/test/java/com/steven/assets/bff/schedulelist/SchedulePublicBffControllerTest.java` 現有
    `項目數正確()` 斷言 `assertThat(jobs()).hasSize(65)`——`JOBS` 變 66 筆後這行必定失敗，須改為
    `hasSize(66)`，並新增一行斷言新 service（`"BFF 閘道觀測服務"`）恰有 1 筆；`@DisplayName` 文案同步更新。
    下方「驗證」段落給的 `-Dtest` glob（`*ApiErrorLog*,*NginxGatewayFailureLogTailer*`）比對不到這個既有
    測試檔名，實作完成後**額外**手動跑一次
    `mvn -q -f bff/pom.xml test -Dtest='*SchedulePublicBffController*'` 確認沒有被本任務新增的第三個
    service 打紅。
  - `frontend/src/views/ScheduleListView.vue` 目前三處寫死「只有業務服務／外部行情服務兩種」：(a) 兩張
    per-service KPI 卡各自 hardcode `businessCount`/`externalCount` 這兩個 computed，「排程總數」KPI 卡的
    `jobs.length` 會變成 66 但兩張既有卡加總仍是 65，出現對不上的計數漂移；(b) `el-radio-group` 篩選只有
    「全部」「業務服務」「外部行情服務」三顆按鈕，篩不出新 service 那一筆；(c) `<el-tag>` 的服務顏色是
    `row.service === '業務服務' ? 'primary' : 'warning'` 二元判斷，新 service 會被誤套上「外部行情服務」的
    橘色。三處都必須改成依 `jobs` 資料動態算 distinct service 清單（例如一個
    `computed(() => 依 jobs 內容依序取得的 distinct service 陣列)`），KPI 卡與篩選按鈕依這份清單動態產生，
    tag 顏色改用一個涵蓋三個已知 service 名稱、對未知值有合理 fallback 的對照表——不得只是再加一個寫死的
    第三分支，因為那樣未來第四個 service 出現時會重蹈同樣的漂移。本頁目前沒有任何 contract test，驗收時
    須在 Docker runtime acceptance 額外打開 `/schedule-list` 頁面，肉眼確認 KPI 總數三者相加（或動態卡片
    加總）與「排程總數」一致、篩選按鈕能篩出新 service、新 service 的 tag 不是外部行情服務的顏色。

- [x] 421.5 **Docker 基礎設施：新增唯讀共用磁碟區，讓 Nginx 的錯誤 log 落地成檔案。**
  - `docker-compose.yml` 頂層 `volumes:` 新增（比照既有 `postgres_data`/`redis_data` 的具名慣例）：
    ~~~yaml
      nginx_gateway_log:
        name: asset-nginx-gateway-log
    ~~~
  - `api-gateway` service 新增：
    ~~~yaml
        volumes:
          - nginx_gateway_log:/var/log/nginx-shared
    ~~~
    （該 service 現有 `read_only: true` + `user: nginx`；只新增這一個具名 volume 的讀寫權限，不放寬
    其餘唯讀限制、不新增 `cap_add`。）
  - `bff` service 新增：
    ~~~yaml
        volumes:
          - nginx_gateway_log:/var/log/nginx-shared:ro
    ~~~
  - `api-gateway/Dockerfile`：在 `USER nginx` **之前**新增
    `RUN mkdir -p /var/log/nginx-shared && chown nginx:nginx /var/log/nginx-shared`——具名 volume
    第一次建立時會複製 image 內該路徑當下的屬主／權限，這是唯一讓非 root 的 `nginx` user 事後仍能寫入
    這個掛載路徑的方式（volume 若在鏡像沒有預先建立該目錄的情況下被建立，預設屬主是 root，`read_only: true`
    的容器內 `nginx` user 屆時無法補救）。
  - `api-gateway/nginx.conf`：`error_log /dev/stderr warn;` 這一行**保留**（`docker logs asset-api-gateway`
    行為不變），下方新增第二個目的地：
    ~~~nginx
    error_log /var/log/nginx-shared/error.log warn;
    ~~~
    （nginx 支援多個 `error_log` 指令同時生效，兩個目的地各自獨立寫入同一份內容。）`access_log` 不動。

- [x] 421.6 **前端：清單新增「HTTP 狀態碼」欄。** `frontend/src/views/ApiErrorLogsView.vue` 的
  `<el-table>` 在既有 `來源`（`prop="source"`）與 `API 名稱`（`prop="apiName"`）兩欄之間，新增
  `<el-table-column prop="httpStatus" label="HTTP 狀態碼" width="120">`，`httpStatus` 為 `null`
  （FUBON_API 的既有列）時顯示 `—`（用 `#default="{ row }"` slot：`{{ row.httpStatus ?? '—' }}`）。
  不改動既有三個 `el-select` 篩選、`grid-template-columns`、expand/lazy-detail 邏輯與其餘欄位。

- [x] 421.7 **測試。**
  - `backend/src/test/java/com/steven/assets/apierrorlog/ApiErrorLogServiceTest.java`：**既有測試目前直接用
    位置參數建構 `ListItem`/`Detail`（6／7 個參數），`httpStatus` 插入 `messageHeader`/`stackTrace` 之後、
    `occurredAt` 之前後會編譯失敗，必須同步補上這個參數（例如 `null`）並放對新位置，不是只新增測試。**
    新增／調整斷言，證明 `ListItem`/`Detail` 的 `from(...)` 正確帶出 `httpStatus`（含 null 值）。
  - `backend/src/test/java/com/steven/assets/apierrorlog/ApiErrorLogInternalControllerTest.java`：**既有唯一
    測試方法（驗證 private token 與 exact schema 的那個）目前的請求 JSON 只有 6 個 key，`FIELDS` 擴為 8 個
    後這個既有「應該成功」的案例會變成因欄位不齊而被拒絕（400），且該方法尾端
    `verify(recorder).record("OPEN_API", "OPEN_QUOTES_LIST", "即時報價清單", "safe", "trace", Instant.parse(...))`
    是 6 參數呼叫，`record(...)` 擴為 8 參數後這行會編譯失敗。這兩處都必須同步修改：payload 補上
    `"httpStatus"`（例如 `400`）與 `"dedupeKey"`（`null`）兩個 key，`verify(recorder).record(...)` 補上對應
    兩個引數，不能只當作新增測試的附帶結果。** 新增案例覆蓋——payload 缺 `httpStatus`／`dedupeKey` 任一
    key 時 400（8 個 key 缺一不可，呼應既有「缺欄即拒絕」精神）；`httpStatus` 非整數或超出 100–599 時
    400；`dedupeKey` 為 JSON `null` 與為字串時都能成功寫入且原樣保存；`httpStatus`／`dedupeKey` 正確傳給
    `ApiErrorLogRecorder.record(...)`（可用 mock 驗證呼叫參數)。
  - 在 `backend/src/test/java/com/steven/assets/apierrorlog/` 下新增一個驗證 `dedupe_key` partial unique
    index 的 Postgres 整合測試（命名比照下述既有檔案的慣例，例如以 `...DedupeKeyPostgres` 加測試框架慣用字尾）：
    比照既有 `backend/src/test/java/com/steven/assets/integration/fubon/FubonTradeSyncUniqueIndexPostgresTest.java`
    的既有模式（`@DataJpaTest` + `spring.jpa.hibernate.ddl-auto=create-drop` + `spring.liquibase.enabled=false` +
    `@Testcontainers` 起真實 `postgres:16-alpine` + `@BeforeEach` 用 `entityManager.createNativeQuery(...)`
    手動補上本任務的 partial unique index DDL，因為它和 421.1 的 CHECK 一樣不是 JPA annotation 能表達的）。
    至少驗證：兩筆 `dedupeKey` 相同的 `ApiErrorLog` 存檔，第二筆丟出 `DataIntegrityViolationException`
    且最終只剩一筆；`dedupeKey` 為 `null` 的多筆 row 完全不受這個唯一索引限制（比照既有
    `nullBrokerFilledNoRowsAreExemptFromTheUniqueIndex` 案例的精神）。這個測試刻意不用 mock/H2，
    理由與既有 `FubonTradeSyncUniqueIndexPostgresTest` 檔頭註解一致：partial unique index 在真實
    PostgreSQL 上的擋下行為，mock repository 驗證不到。
  - `bff/src/test/java/com/steven/assets/bff/apierrorlogs/PublicApiErrorCaptureWebFilterTest.java`：**既有
    `captured_4xx_and_successful_204_and_unknown_404_produce_no_row` 必須拆解，不能整支原樣保留。** 它目前
    把三個子案例（`/api/quotes`+`BAD_REQUEST`+capture=true、`/api/quotes/one`+`NO_CONTENT`+無 capture、
    `/api/not-catalogued`+`NOT_FOUND`+capture=true）綁在同一個 `verifyNoInteractions(ingest)` 底下；本任務
    放寬後，第一個子案例（4xx 且已捕獲 Throwable）會實際呼叫一次 `ingest(...)`，若不拆開，這個既有測試方法
    在新邏輯下必定編譯過但斷言失敗。改法：`/api/quotes/one`（204）與 `/api/not-catalogued`（404，未列白名單）
    兩個子案例留在原測試方法、繼續斷言 `verifyNoInteractions(ingest)`；`/api/quotes`+`BAD_REQUEST`+capture=true
    移到一個新測試方法，斷言 `ingest(...)` 恰被呼叫一次且傳入的 `httpStatus` 等於 `400`。另外新增一個案例：
    controller 直接回一個沒有對應 Throwable 的 4xx（不經 advice/exception path，即從未呼叫過
    `PublicApiErrorCaptureWebFilter.capture(...)`）時仍維持 zero row，證明「4xx 只在已捕獲 Throwable 時才記」
    這個條件確實有被檢查、不是只要 4xx 就記。**既有 `final_matched_5xx_uses_advice_captured_raw_throwable_and_its_boundary_time`
    也要同步修改**：其 `verify(ingest).ingest(eq(...), eq(...), contains(...), contains(...), eq(capturedAt))`
    是舊的 5 參數呼叫，`ingest(...)` 擴為 7 參數（新增 `httpStatus`、`dedupeKey`）後這行會編譯失敗，必須補上
    `eq(<實際 5xx 狀態碼>)` 與 `eq((String) null)`（filter-driven capture 固定不去重）兩個引數。既有其餘
    5xx／status-fallback 案例只需確認在新簽章下仍能編譯、語意不變。
  - **`bff/src/test/java/com/steven/assets/bff/apierrorlogs/ApiErrorLogsBffControllerTest.java`：這是
    Requirement 143 AC「BFF 既有三個 proxy endpoint 不需改程式碼，但須有測試證明」唯一對應的測試檔，
    必須新增，不可省略。** 該檔目前只有一個測試方法覆蓋 `GET /api/bff/api-error-logs/operations`；`list`
    （`GET /api/bff/api-error-logs`）與 `detail`（`GET /api/bff/api-error-logs/{id}`）兩個 endpoint 完全沒有
    測試。新增至少一個測試方法：讓 fake/stub business 回應含 `"httpStatus":400`（或任一非 null 整數）的
    JSON，驗證 `ApiErrorLogsBffController.list(...)` 的 `Mono<Object>` opaque relay 原樣把這個欄位傳給呼叫端
    （byte-for-byte 或至少 JSON 節點相等），不因新增欄位而遺漏或轉換型別；`httpStatus` 為 JSON `null` 時
    同樣原樣透傳。
  - 為新的 `NginxGatewayFailureLogTailer` 在 `bff/src/test/java/com/steven/assets/bff/apierrorlogs/` 下
    新增對應單元測試（命名比照同目錄既有 `PublicApiErrorCaptureWebFilterTest.java` 的慣例）：
    以暫存檔案（`@TempDir`）餵入下列今天實際擷取到的真實 log 行（直接複製，不得改寫格式）作固定測試
    fixture，斷言恰好比對到一筆 `OPEN_MARKET_INDEX`、`httpStatus=502`、`dedupeKey` 為該行 trim 後文字的
    SHA-256 hex：

    ~~~text
    2026/09/07 01:48:59 [error] 30#30: *21507 connect() failed (111: Connection refused) while connecting to upstream, client: 127.0.0.1, server: _, request: "GET /api/public/market-index HTTP/1.1", upstream: "http://172.18.0.4:8080/api/public/market-index", host: "127.0.0.1:9090"
    ~~~

    另外覆蓋：`[warn]` 等級行（例如既有 `an upstream response is buffered to a temporary file...`）不
    產生任何 ingest 呼叫；`request:` 路徑不在 13 條白名單內的 `[error]` 行整行捨棄；訊息含 `timed out`
    字樣時 `httpStatus=504`；同一行連續兩個 tick 都比對到時呼叫 `ingest(...)` 兩次、且兩次 `dedupeKey`
    完全相同（去重本身由 DB 唯一索引負責，這裡只需證明 tailer 產生的 key 是穩定、可重現的）；掛載檔案
    不存在時不拋出例外。
  - `frontend/src/utils/apiErrorLogsView.contract.test.js`：新增案例驗證 table 多出一欄
    `prop="httpStatus"` 且置於 `source`／`apiName` 之間、`httpStatus` 為 `null` 時渲染 `—`；既有三個
    `el-select`、grid 比例、lazy detail 相關斷言不變。
  - 不得新增任何呼叫 `POST /api/public/crawler-data/rescan`、真實 Fubon SDK 或 broker 的測試流程。

## 驗證

先執行可重現的本地測試與靜態檢查：

~~~bash
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test -Dtest='*ApiErrorLog*' -DextraArgLine=-Dnet.bytebuddy.experimental=true
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f bff/pom.xml test -Dtest='*ApiErrorLog*,*NginxGatewayFailureLogTailer*' -DextraArgLine=-Dnet.bytebuddy.experimental=true
/Users/steven/.nvm/versions/node/v22.21.0/bin/npm --prefix frontend test
/Users/steven/.nvm/versions/node/v22.21.0/bin/npm --prefix frontend run build
bash scripts/spec-check.sh
git diff --check
git diff --cached --check
~~~

先以 `run-stack` 重建並 `recreate business-services`，讓 Liquibase migration 實際完成；此步驟之前的舊
schema drift 結果不能作為本任務證據。確認 business health 後，依 `db/schema.sql` 檔頭「重新產生」的三步
程序從此刻 `asset-postgres` 重產 `db/schema.sql`，再跑 `bash scripts/tests/schema-sql-drift-test.sh`。完成後
才重建／recreate `api-gateway`（新 volume/Dockerfile/nginx.conf）、`bff`（新 volume/filter/ingest
client/新排程）與 `frontend`，並確認 `api-gateway`、`bff` 皆為 healthy。

**注意這個重建順序本身會製造一段預期內、短暫的靜默視窗：** `business-services` 先換成要求 8 個 key
（`ApiErrorLogInternalController.FIELDS` 嚴格相等）的新版之後、到 `bff` 也換成送 8 個 key 的新版之前，
`bff` 仍在跑舊版只送 6 個 key 的 ingest，會被新版 `business-services` 一律判 400；反之若順序顛倒，新版
`bff` 送 8 個 key 給還沒更新的舊版 `business-services` 也會被判 400。這個 400 會被
`ApiErrorLogIngestClient` 既有的 `onErrorResume` 吞掉，不會影響任何公開 API 的成功/失敗回應，只是這段
視窗內（不含 gateway-log 排程，因為 `bff` 都還沒重建、排程根本還不存在）**開放 API 側的 5xx 擷取會整段
暫時失效、不留痕跡**。這是既有 R141 best-effort、fire-and-forget 設計下的既有已知取捨（不是本任務新增
的缺陷），驗收時若這段視窗內剛好有失敗發生而清單沒看到對應 row，不要誤判為本任務的 bug；驗收判定一律
以「兩邊都重建完成、都 healthy 之後」的行為為準。

Docker runtime acceptance（除既有 Task 417 的 health/schema 驗證外，本任務另需以下步驟）：

1. 確認新 volume 確實掛載成功且跨 uid 可讀：
   ~~~bash
   docker exec asset-api-gateway sh -c 'ls -la /var/log/nginx-shared && echo write-check > /var/log/nginx-shared/.write-check && rm /var/log/nginx-shared/.write-check'
   docker exec asset-bff sh -c 'ls -la /var/log/nginx-shared && cat /var/log/nginx-shared/error.log | tail -5'
   ~~~
   兩個指令都必須成功、不得出現 `Permission denied`。
2. 用不合法 `market` 值驗證新的 4xx 擷取：
   ~~~bash
   curl -s -o /dev/null -w '%{http_code}\n' 'http://127.0.0.1:9090/api/public/market-index?market=NASDAQ&range=5y'
   ~~~
   應回 `400`；稍待排程／filter 寫入後，以既有 ADMIN 帳號查 `GET /api/bff/api-error-logs?source=OPEN_API&operationKey=OPEN_MARKET_INDEX`，
   確認出現一筆新 row，`httpStatus=400`。
3. 用短暫停止 upstream 驗證新的 gateway-502 擷取（僅限 acceptance 環境，不得對正式對外服務執行）：
   ~~~bash
   docker compose -p asset-management stop bff
   curl -s -o /dev/null -w '%{http_code}\n' 'http://127.0.0.1:9090/api/public/market-index'
   docker compose -p asset-management start bff
   ~~~
   應回 `502`；等待 `bff` 健康且新排程至少跑過一輪 tick（預設 15 秒）後，查同一個 list endpoint，確認
   出現一筆 `httpStatus=502`、`messageHeader` 含「無法連線」字樣的新 row；**立即再等一輪 tick 後重查**，
   確認筆數仍是 1（去重生效，未產生第二筆可見 row）。
4. 前端以既有 ADMIN 帳號開啟 `/api-error-logs`，桌面寬度下確認新的「HTTP 狀態碼」欄出現在「來源」與
   「API 名稱」之間；既有 sentinel row（`API_ERROR_LOG_ACCEPTANCE_SENTINEL`，`source=OPEN_API`）該欄應
   顯示 `—`（它是 Task 417 acceptance 時期寫入，寫入當下沒有 `httpStatus`）。

## 完成報告

**本任務分兩段完成。** 第一段（421.1–421.6 與 421.7 的既有測試修改部分）由前一個 subagent 完成，
但在 421.7 剩餘的測試撰寫階段因 API session 額度用完被強制中斷。本報告由接續完成剩餘部分的第二個
subagent 回填，涵蓋：逐項核對前一階段成果是否符合本任務規格、補完 421.7 缺的 5 項測試、執行本檔
「驗證」段落最前面的本地指令、以及 421.1 的 schema 重產驗證。**Docker runtime acceptance（步驟
1–4）與 `/commit-merge-push` 不在本階段範圍內，留給主 agent 之後另外處理**（見下方說明）。

### 421.1–421.6 核對結果：與規格相符，未發現需修正之處

逐一比對前一階段已完成的檔案與本任務檔規格文字（含程式碼片段、欄位順序、參數簽章），確認以下皆
正確、不需修改：

- `backend/src/main/resources/db/changelog/changes/v1.125.0-api-error-log-status-and-dedupe.sql`：
  DDL 與規格給定文字逐字相符（`http_status SMALLINT` CHECK 100–599、`dedupe_key VARCHAR(64)`、
  partial unique index），已納入 `db.changelog-master.yaml` 尾端 include。
- `ApiErrorLog.java`／`ApiErrorLogRecorder.java`／`ApiErrorLogInternalController.java`／
  `ApiErrorLogService.java`：建構子簽章、`FIELDS` 8 key、`httpStatus`/`dedupeKey` 驗證邏輯、
  `ListItem`/`Detail` 欄位順序（`httpStatus` 置於 `messageHeader`/`stackTrace` 之後、`occurredAt`
  之前，且不含 `dedupeKey`）皆與規格逐字相符；`record(Throwable,...)` 5 參數 overload 簽章未變、
  唯一呼叫端 `FubonHttpClient.java:249` 未被觸碰。
- `PublicApiErrorCaptureWebFilter.java`：`finalStatus(...)` 的 5xx-一律記錄／4xx-僅捕獲-Throwable-時記錄
  判斷式、`recordOnce(...)` 新增 `httpStatus` 參數與 synthetic message 改用實際值，皆與規格相符。
- `ApiErrorLogIngestClient.java`：`ingest(...)` 新增 `int httpStatus`，且已將 `Map.of(...)` 改為
  `LinkedHashMap`（規格明確提醒的 `Map.of` null-value NPE 陷阱已正確迴避）。
- `OpenApiRouteCatalog.java`：13 筆路由與 `ApiErrorLogOperationCatalog` 的 OPEN_API 13 筆 operationKey
  逐一核對一致；`PublicApiErrorCaptureWebFilter` 已改為引用它、不再維護私有副本。
- `NginxGatewayFailureLogTailer.java`：逐行解析規則（`[error]`-only、UTC 時間戳轉換、
  `request:` 擷取＋query-string 剝離＋白名單比對、`timed out`→504／其餘 502、
  `messageHeader` 固定樣式、`stackTrace` 走既有 sanitize、`dedupeKey` = SHA-256 hex）與規格逐條相符；
  檔案不存在時 `Files.isRegularFile(...)` 為 false 即整輪略過、僅寫 debug log，不拋例外。
- `SchedulePublicBffController.java`／`SchedulePublicBffControllerTest.java`：**這部分前一階段已經
  做完**（含 JOBS 從 65→66 筆、新增 `GATEWAY` service 常數、class Javadoc 三服務化、既有
  `項目數正確()` 斷言已改為 `hasSize(66)`＋新增 BFF 閘道觀測服務 1 筆的斷言）——與本檔交派說明中
  「明顯還沒做的部分」清單不同，此檔實際上已經處理完成，核對後未發現需修正之處。
- `docker-compose.yml`／`api-gateway/Dockerfile`／`api-gateway/nginx.conf`：具名 volume、
  `USER nginx` 前的 `mkdir+chown`、第二個 `error_log` 目的地皆與規格相符；`api-gateway` 仍
  `read_only: true`＋`user: nginx`＋僅 `cap_drop: ALL`（未新增 `cap_add`）。
- `frontend/src/views/ApiErrorLogsView.vue`：「HTTP 狀態碼」欄位置於「來源」與「API 名稱」之間，
  `httpStatus ?? '—'`。
- `frontend/src/views/ScheduleListView.vue`：KPI 卡／篩選按鈕／tag 顏色皆已改為依 `distinctServices`
  動態產生（非寫死二/三元分支），對照表對未知 service 有 fallback。
- `spec/design.md`／`spec/requirements.md`／`spec/steering/structure.md`／`CLAUDE.md`：Requirement
  143 段落完整、且明確列出取代 Requirement 141 哪些過期文字；`CLAUDE.md`／`structure.md` 的
  Requirements 計數已由 134→135、142→143。`CLAUDE.md` 描述 `spec/tasks.md` 那一列沒有一併提到
  Task 421（仍只舉 Task 417 為例），但依專案既有慣例（Task 417 之後的新任務一律不追加進
  `spec/tasks.md` 索引），`spec/tasks.md` 本就不需要為 Task 421 更動，此列不算過期，不需修正。

### 421.7 本階段補完的 5 項測試

1. `bff/src/test/java/com/steven/assets/bff/apierrorlogs/PublicApiErrorCaptureWebFilterTest.java`：
   拆解原 `captured_4xx_and_successful_204_and_unknown_404_produce_no_row`——204／未列白名單 404
   兩個子案例留在改名後的 `successful_204_and_unknown_404_produce_no_row()`；`/api/quotes`+
   `BAD_REQUEST`+capture=true 移到新方法 `captured_4xx_on_matched_route_records_once_with_real_status()`，
   斷言 `ingest(...)` 恰被呼叫一次、`httpStatus=400`。新增
   `uncaptured_4xx_from_controller_produces_no_row()` 證明「無 Throwable 的 4xx 仍是 zero row」。
   `final_matched_5xx_uses_advice_captured_raw_throwable_and_its_boundary_time()` 的
   `verify(ingest).ingest(...)` 補上 `eq(HttpStatus.BAD_GATEWAY.value())` 與 `eq((String) null)` 兩個
   新引數。
2. `bff/src/test/java/com/steven/assets/bff/apierrorlogs/ApiErrorLogsBffControllerTest.java`：新增
   `list_relays_http_status_field_unchanged_for_both_a_real_value_and_json_null()`，以 fake business
   回應驗證 `list(...)` 的 `Mono<Object>` opaque relay 對 `httpStatus`（含非 null 整數與 JSON null）
   原樣透傳（JSON 節點相等）。
3. 新增 `backend/src/test/java/com/steven/assets/apierrorlog/ApiErrorLogDedupeKeyPostgresTest.java`：
   比照 `FubonTradeSyncUniqueIndexPostgresTest` 的 `@DataJpaTest`＋`ddl-auto=create-drop`＋
   `spring.liquibase.enabled=false`＋Testcontainers 真實 `postgres:16-alpine` 模式，`@BeforeEach`
   手動補上 `uq_api_error_log_dedupe_key` partial unique index DDL。兩個測試：重複 `dedupeKey` 的
   第二筆 `saveAndFlush` 丟出 `DataIntegrityViolationException` 且最終只剩一筆；`dedupeKey=null` 的
   多筆 row 不受此唯一索引限制。
4. 新增 `bff/src/test/java/com/steven/assets/bff/apierrorlogs/NginxGatewayFailureLogTailerTest.java`：
   6 個測試——規格給定的真實 log fixture（逐字複製）比對到 `OPEN_MARKET_INDEX`／`httpStatus=502`／
   `dedupeKey` 為該行 SHA-256 hex；`[warn]` 行不觸發 ingest；不在白名單的 `[error]` 行整行捨棄；
   含 `timed out` 時 `httpStatus=504`；同一行連續兩次 `tail()` 呼叫 ingest 兩次且 `dedupeKey` 相同；
   掛載檔案不存在時不拋例外、不呼叫 ingest。
5. `frontend/src/utils/apiErrorLogsView.contract.test.js`：新增案例驗證
   `prop="httpStatus" label="HTTP 狀態碼" width="120"`、`{{ row.httpStatus ?? '—' }}` 存在，且欄位
   順序介於 `prop="source"` 與 `prop="apiName"` 之間；不動既有斷言。

### 本地驗證指令實際輸出

全部通過，無需修改任何原始碼即讓測試轉綠（撰寫時已對齊既有簽章，一次到位）：

```
mvn -q -f backend/pom.xml test -Dtest='*ApiErrorLog*' -DextraArgLine=-Dnet.bytebuddy.experimental=true
  → exit 0；5 個測試類別、共 15 個測試，0 failures／0 errors
    （ApiErrorLogDedupeKeyPostgresTest 2、ApiErrorLogDiagnosticRendererTest 2、
      ApiErrorLogInternalControllerTest 5、ApiErrorLogRetentionSchedulerTest 1、
      ApiErrorLogServiceTest 5）

mvn -q -f bff/pom.xml test -Dtest='*ApiErrorLog*,*NginxGatewayFailureLogTailer*,*SchedulePublicBffController*' \
  -DextraArgLine=-Dnet.bytebuddy.experimental=true
  → exit 0；ApiErrorLogDiagnosticRendererTest 1、ApiErrorLogsBffControllerTest 2、
    NginxGatewayFailureLogTailerTest 6、SchedulePublicBffControllerTest 18，0 failures／0 errors

npm --prefix frontend test  → exit 0；43 個測試全過（含 apiErrorLogsView.contract.test.js 2 個）
npm --prefix frontend run build  → exit 0（僅既有、與本任務無關的 chunk-size 警告）
bash scripts/spec-check.sh  → BLOCK: 0　CHECK: 2（皆為既有制度性提醒，非本任務新增問題）
git diff --check  → exit 0（無空白字元錯誤）
git diff --cached --check  → exit 0
```

**上述 bff 測試 glob 有一個既有落差，非本階段造成，特此記錄：** 本檔「驗證」段落給的
`-Dtest='*ApiErrorLog*,*NginxGatewayFailureLogTailer*'` 這個 pattern **比對不到**
`PublicApiErrorCaptureWebFilterTest`（類名是 `ApiErrorCapture`，不含 `ApiErrorLog` 子字串），
而本階段對這個檔案做了結構性修改（拆解測試方法、新增引數）。已另外手動執行
`mvn -q -f bff/pom.xml test -Dtest='PublicApiErrorCaptureWebFilterTest' -DextraArgLine=...`
確認 5 個測試全過（0 failures／0 errors）。建議主 agent 之後有機會可把這個 glob 落差一併記錄或
修正（不在本任務改動範圍內，故未在此次變更中一併修正該檔頭文字）。

### 421.1 schema 重產驗證：確認為最新，但發現一個非阻塞性的 Liquibase 記錄落差

`asset-postgres` 容器當時正在跑（`docker ps` 確認 `Up 2 days (healthy)`）。執行
`bash scripts/tests/schema-sql-drift-test.sh`（及 `spec-check.sh` 內建的 B10）皆回報：

```
PASS: db/schema.sql（去除專案檔頭後）逐位元等於 asset-postgres 此刻的 pg_dump 輸出
      表數：99 張（檔頭宣告一致）
```

即 `db/schema.sql` 目前與容器內**實際物理 schema**逐位元相符。進一步用
`information_schema.columns`／`pg_indexes` 直接查證 `api_error_log` 表，確認
`http_status`（smallint）、`dedupe_key`（varchar）兩欄與 `uq_api_error_log_dedupe_key`
（`UNIQUE ... WHERE dedupe_key IS NOT NULL`）皆確實存在於容器內。

**但同時查詢 `databasechangelog` 發現一個落差**：目前紀錄的最新已執行 changeset 是
`v1.124.0-realized-gain-fubon-sync`（`orderexecuted=159`），**沒有** `v1.125.0-api-error-log-status-and-dedupe`
的紀錄列。也就是說，`api_error_log` 表上的新欄位與索引雖然物理上已存在、且與遷移檔定義完全一致，
但這個狀態**極可能是前一階段直接對容器手動執行 DDL（例如透過 `docker exec ... psql`）而非透過真正
啟動 `business-services` 讓 Liquibase 實際跑過這個 changeset** 而達成的——目的應該是在沒有時間/機會
跑一次完整 app 重啟的情況下，先讓 `db/schema.sql` 的 pg_dump 重產有一個可比對的基準。

**這不是阻塞性問題，判斷理由：** 本 changeset 的三條 DDL 語句全部使用 `IF NOT EXISTS` 防護
（`ADD COLUMN IF NOT EXISTS`／`CREATE UNIQUE INDEX IF NOT EXISTS`），這正是規格文字本身要求的寫法。
之後 `business-services` 真正重啟、Liquibase 依 `databasechangelog` 判斷 `v1.125.0` 尚未執行而嘗試
執行它時，三條語句會因為目標已存在而成為 no-op、不會報錯，Liquibase 會照常把這個 changeset
記錄為已執行——**這正是本檔「驗證」段落本身就預期並要求的順序**：「先以 `run-stack` 重建並
`recreate business-services`，讓 Liquibase migration **實際完成**；此步驟之前的舊 schema drift
結果不能作為本任務證據。」換言之，本階段做到的是「`db/schema.sql` 目前已對得上容器物理現狀」，
但「Liquibase 真正跑過這個 changeset 並完成記錄」這件事，如同本檔規劃，仍待主 agent 之後執行
`/run-stack` 並 `recreate business-services` 時才會真正發生與驗證，屆時 Liquibase log 會顯示
`v1.125.0-api-error-log-status-and-dedupe` 首次執行成功（即使欄位/索引早已存在也不會報錯）——
這是預期行為，請主 agent 不要誤判為異常。**本階段除了查證與記錄此發現外，未對 `databasechangelog`
或容器做任何寫入或修改**（不在本階段授權範圍內，Docker rebuild/recreate 明確留給主 agent）。

### 與本檔規劃不符的差異

除上述「421.4 交派說明誤判 `SchedulePublicBffController` 為未完成」與「bff 測試 glob 既有落差」兩點
外，未發現其他差異——421.1–421.6 的既有實作內容與本檔規格逐項核對後完全相符，未做任何修正。

### 留給主 agent 的部分（明確不在本階段範圍內）

1. **Docker runtime acceptance 步驟 1–4**（volume 掛載讀寫查證、4xx 擷取、gateway-502 擷取與去重、
   前端 `/api-error-logs` 目視驗證）：需要先 `/run-stack` 重建 `api-gateway`／`bff`／`frontend`
   並 `recreate business-services`，本階段依交派範圍未執行。
2. **確認 Liquibase 對 `v1.125.0` 的正式執行記錄**：如上節所述，`recreate business-services` 時
   應會自然發生；建議 `/run-stack` 後順手用
   `docker exec asset-postgres psql -U assets -d assets -tAc "select id,dateexecuted from databasechangelog where id='v1.125.0-api-error-log-status-and-dedupe'"`
   確認確實補上這筆記錄（預期會有，因為 DDL 是 `IF NOT EXISTS` 冪等寫法）。
3. **`/commit-merge-push`**：本階段明確被指示不得執行 `git commit`／`merge`／`push`，即使本地驗證
   已全數通過，仍交由主 agent 驗收 Docker runtime acceptance 後另外處理。
4. **spec-review 重審提示**：本階段編輯 `spec/tasks/t421_....md` 打勾與回填完成報告時，
   `notify-spec-changed` hook 依既有機制觸發了一次性提示（寫入 `spec/` 後的既有行為，非新問題）；
   由於這純屬完成報告回填、不影響任何功能契約，且本階段之後不再有任何 `backend/**`／`bff/**`／
   `frontend/src/views/*.vue` 的寫入需求，故未觸發 `require-spec-review` 的阻擋。若主 agent 之後仍
   需要對這些路徑做任何寫入，屆時可能會被 `require-spec-review` 攔下，需要時可執行 `/spec-review`
   重新記錄通過雜湊。

### Docker runtime acceptance 執行結果（主 agent 派 `/run-stack` subagent 完成）

依上節規劃順序執行：`business-services`（`--no-cache`）→ 確認 healthy → 重產 `db/schema.sql` → drift
test → `api-gateway`／`bff`／`frontend`（`--no-cache`）→ 依序 recreate 並逐一等 healthy。全程對到本機
唯一在跑的 `-p asset-management` stack，未另建第二套，四個服務映像 ID 均對應本次 build 產物。

**Liquibase v1.125.0：確認真正執行過。** `docker logs asset-business-services` 顯示
`Running Changeset...v1.125.0...ran successfully`，三條 DDL 因先前已手動套用而各自印出
`already exists, skipping`（如上節預期，冪等 `IF NOT EXISTS` 寫法的設計目的）；`databasechangelog`
新增一列 `orderexecuted=160`。`db/schema.sql` 重新產生後與此前版本零差異，`schema-sql-drift-test.sh`
PASS（99 表逐位元相符）。

**步驟 1（volume 讀寫）：通過。** `api-gateway` 對新 volume 寫入/刪除成功；`bff` 唯讀掛載列出/cat
成功；均無 `Permission denied`。

**步驟 2（4xx 擷取）：通過。** `market=NASDAQ` 回 400；稍待後讀模型出現一筆 `httpStatus:400` 新 row。

**步驟 3（gateway 502 擷取＋去重）：通過，並發現一項真實但非缺陷的細節。** 停 `bff`、送出請求驗證
回 502、`start bff` 後，等排程跑過一輪，`list` 端點出現的是**兩筆**新 502 row（非任務檔敘述隱含的
「一筆」），追查後確認：`api-gateway` 既有的 10 秒一次 healthcheck 剛好在同一停機窗口也真實打中
502，兩筆分別對應 nginx 兩行不同的 `[error]` 原始文字（不同 connection id／timestamp）——即同一次
停機視窗內確實發生了兩個不同來源、各自獨立的真實失敗事件，各自正確各留一筆，**不是去重機制失效**；
`docker logs asset-business-services` 持續每個 tick 出現 `SQLState 23505`（unique violation 被吞掉），
證明去重本身逐 tick 持續正確運作。這件事本身反而是這個功能設計正確性的佐證：多個真實失敗會各自留下
獨立紀錄，不會被誤併成一筆。建議日後若重跑這個手動步驟，此為預期可能觀察到的結果，不需視為異常。

**步驟 4（前端「HTTP 狀態碼」欄）與額外的 `/schedule-list` 檢查：API／建置產物層級通過，未做瀏覽器
實際登入操作。** 本頁與排程列表頁的 ADMIN 帳號登入是真實 Google OAuth，依安全規則不得代為輸入密碼；
`/run-stack` subagent 嘗試開瀏覽器確認會導向真實登入頁後即主動停止。改用 business-services 內部
`X-User-Role: ADMIN` header（比照既有「免 OAuth 端到端測試」慣例）驗證 API 回應正確（`httpStatus`
欄位存在、既有 sentinel row 為 `null`），並直接 grep 部署中的建置產物確認：
`ApiErrorLogsView-*.js` 含精確的 `prop:"httpStatus",label:"HTTP 狀態碼",width:"120"` 且位置介於
`來源`／`apiName` 之間；`SchedulePublicBffController` class（`unzip` 部署中的 `bff/app.jar`）確認
三個 service 字串皆已編譯進去、`JOBS` 恰 66 筆；`ScheduleListView-*.js` 建置產物確認動態 tag 顏色表
含 `"BFF 閘道觀測服務":"success"`（非誤套外部行情服務的 warning 色）。**這兩項因此只有 API／程式碼
層級證據，沒有實際「肉眼看畫面」的確認**；若後續有非真人 OAuth 的測試登入路徑可用，建議補一次真正
的瀏覽器視覺驗收。

**範圍紀律：** 全程未執行 `git commit`／`merge`／`push`、未修改任何程式碼、未觸碰 `db/init/**` 或
清空過任何資料卷；除本檔明確授權的步驟 3 外未主動停過其他服務。
