# [t454] SRPP 共用計算結果：BFF 公開路由、strict validator 與 9090／Tailscale／OpenAPI 同步

**對應 Requirements:** Requirement 163（新增第 14 條 9090 exact GET `/api/public/srpp/daily-context`：BFF 嚴格 query 驗證、Requirement 140 owner selector、strict response validator、RFC 9457 錯誤、Cache-Control，以及 gateway／Tailscale／frontend／OpenAPI／錯誤日誌 catalog 全面同步）
**前置任務:** t453（business `GET /internal/public-srpp/daily-context`）；t452（`api_error_log_operation` 已有 `OPEN_SRPP_DAILY_CONTEXT` 列）
**Liquibase changeset:** 無

## 背景

business 端讀取端點（t453）只供容器內呼叫。本任務把它以 exact 路由公開到 9090，使 manifest 由 13 條（12 GET＋1 POST）變為 14 條（13 GET＋1 POST）。這條路由沿用 Requirement 140 的 `email` owner selector（使用者知情接受「無驗證層、可依 email 讀任一 ACTIVE 帳號」），**不新增驗證機制，也不得「修復」它**。實際環境中 registry 為空，路由穩定回 409 `POLICY_UNSUPPORTED`。

proposal 規格包原文、合成範例與 proposal OpenAPI：`docs/handoffs/srpp-api-spec-20260923/`（`openapi.yaml` 內的 path、`components.schemas.Srpp*` 為本路由契約來源）。

## 要做什麼

- [ ] 454.1 **BFF query 解析 `SrppDailyContextQuery`（純函式，無 I/O）。** 由 `ServerHttpRequest.getQueryParams()` 取多值 map：允許 key 恰為 `tradingDate, slot, policyBundleSha256, email, view, packageId, sourceId`；未知 key、任一 key 出現超過一次、值空字串或含前後空白、`Content-Length > 0` 或有 `Transfer-Encoding`（GET body）→ 400 `INVALID_REQUEST`。格式：`tradingDate` 嚴格 `yyyy-MM-dd` 且為合法日期；`slot` 僅 `09:05`／`11:40`；`policyBundleSha256` `^[0-9a-f]{64}$`；`email` 與 Requirement 140 相同（`^[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}$` 不分大小寫、≤254）；`view` `summary|evidence`（預設 summary）；`packageId` `^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`；`sourceId` `^[a-z][a-z0-9_-]{0,63}$`；evidence 缺 packageId 或 sourceId、summary 帶 sourceId → 400。產生 business URI 時只帶驗證過的參數（**不帶 email**），以 `UriComponentsBuilder` 編碼。

- [ ] 454.2 **`PublicSrppDailyContextService`。** 流程：query 400 →（零 outbound）→ owner resolve → business 呼叫 → 回應驗證。
  - owner 判定規則同 Requirement 140（`LatestAssetsPublicService`），但本路由**刻意較嚴**：email 空值或前後空白在 454.1 即回 400（既有路由把空白 email 視為省略），且 configured-admin 與 byEmail 兩種 lookup 都套 5 秒逾時（既有 `configuredAdmin()` 無逾時）。其餘：無 email → `BusinessUserClient.configuredAdmin()`，須 `configuredAdmin()==true` 且 ACTIVE、id/role/status 非 null；有 email → `byEmail(trimmed)`，ACTIVE 且 id/role/status 非 null 即可，**不得要求 configuredAdmin**；lookup timeout 5 秒；查無、非 ACTIVE、逾時、錯誤一律同一個 503 `OWNER_UNAVAILABLE`（同 title、同 detail），不可區辨。
  - business 呼叫 `GET /internal/public-srpp/daily-context?...`，`.contextWrite(ctx -> ctx.delete(AuthConstants.CTX_IDENTITY))` 並顯式設定 `HDR_USER_ID／ROLE／STATUS` 為解析出的 owner；timeout 5 秒。逾時或 `WebClientRequestException`（連線失敗）→ 503 `CONTEXT_NOT_READY`。
  - business 非 2xx：body 必須是 `application/problem+json` 且欄位恰為 `type, title, status, detail, instance, code, retryable`，`status` 等於 HTTP status，code 與 status 符合下表；符合 → BFF 以自己的固定文案重新輸出同 code（不轉送 business 文字）；不符合、business 500，或任何未列於下表的 status → 502 `UPSTREAM_INVALID`。允許對照：400→`INVALID_REQUEST`；404→`CONTEXT_NOT_FOUND`／`SOURCE_EVIDENCE_NOT_FOUND`；409→`POLICY_UNSUPPORTED`／`CONTEXT_STALE`／`CONTEXT_IDENTITY_MISMATCH`／`NON_TRADING_DAY`；503→`OWNER_UNAVAILABLE`／`CALENDAR_UNAVAILABLE`／`CONTEXT_NOT_READY`。
  - business 2xx：先讀成 `byte[]`，交給 validator；通過後**原位元組**回傳，不重新序列化。

