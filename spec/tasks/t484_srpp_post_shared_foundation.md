# [t484] SRPP 9090 POST 端點共用基礎（媒體型別與錯誤處理、problem 載體、owner 解析、Swagger 身分、API 錯誤日誌目錄）

**對應 Requirements:** Requirement 184（SRPP 事件證據與決策兩個 9090 POST 端點共用的契約基礎設施，先於各自的業務實作完成）
**前置任務:** t483（佔位實作先改為 fail-closed）
**Liquibase changeset:** v1.146.0-srpp-api-error-log-operations.sql（insert-only）

## 背景

`POST /api/public/srpp/event-evidence/capture`（t481）與 `POST /api/public/srpp/daily-decision/evaluate`（t482）共用同一個 BFF controller（`bff/src/main/java/com/steven/assets/bff/publicsrpp/SrppCaptureController.java`，其 relay 類 `SrppCaptureRelay` 在同一檔）與同一個 business controller（`backend/src/main/java/com/steven/assets/controller/InternalSrppCaptureController.java`）。這兩個端點需要一組共同的基礎設施，而目前這些在 main 上都有缺口（以下皆已用程式碼查證）：

1. **415 與未預期錯誤。** 兩個 controller 都以 `consumes=application/json` 宣告。Content-Type 不符時例外在 handler mapping 階段拋出，專案的 `@RestControllerAdvice(assignableTypes=…)`（例如 `PublicSrppOrchestratedExceptionAdvice`）抓不到它（Spring 的 `HandlerTypePredicate` 在沒有 handler 型別時，對帶 selector 的 advice 回 false）。結果：BFF 回 Spring 預設的 415 body（不是 problem+json）；business 端落入全域 `GlobalExceptionHandler` 的 `@ExceptionHandler(Exception.class)` 回 500，且 `detail` 帶 `ex.getMessage()`。
2. **problem 載體。** backend `SrppCaptureProblem` 只有 `(status, code)`；`InternalSrppCaptureController.problem()` 與 BFF `SrppCaptureRelay.problem()` 都只輸出四欄 `{type,title,status,code}`；既有 `SrppProblemCatalog.INSTANCE` 固定為 `/api/public/srpp/daily-context`，不能直接重用。
3. **BFF 沒有 owner 解析。** 既有 SRPP GET 端點由 `PublicSrppOrchestratedService.owner(String)`（private 方法）以 `BusinessUserClient.configuredAdmin()`／`byEmail` 解析 owner 並帶 `X-User-*` headers；這兩個 POST 的 relay 只是轉送 bytes，business 端沒有 owner。
4. **business 端不知道「已發布的 Swagger 身分」。** 請求的 `swaggerSha256` 無處可比。business-services 的 Docker build context 只有 `./backend`（`docker-compose.yml` 的 `context: ./backend`），讀不到 `docs/openapi/`。
5. **API 錯誤日誌目錄缺口。** BFF `OpenApiRouteCatalog` 已登記 `OPEN_SRPP_DAILY_REPORT_MAIL`、`OPEN_SRPP_DAILY_REPORT_MAIL_STATUS`、`OPEN_SRPP_EVENT_EVIDENCE`、`OPEN_SRPP_DAILY_DECISION` 四個 key，但 backend `ApiErrorLogOperationCatalog.OPERATIONS` 與 `api_error_log_operation` 資料表沒有對應列，`require(...)` 會丟 `IllegalArgumentException`，這四條路由的 5xx 會被靜默丟棄。另外 BFF 有四個既有 key 的標籤與 backend 不同（`OPEN_SRPP_CALCULATIONS`：BFF「SRPP 計算」對 backend「SRPP 按需計算」；`OPEN_SRPP_MARKET_FACTS`：「SRPP 市場事實」對「SRPP 批次市場事實」；`OPEN_SRPP_COMPLETED_TECHNICALS`：「SRPP 完成技術」對「SRPP 完成日技術事實」；`OPEN_SRPP_TECHNICAL_SERIES`：「SRPP 技術序列」對「SRPP 逐日技術序列」），這四條路由的 5xx 目前就被 ingest 退回。