- [ ] 454.3 **Strict validator `SrppDailyContextResponseValidator`（BFF，純函式）。** 以 Jackson `STRICT_DUPLICATE_DETECTION` 解析、`Content-Type` 必須是 `application/json`；依 proposal schema 逐欄驗證（`additionalProperties:false` 全面適用）。任一不符拋 payload 例外 → 502 `UPSTREAM_INVALID`。必須涵蓋：
  - SUMMARY：`kind, context, contextContentSha256, freshness` 恰好四欄；context 16 欄精確、常數（`schemaVersion 1.0.0`、`timezone Asia/Taipei`、`scope LISTED_CALCULATIONS_ONLY`、`tradingAuthorized false`、`requiresOriginalDailyChecks true`）；時間必含 offset 且 `dataAsOf ≤ capturedAt ≤ generatedAt`、`dataCutoffAt ≤ generatedAt ≤ checkedAt`；Decimal 全部 canonical（拒絕 JSON number、boolean、`NaN`、`Infinity`、指數、千分位、前導零、負零、多餘尾零）；Metric 條件（UNAVAILABLE ⇒ value null 且 reasonCodes ≥1；其他 ⇒ value 為 Decimal 且 sourceIds ≥1）、unit 與欄位對應（TWD／RATIO／NATIVE_PRICE／POINT）；模組 status 條件（UNAVAILABLE ⇒ data null、原因 ≥1；COMPLETE ⇒ reasonCodes 空、不得含 UNAVAILABLE Metric、`unmappedHoldingIds`／`missingIncomeRowIds` 必空；PARTIAL ⇒ 原因 ≥1、data 非 null）；八項 checks 名稱完整不重複且 `passed` 為 true；sources／sourceIds／rows／depositGroups 排序與唯一；`sourceHoldingIds`、`sourceRowIds`、`unmappedHoldingIds`、`missingIncomeRowIds`、`changedSourceIds` 等 ID 陣列依 `String.compareTo` 字串序且唯一；**比較器與 producer 完全一致**：sources 依 `sourceId`、allocation rows 依 `assetKey`、depositGroups 依 `(currency, depositType, bankId)` 逐鍵 `String.compareTo`（bankId 以 wire 字串比較、null 排最前，不得轉數值）；反例須含 bankId `"10"` 與 `"9"` 兩組，只接受字串序（`"10"` 在前）；BFF `SrppJcs` 同樣只允許絕對值 ≤ 2^53−1 的整數；sourceIds 只引用 AVAILABLE source；AVAILABLE source 的 revision／時間／hash 非 null 且 reasonCodes 空、MISSING 反之；coverage 與模組一致；reasonCode 格式 `^[A-Z][A-Z0-9_]*$`；freshness 條件（CURRENT ⇒ 兩陣列空；否則 reasonCodes ≥1）。funding、completedTechnicals 若非 UNAVAILABLE 也須依 proposal schema 完整驗證（`SrppFundingData`、`SrppTechnicalsData`，含 `tradingAuthorized:false`）。
  - 以 BFF 自己的 `SrppJcs`（與 backend 同規格：key 依 `String.compareTo` 排序、RFC 8785 字串逸出、只允許整數 number）重算 `contextContentSha256` 必須相符。
  - 與請求一致：`context.tradingDate／slot／policy.policyBundleSha256` 等於 query；pinned 時 `context.packageId` 等於 query；最新模式 freshness 必須 CURRENT；view 與 `kind` 對應。
  - EVIDENCE：7 欄精確、常數 `bodyMediaType application/json`、`bodyEncoding UTF-8`，packageId／sourceId 等於 query，`sha256(UTF-8(body)) == bodySha256`，且 body 本身可被嚴格 JSON 解析（拒絕重複 key、NaN／Infinity）。
  - 測試 `SrppDailyContextResponseValidator` 的單元測試：把 proposal `examples/` 的 summary／evidence 複製到 `bff/src/test/resources/srpp/` 作正例（合成範例的 query 以範例值構造）；反例至少：多一欄、少一欄、錯 enum、number 金額、`"1.0"`、`"-0"`、`"1e3"`、`"01"`、boolean 金額、UNAVAILABLE 卻有值、COMPLETE 含 UNAVAILABLE Metric、checks 7 項、checks 重複、sourceIds 未排序、引用 MISSING source、coverage 過報、hash 被改一碼、evidence body 被改、latest 收到 STALE、tradingDate 與 query 不符。

- [ ] 454.4 **Controller 與 advice。** `PublicSrppDailyContextController`：`@GetMapping("/api/public/srpp/daily-context")`，只把 `ServerHttpRequest` 交給 service。`PublicSrppDailyContextExceptionAdvice`（`@RestControllerAdvice(assignableTypes = …)`、`@Order(Ordered.HIGHEST_PRECEDENCE)`）：所有錯誤都輸出 `application/problem+json`，欄位恰為 `type:"about:blank", title, status, detail, instance:"/api/public/srpp/daily-context", code, retryable`，文案取自 BFF `SrppProblemCatalog`（每 code 固定英文 title＋繁中 detail；retryable 逐 code 固定：`CALENDAR_UNAVAILABLE`、`CONTEXT_NOT_READY` 為 true，其餘（含 503 `OWNER_UNAVAILABLE`——帳號不存在或停用時重試無益）一律 false；未預期例外 500 `INTERNAL_ERROR`）；錯誤日誌：既有 `PublicApiErrorCaptureWebFilter` 的行為是「最終回應 5xx 一律記錄；4xx 只在 advice 呼叫過 `capture(exchange, ex)` 時記錄」，而 Requirement 143 要求 matched route 由例外映射出的 4xx 也要記錄（既有 12 支 scoped advice 皆對 400 呼叫 capture）。本 advice 依 Requirement 143：400、404、409、500、502、503 的 handler 都呼叫 `capture`，**唯一具名例外是 409 `POLICY_UNSUPPORTED` 不呼叫**——它是空 registry 期間正式環境的常態回應，也是 Tailscale preflight 的預期結果，記錄只會淹沒真正的錯誤。**成功與所有應用錯誤都帶 `Cache-Control: private, no-store`**，不回 304／ETag。
  - `SecurityConfig`：GET permitAll 清單加入 `"/api/public/srpp/daily-context"`（exact；其他 method、子路徑、尾斜線維持 401）。
  - `OpenApiRouteCatalog`：加入 `"GET /api/public/srpp/daily-context" → OPEN_SRPP_DAILY_CONTEXT "SRPP 共用計算結果"`，Javadoc「13-route」改 14；`PublicApiErrorCaptureWebFilter` Javadoc 同步；`SchedulePublicBffController` 的「比對既有 13 條白名單路由」文字改 14；`PublicApiErrorCaptureWebFilterTest` 註解「outside the 13-route allowlist」改 14。

- [ ] 454.5 **BFF 測試。**
  - `PublicSrppDailyContextService` 的單元測試（比照 `LatestAssetsConfiguredAdminContextTest` 用 `ExchangeFunction` stub）：每個 400 query 案例零 outbound（owner lookup 與 business 都沒被呼叫）；無 email 走 configuredAdmin、有 email 走 byEmail 且接受非 admin ACTIVE；查無／非 ACTIVE／逾時三者回應逐位元相同；caller `X-User-*`／Reactor identity 不外洩、business 收到的 header 是解析出的 owner、business URI 不含 email；business 409／404／503 轉譯、code 與 status 不符 → 502、business 500 → 502、逾時 → 503；validator 失敗 → 502；成功回應位元組與 business 相同。
  - `PublicSrppDailyContextSecurity` 的單元測試：匿名 GET 可達 controller；POST、`/extra`、尾斜線 401。
  - Cache-Control 在 200、400、404、409、502、503 皆為 `private, no-store`。
  - 錯誤日誌：409 `POLICY_UNSUPPORTED` 回應時 capture／ingest 零呼叫；400 `INVALID_REQUEST`、404、409 `CONTEXT_STALE`、502 各恰一次。

- [ ] 454.6 **Gateway `api-gateway/nginx.conf`。** 在 `location = /api/public/transactions` 同型位置新增：
  ```
  location = /api/public/srpp/daily-context {
      add_header Allow $api_allow_header always;
      if ($request_method != GET) { return 405; }
      set $bff_upstream bff:8080;
      proxy_pass http://$bff_upstream$request_uri;
  }
  ```
  不新增 wildcard／regex location。