本任務把這些基礎設施一次做好，讓 t481（事件證據）與 t482（決策引擎）只需專注各自的業務。**不新增路徑**（9090 manifest 維持 23 個 method/path pairs），**不改業務行為**（兩個端點在 t481、t482 完成前仍由 t483 的 fail-closed 503 回應）。

## 要做什麼

### A. 媒體型別與未預期錯誤（BFF 與 business 兩層）

- [x] 484.1 在 BFF `SrppCaptureController` 與 business `InternalSrppCaptureController` 的兩個 POST mapping **移除 `consumes`**，改由 controller 入口自行檢查：`Content-Type` 的 type 必須是 `application`、subtype 必須是 `json`（不分大小寫），參數（如 `charset`）忽略，**不接受** `+json` 變體與萬用字元；缺失或不符 → 415 `UNSUPPORTED_MEDIA_TYPE`（`application/problem+json`）。business 端 `@RequestBody String` 改為 `required=false`，body 為 null 或全空白 → 400 `INVALID_REQUEST`（BFF 的 `Mono<byte[]>` 本來就容許空 body，空 body 同樣 → 400）。注意 Spring 在**參數解析階段**就會對語法不合法的 Content-Type 拋例外（MVC：`HttpMediaTypeNotSupportedException`；WebFlux：`UnsupportedMediaTypeStatusException`），早於方法本體，所以兩層的 controller 都要加**控制器本地**的 `@ExceptionHandler` 把這些例外映射為同一個 415 problem（WebFlux 另把 `ServerWebInputException` 映射為 400 `INVALID_REQUEST`）。控制器本地 handler 優先於全域 advice，且與既有 `@ExceptionHandler(SrppCaptureProblem.class)` 並存（以最近的例外型別為準）。
- [x] 484.2 兩個 controller 各加控制器本地 `@ExceptionHandler(Exception.class)`：未預期例外回 500 `INTERNAL_ERROR`（problem 本體不得含 stack trace、SQL、內部 URL、帳號或 `ex.getMessage()`）。**伺服器端 log** 必須記錄例外類別與 stack trace（不含 request 內容與帳號），否則 500 無法排錯；BFF 端比照 `PublicSrppOrchestratedExceptionAdvice.unexpected` 呼叫 `PublicApiErrorCaptureWebFilter.capture(exchange, ex)`，讓 5xx 進入 API 錯誤日誌。
- [x] 484.3 驗收測試的切片：business 用 `@WebMvcTest(InternalSrppCaptureController.class)` 搭配 `@Import(GlobalExceptionHandler.class)` 與 `@MockBean`（repo 內沒有 `@SpringBootTest` 的先例，`InternalPublicMarketDataControllerTest` 是 `@WebMvcTest` 先例）；BFF 用 `@SpringBootTest(webEnvironment = RANDOM_PORT)` 加 `WebTestClient`（先例：`PublicSrppDailyContextSecurityTest`）。**不可只用 `standaloneSetup`**（它測不到全域 handler 與參數解析階段的例外）。案例至少含：`Content-Type: foo`、缺 Content-Type 且有 body、缺 Content-Type 且 body 為空（須回 415 而非 400）、`text/plain`、`application/json+extra`（不相容）、`application/json; charset=utf-8`（通過）、`APPLICATION/JSON`（通過）；每個不通過的案例都斷言 415、`Content-Type: application/problem+json`、`code=UNSUPPORTED_MEDIA_TYPE`、`Cache-Control: no-store`。另有一案讓 service 拋任意 `RuntimeException`，斷言 500、`code=INTERNAL_ERROR`、body 不含例外訊息。兩個端點（capture 與 evaluate）都要覆蓋。