- [ ] 454.7 **Tailscale。** `scripts/configure-tailscale-api-gateway.sh`：`SERVE_PATHS` 與 Python `expected` dict 加入 `/api/public/srpp/daily-context`；新增 preflight：以台北今天、`slot=09:05`、`policyBundleSha256` 為 64 個 `0`，期待 HTTP 409、`Content-Type: application/problem+json`、`Cache-Control: private, no-store`、body `code == "POLICY_UNSUPPORTED"`（全零 hash 由 t452 的 DB CHECK 與登錄腳本禁止登錄，結果穩定並證明鏈路直達 business；`POLICY_UNSUPPORTED` 為錯誤日誌具名例外，不寫日誌）；所有「十三」文字改「十四」。`scripts/tests/configure-tailscale-api-gateway-test.sh`：mock `serve status --json` 加入新 handler、mock curl 加入對應 case（回合成 409 problem）、`grep -c '^serve '` 期望值 13→14、PASS 文字改十四路。

- [ ] 454.8 **Frontend `frontend/nginx-app.conf`。** 新增 exact `location = /api/public/srpp/daily-context { return 404; }`；matrix regex 的 `public(?:;[^/]*)?/(?:…)` 群組加入 `srpp(?:;[^/]*)?/daily-context(?:;[^/]*)?`。`frontend/src/utils/openApiContract.test.js`：路由數 13→14、排序清單加入 `GET /api/public/srpp/daily-context`（位於 portfolio-advice/latest 與 trading-calendar 之間）、標題「十三個」改「十四個」。前端 view 不得呼叫此路由。

- [ ] 454.9 **OpenAPI `docs/openapi/docker-external-api.yaml`。**
  - `info.version` 1.13.0→1.14.0；`info.description`／servers 描述中路由數「十三條」→「十四條」、「十二條 GET／十二條唯讀」→「十三條」；新增一段說明本路由（背景 producer、GET 純讀、空 registry 時一律 409、模組降級語意、`tradingAuthorized=false`）。
  - paths 新增 `/api/public/srpp/daily-context` get：operationId `getSrppDailyContext`，參數 7 個（順序 `tradingDate, slot, policyBundleSha256, email, view, packageId, sourceId`），`email` 參數的 schema／example／description 與既有 personal owner operation 相同慣例（description 必含「格式不合法回 400」「查無帳號或帳號非 ACTIVE 回 503」），其餘參數描述取 proposal 並依下方規則補齊；responses 200／400／404／405／409／500／502／503／504，200 為 `oneOf` Summary／Evidence 並附 `Cache-Control` header（`const: private, no-store`），錯誤回 `SrppProblem`，502 另有 `text/html` NginxErrorHtml、504 `$ref` 既有 `NginxGatewayTimeout`、405 帶 `Allow: GET`。範例使用 proposal 的合成 partial／stalePinned／evidence 與問題範例（`POLICY_UNSUPPORTED`、`CONTEXT_NOT_READY`、`OWNER_UNAVAILABLE`、`INVALID_REQUEST`）；problem 範例只取結構：`title`／`detail` 一律用 `SrppProblemCatalog` 文案，不得沿用 proposal 的通用文案；`retryable` 依 catalog（恰與 proposal 四份範例相同）。
  - `components.schemas` 加入 proposal 全部 `Srpp*` schema：名稱、結構、限制不變，**但描述必須補齊**以通過既有 `assert_schema_descriptions!`（每個 schema、property、array item、allOf／anyOf／oneOf variant、只有 `$ref` 的包裝節點都要有具體繁中用途描述，每個 enum／const 字面值都要在描述內逐一解釋；proposal 原稿約 470 處不合格），並移除「提案／尚未實作／尚未部署」字樣；不得加入 proposal 的 `x-implementation-status`。
  - **YAML 1.1 陷阱：** proposal `openapi.yaml` 中未加引號的 `09:05` 會被 Ruby Psych 解析成整數 32700。併入時 slot parameter enum、`SrppContext.slot` enum 與所有 examples 的 `09:05` 一律寫成 `'09:05'`；contract test 新增斷言 slot parameter 與 `SrppContext.slot` 的 enum 都等於 `['09:05', '11:40']`。
  - 7 個 parameter 各補 `example`（renderer 要求）：tradingDate `2026-09-24`、slot `'09:05'`、policyBundleSha256 一個 64 位 hex、email `selected@example.invalid`、view `summary`、packageId 一個小寫 UUID、sourceId `assets`。parameter schema 用 BFF 實際規則（packageId 小寫 UUID pattern、sourceId `^[a-z][a-z0-9_-]{0,63}$`、email maxLength 254 並註明空白回 400），不照抄 proposal 較寬的 `SrppId`。
  - `scripts/tests/docker-external-api-openapi-test.rb`：`MANIFEST` 加入 `['GET', '/api/public/srpp/daily-context'] => %w[200 400 404 405 409 500 502 503 504]`（405 比照既有 `commodity-prices` 已列入 MANIFEST 的先例：OpenAPI 405 response 照抄該路由的 text/html 與 `Allow` header 寫法，Allow 值為 `GET`）、`OPERATION_IDS` 加入 `getSrppDailyContext`、`info.version` 期望 1.14.0、`personal_owner_operations` 加入本路由（參數精確陣列為上述 7 個）、reachable schema 計數（本檔與 `scripts/render-9090-openapi-docs.rb` 兩處的 92）改成實際新值、BFF `SecurityConfig` 檢查清單加入新路徑、訊息文字「十三路」改「十四路」。
  - 執行 `ruby scripts/render-9090-openapi-docs.rb` 重產 `docs/openapi/9090-api-swagger.md`（雲端 session 依現行規則不覆寫 SRPP 鏡像；地端會同時覆寫）。`bff` 的 `OpenApiContractServiceTest` 維持通過。