### B. problem 載體

- [x] 484.4 新增 capture 專用 problem 目錄（backend 一份、BFF 一份，BFF 的只含它自己產生的 `INVALID_REQUEST`、`UNSUPPORTED_MEDIA_TYPE`、`OWNER_UNAVAILABLE`、`UPSTREAM_INVALID`、`INTERNAL_ERROR`），每個 code 固定 `status`、英文 `title`、繁中 `detail`、`retryable`（風格同 `SrppProblemCatalog` 與 `SrppOrchestratedProblemCatalog`）。本任務登錄的共用 code 與狀態：`INVALID_REQUEST` 400、`UNSUPPORTED_MEDIA_TYPE` 415、`NON_TRADING_DAY` 409、`POLICY_UNSUPPORTED` 409、`SWAGGER_MISMATCH` 409、`OWNER_UNAVAILABLE` 503、`CALENDAR_UNAVAILABLE` 503（retryable）、`CONTEXT_NOT_READY` 503（retryable）、`UPSTREAM_INVALID` 502、`INTERNAL_ERROR` 500；**只有 `CALENDAR_UNAVAILABLE` 與 `CONTEXT_NOT_READY` 的 `retryable` 為 true**。t481、t482 各自新增自己的 code。
- [x] 484.5 `SrppCaptureProblem`（`com.steven.assets.service.srpp`）增加 `detail`、`retryable`、`errors`（預設空）欄位，**保留既有的兩參數建構子 `(HttpStatus status, String code)`**（由目錄補齊其餘欄位，t483 的程式與測試不必改）。`InternalSrppCaptureController.problem()` 與 BFF `SrppCaptureRelay.problem()` 改輸出 RFC 9457 七欄 `type`、`title`、`status`、`detail`、`instance`、`code`、`retryable`（有 `errors` 時再加 `errors[]`），`instance` 為**該請求的公開路徑**（`/api/public/srpp/event-evidence/capture` 或 `/api/public/srpp/daily-decision/evaluate`）。所有 problem 都 `Cache-Control: no-store`。BFF 的 relay 對 business 回應的 problem 本體仍原樣轉送。
- [x] 484.6 `docs/openapi/docker-external-api.yaml`：兩個端點的 problem 回應統一引用七欄 problem schema（含 `errors[]` 為選填），兩個端點都加 `500`。**不**在此任務加 `422` 與 `202`（分別由 t481、t482 加）。

### C. BFF owner 解析

- [x] 484.7 新增 package-private `SrppOwnerResolver`（`bff/src/main/java/com/steven/assets/bff/publicsrpp/`）：輸入可為 null 的 `email`，邏輯**逐行等同**既有 `PublicSrppOrchestratedService.owner(String)`——`email == null` 時 `users.configuredAdmin()`，否則 `users.byEmail(email)`；`timeout(5s)`；錯誤與空結果一律映為 `OWNER_UNAVAILABLE`（`SrppOrchestratedProblemException` 的對應或 capture 專用例外）；結果必須 `id`、`role`、`status` 非空、`isActive()`，且 `email == null` 時必須是 `configuredAdmin()`。另提供把 owner 轉成 `X-User-Id`／`X-User-Role`／`X-User-Status`（`AuthConstants.HDR_USER_ID`／`HDR_USER_ROLE`／`HDR_USER_STATUS`）headers 並 `contextWrite(ctx -> ctx.delete(AuthConstants.CTX_IDENTITY))` 的輔助方法，與 `PublicSrppOrchestratedService.fetch` 相同。**不改動**既有 `PublicSrppOrchestratedService`（避免回歸）。因為舊方法是 private，一致性測試以兩者對同一組 `BusinessUserClient` mock 的外部可觀察行為（`read()` 的成功／`OWNER_UNAVAILABLE`）比對，涵蓋 configured-admin、byEmail、非 ACTIVE、非 configured-admin 缺省、逾時、空結果。本任務只提供 resolver，**不**把它接到 `event()` 或 `decision()`（分別由 t481、t482 接）。