- [ ] 454.10 **文件計數同步（13→14、12 GET→13 GET）。** `spec/design.md`（Requirement 86 段「Current-state 覆寫」的十三條／十二 GET、Requirement 140 段、Overview 的「Docker 外部 API（本機／遠端）」兩行的路由數與列舉——本次 spec 變更已先行改寫，實作時確認無殘留）、`CLAUDE.md`（BFF 具名例外段、「Docker 外部 API 一律經 Nginx 9090 gateway」段：十三條→十四條、十二條唯讀 GET→十三條，並在列舉末尾加入第十四條 `GET /api/public/srpp/daily-context`（Requirement 163／Task 452–454；owner 預設 configured-admin、可帶 email；GET 純讀已發布 package、registry 空時一律 409）；Requirement 140 的五支個人資料端點敘述另註明本路由沿用同一 email selector）、`INSTALLATION.md`、`scripts/README.md`、`spec/steering/tech.md`、`spec/steering/structure.md` 中所有 9090 路由計數與路徑清單。不得改動其他路由的語意描述。

- [ ] 454.11 **鐵則。** 不新增其他路由、不改其他 13 條行為；BFF 不直查 DB／Redis、不呼叫外部行情；不 import 券商 SDK；不新增 Funnel、root proxy 或 host port；不修改 SRPP 專案檔案。

## 驗證

```bash
bash scripts/spec-check.sh
ruby scripts/tests/docker-external-api-openapi-test.rb
ruby scripts/render-9090-openapi-docs.rb --check
bash scripts/tests/configure-tailscale-api-gateway-test.sh
cd frontend && npm test
cd bff && mvn -q test -DextraArgLine=-Dnet.bytebuddy.experimental=true
cd backend && mvn -q test -DextraArgLine=-Dnet.bytebuddy.experimental=true
```

地端 `/run-stack` 重建 business-services、bff、api-gateway、frontend 後：
```bash
curl -si "http://127.0.0.1:9090/api/public/srpp/daily-context?tradingDate=$(TZ=Asia/Taipei date +%F)&slot=09:05&policyBundleSha256=$(printf '0%.0s' {1..64})"   # 409 POLICY_UNSUPPORTED, Cache-Control: private, no-store
curl -si -X POST "http://127.0.0.1:9090/api/public/srpp/daily-context"      # 405 Allow: GET
curl -si "http://127.0.0.1:9090/api/public/srpp/daily-context/"             # 404
curl -si "http://127.0.0.1:9090/api/public/srpp/daily-context?foo=1"        # 400 INVALID_REQUEST
curl -si "http://localhost/api/public/srpp/daily-context"                   # frontend 404
bash scripts/configure-tailscale-api-gateway.sh                             # 十四路 preflight 通過
```
並比對前後 `srpp_context_package`、`srpp_owner_key` 列數與 Redis key 數不變。

## 完成報告

（實作者回填：改動檔案、reachable schema 新計數、各測試結果、Docker／Tailscale 實測或未能執行的原因。未完成 Docker 與 Tailscale 實測前不得回報「已部署」。）