### D. 已發布 Swagger 身分

- [x] 484.8 「已發布 Swagger 身分」＝隨 business-services 發布的 `9090-api-swagger.md` 位元組的 SHA-256。新增共用 bean `PublishedSwaggerIdentity`（`com.steven.assets.service.srpp`；t481、t482 重用，不得各自讀檔），在啟動時讀 classpath resource `srpp/9090-api-swagger.md`（`backend/src/main/resources/srpp/9090-api-swagger.md`）算出小寫 hex SHA-256；resource 缺失時 `sha256()` 回 `Optional.empty()`（呼叫端一律 fail closed 為 503 `CONTEXT_NOT_READY`，不放行任何 hash）。
- [x] 484.9 **必須同步的清單**：`scripts/render-9090-openapi-docs.rb` 的 `TARGETS` 陣列加第三份 `backend/src/main/resources/srpp/9090-api-swagger.md`（雲端與地端兩個分支都要，並先 `mkdir -p` 目錄），其 `--check` 一併檢查；該腳本產生的文件頭「兩個 Markdown 位置必須位元組一致」改成三個；`scripts/tests/docker-external-api-openapi-test.rb` 的 `MANIFEST`（目前兩個 capture 端點寫死 `%w[200 201 400 409 415 502 503]`）加入 `500`，並新增三份位元組一致的檢查；同檔釘死的 `info.version`（目前 `1.20.0`）與 `docs/openapi/docker-external-api.yaml` 的 `info.version` 一起升為 `1.21.0`（之後每個改變 9090 契約的任務各升一個 minor），所有產生的 Markdown 同步；`CLAUDE.md` 中「重產本專案 Swagger Markdown 並覆寫 SRPP 鏡像」的敘述與 `INSTALLATION.md` 中「兩份 Markdown」的敘述都補成三份；重產後在**地端**覆寫 `/Users/steven/Project/SRPP/docs/9090 Port API Swagger.md`。測試：`PublishedSwaggerIdentity` 的 SHA-256 等於相對路徑 `../docs/openapi/9090-api-swagger.md`（或 `docs/openapi/9090-api-swagger.md`，先試後者再試前者，方式同 `SrppFormulaCatalogTest` 讀 `spec/fixtures`）的位元組 SHA-256。**營運風險**：身分是整份 Markdown 的位元組雜湊，任何一條 9090 路由的描述變動都會改變它；改了 YAML 卻沒有重建 `business-services`，或雲端 session 改 YAML 而 SRPP 本機鏡像未同步，SRPP 的 capture 與 evaluate 會全數 409 `SWAGGER_MISMATCH`。因此改 YAML 後必須重產三份、重建 `business-services` 並地端覆寫 SRPP 鏡像。

### E. API 錯誤日誌目錄

- [x] 484.10 backend `ApiErrorLogOperationCatalog.Operation` 的欄位順序是 `(source, operationKey, apiName, apiUrl, displayOrder)`，其中 **`apiName` 是中文標籤**（例如 `"SRPP 逐日技術序列"`），**`apiUrl` 才是 `GET …`／`POST …` 路由字串**；資料表 `api_error_log_operation` 的欄位是 `source, operation_key, operation_label, display_order`；`api_error_log(source, operation_key, api_name)` 以外鍵對 `api_error_log_operation(source, operation_key, operation_label)`；BFF 送出的 `apiName` 就是 `OpenApiRouteCatalog` 的 `Operation.name()`。因此 BFF 與 backend 的**標籤必須逐字相同**，否則 `require(...)` 或外鍵都會讓該筆 5xx 被丟棄。在 `ApiErrorLogOperationCatalog.OPERATIONS` 補四個 `OPEN_API` operation：`OPEN_SRPP_DAILY_REPORT_MAIL`（`SRPP 日報寄送`，`POST /api/srpp/daily-report-mail`，200）、`OPEN_SRPP_DAILY_REPORT_MAIL_STATUS`（`SRPP 日報寄送狀態`，`GET /api/srpp/daily-report-mail/{idempotencyKey}`，210）、`OPEN_SRPP_EVENT_EVIDENCE`（`SRPP 事件證據收據`，`POST /api/public/srpp/event-evidence/capture`，220）、`OPEN_SRPP_DAILY_DECISION`（`SRPP 日報決策收據`，`POST /api/public/srpp/daily-decision/evaluate`，230）。並把 BFF `OpenApiRouteCatalog` 上面提到的四個不一致標籤改成與 backend 完全相同（DB 的 operation 列有 `BEFORE UPDATE`／`BEFORE DELETE` 不可變 trigger，所以只能改 BFF）。
- [x] 484.11 新增 insert-only changeset `backend/src/main/resources/db/changelog/changes/v1.146.0-srpp-api-error-log-operations.sql`（格式同 `v1.142.0-srpp-technical-series-error-log-catalog.sql`：`--liquibase formatted sql`、`--changeset steven:v1.146.0-srpp-api-error-log-operations`、`INSERT INTO api_error_log_operation (source, operation_key, operation_label, display_order) VALUES … ON CONFLICT (source, operation_key) DO NOTHING;`），補上 484.10 的四筆，並登錄於 `db.changelog-master.yaml`。
- [x] 484.12 測試：(a) 更新既有 `backend/src/test/java/com/steven/assets/apierrorlog/ApiErrorLogServiceTest.java`——它把目錄寫死為 36 筆（`subList(0,19)`／`subList(19,36)` 與 `expectedCatalog()`），補四筆後為 40 筆，其中 `OPEN_API` 為 23 筆，須同步改 `expectedCatalog()`、方法名與 `subList` 邊界；(b) 新增 `scripts/tests/` 下的 Ruby 平價測試，以 regex 解析 BFF `OpenApiRouteCatalog.java` 與 backend `ApiErrorLogOperationCatalog.java`，斷言 BFF 的**每一組 (operation_key, 標籤)** 都存在於 backend 且標籤逐字相同（只比 key 會放過標籤錯誤）。

### F. 收尾

- [x] 484.13 `db/schema.sql` 不受影響（只有種子資料，無 DDL），但仍須跑 `bash scripts/tests/schema-sql-drift-test.sh` 確認回 0；`spec/design.md`、`spec/steering/structure.md` 的 9090 manifest 描述維持 23 pairs。
- [x] 484.14 約束：不得新增路徑、HTTP client、LLM client、快取寫入、排程或券商呼叫；不得改動既有 `PublicSrppOrchestratedService` 與 SRPP GET 端點的行為；`SrppOwnerResolver` 與 `PublishedSwaggerIdentity` 只被提供、尚未被業務邏輯使用。

## 驗證

```bash
bash scripts/spec-check.sh
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f bff/pom.xml test
ruby scripts/render-9090-openapi-docs.rb --check
ruby scripts/tests/docker-external-api-openapi-test.rb
bash scripts/tests/configure-tailscale-api-gateway-test.sh
bash scripts/tests/schema-sql-drift-test.sh
```

以 Docker 重建 `business-services`、`bff`、`api-gateway` 後（依 `.claude/skills/run-stack`），對 loopback 9090 驗證兩個端點：送 `Content-Type: text/plain` 回 415 且是 `application/problem+json`、七欄、`instance` 為各自路徑；送合法 JSON 仍回 t483 的 503 `CONTEXT_NOT_READY`（七欄）；GET 同路徑回 405 `Allow: POST`；容器內 `printenv` 與 `unzip -l /app/app.jar | grep 9090-api-swagger.md` 確認 resource 存在。

## 完成報告

實作於 2026-10-09 完成。

- 新增：backend `SrppCaptureProblemCatalog`、`PublishedSwaggerIdentity`、classpath resource `srpp/9090-api-swagger.md`、changeset `v1.146.0-srpp-api-error-log-operations.sql`；BFF `SrppCaptureProblemCatalog`、`SrppCaptureProblemException`、`SrppOwnerResolver`（僅提供，未接線）；`scripts/tests/api-error-log-operation-parity-test.rb`。
- 修改：兩層 SRPP POST controller（移除 `consumes`、入口檢查 Content-Type、空 body 400、控制器本地 415／500 handler、七欄 problem）、`SrppCaptureProblem`（新增 `detail`／`retryable`／`errors`，保留兩參數建構子）、`ApiErrorLogOperationCatalog`（補四個 operation）、BFF `OpenApiRouteCatalog`（四個標籤對齊 backend）、`render-9090-openapi-docs.rb`（第三份 target）、OpenAPI（`info.version` 1.21.0、兩端點加 500 與七欄 problem schema）、`CLAUDE.md`／`INSTALLATION.md`／`scripts/README.md`。
- 新測試：`InternalSrppCaptureControllerWebMvcTest`（32 案，`@WebMvcTest`）、`SrppCaptureControllerMediaTypeTest`（26 案，`@SpringBootTest(RANDOM_PORT)`＋`WebTestClient`）、`SrppOwnerResolverConsistencyTest`（15 案）、`PublishedSwaggerIdentityTest`（2 案）、`SrppCaptureProblemCatalogTest`（13 案）。
- 驗證：spec-check BLOCK 0／CHECK 0；backend 2756 測試與 BFF 645 測試通過（兩者在 JDK 25 皆需 `-DextraArgLine=-Dnet.bytebuddy.experimental=true`）；`render-9090-openapi-docs.rb --check`、`docker-external-api-openapi-test.rb`（23 pairs）、新的 parity 測試、`configure-tailscale-api-gateway-test.sh`、`schema-sql-drift-test.sh` 皆通過。
- Docker 驗收（`business-services`、`bff` 重建並 recreate）：Liquibase 只套用 1 個新 changeset；四個新 operation 在資料庫（200／210／220／230）、`OPEN_API` 共 23 列；jar 內 Swagger resource 與 `docs/openapi/9090-api-swagger.md` 的 SHA-256 一致；兩個端點的 `text/plain`、`foo`、缺 Content-Type 皆回 415 problem+json 七欄且 `instance` 為請求路徑，空 body 400，合法欄位於休市日回 409 `NON_TRADING_DAY`，GET 回 405 `Allow: POST`；既有 `trading-calendar` GET 仍 200。
- 與 spec 的偏差：(1) 可達 schema 數由 144 變 145（新增 `SrppCaptureProblem` schema），renderer 與契約測試的寫死值同步；(2) WebFlux 對 `application/*`、`*/*` 拋的是一般 `ResponseStatusException`（415），BFF 多加一個本地 handler 映射；(3) `SrppCaptureProblem` 建構時 status 與目錄登記不同會丟 `IllegalArgumentException`；(4) parity 測試額外比對路由字串與種子列標籤／displayOrder；(5) `CLAUDE.md` 補一句「改 YAML 後必須重建 `business-services`，否則回 409 `SWAGGER_MISMATCH`」。
- 營運備註：驗收時發現前次驗收建出的 `business-services` 把 fubon secrets 掛到 worktree 內空目錄，本次以 `FUBON_SECRETS_DIR_HOST` 指回 main 的正式目錄，部署後有效設定與 main 展開設定逐鍵比對相同。從 feature worktree 驗收時務必留意此掛載點。
- 本任務完成後 SRPP 本機鏡像 `/Users/steven/Project/SRPP/docs/9090 Port API Swagger.md` 已由 renderer 覆寫為 1.21.0（SRPP main checkout 內仍為未提交修改，需由 SRPP 端自行 commit）。
