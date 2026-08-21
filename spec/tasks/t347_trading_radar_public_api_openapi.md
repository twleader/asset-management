# [t347] 今日交易雷達 9090 公開 API 與 OpenAPI 3 全覆蓋硬規則

**對應 Requirements:** Requirement 86（主要管理者今日交易雷達的第九條 Docker 外部唯讀 API；所有 9090 API 必須有完整 OpenAPI 3 契約）
**前置任務:** t328（deny-by-default 9090 gateway）、t338（configured-admin 公開 API 模式）、t346（TPEX market-index runtime 已落地但 Swagger 尚未同步）
**Liquibase changeset:** 無

## 背景

目前 host/Tailscale 的 9090 gateway 有八條 exact route（七 GET、一 POST），而「今日交易雷達」只有登入後 `/api/bff/trading-radar`。交易雷達是 owner-scoped：內容由該 owner 的最新持股與觀察清單組成，不能把匿名 request header 或 query 當 owner。公開 API 必須固定讀 business 唯一解析出的 configured admin，與 `/api/assets/latest`、`/api/public/portfolio-advice/latest` 相同。

既有頁面 root GET 每次組裝後，會把結果以節流／去重方式保存為 per-owner Redis export snapshot。外部自動化可能高頻 polling；若公開 API 直接重用 root GET，會讓匿名讀取污染匯出快照時間軸。因此 public 路徑要使用同一個 `assemble(null)` 與同一個 DTO，但走一支不執行 snapshot save 的 business GET。

本專案已有 `docs/openapi/docker-external-api.yaml`，但規範尚未把 Swagger 同步列為 9090 route 的硬前置條件，已發生 runtime 與文件漂移：程式的 market-index catalog 已有 `TPEX／台股櫃買市場` 且 TWSE 已改名「台股集中市場」，OpenAPI enum、label 與 item count 仍停在舊版。這個任務同時建立可執行的 gateway/OpenAPI parity 與 DTO-field parity，之後 route 或 DTO 漂移時必須自動失敗。

新 route 會揭露 configured admin 的持股／觀察標的、價格、動作、風險與證據。任何能到達本機 loopback 或獲准 Tailscale identity 都可免登入讀取；此風險已明示接受。不得因此開 owner selector、公網 listener、Funnel、wildcard route、Swagger UI route 或額外 OAuth/API key。

## 要做什麼

- [ ] **347.1 Business 新增不保存快照的 current GET。**
  - `TradingRadarController` 在既有 class-level `/api/trading-radar` 下新增 exact `@GetMapping("/current")`，只回 `service.getCurrent()`。
  - `TradingRadarService` 新增 `@Transactional(readOnly = true) public TradingRadarDto.Response getCurrent()`，實作只能 `return assemble(null);`。
  - `assemble(null)` 保留 request-scoped `TenantFilterAspect` owner 語意；不得改用背景的 explicit-owner repository 分支，不得複製規則引擎或 DTO shaping。
  - `getCurrent()` 不得呼叫 `snapshotStore.save`、`saveRecomputed`、`TradingRadarRefreshService`、通知、export、外部 HTTP、DB/Redis write。既有 `get()` 的 snapshot save 行為完全不變。
  - 擴充 `TradingRadarServiceOwnerScopeTest`：current 走無 owner repository method，owner-scoped repository method 未被呼叫，兩種 snapshot save 都未呼叫；既有 root GET 與背景 owner 重算測試繼續通過。新增 controller delegation test，禁止 controller 直接碰 repository／WebClient。

- [ ] **347.2 BFF 新增 configured-admin public boundary。** 在 `bff/src/main/java/com/steven/assets/bff/tradingradar/` 新增：
  - `PublicTradingRadarController`：`@RestController`、`@RequestMapping("/api/public/trading-radar/today")`、exact GET，只委派 service，回 `Mono<ResponseEntity<byte[]>>`。
  - `PublicTradingRadarService`：先 `users.configuredAdmin()`；要求 admin、id 非 null、`configuredAdmin()==true`、`isActive()==true`；下游 `GET /api/trading-radar/current` 顯式帶 `AuthConstants.HDR_USER_ID/HDR_USER_ROLE/HDR_USER_STATUS`；使用 `.retrieve().onStatus(status -> !status.is2xxSuccessful(), ClientResponse::createException).toEntity(byte[].class)` 或等價的 explicit non-2xx gate；只對 downstream publisher `.contextWrite(ctx -> ctx.delete(AuthConstants.CTX_IDENTITY))`。
  - 不接受 request query/header 當 owner，不把匿名 caller 的 `X-User-*` 或 Tailscale header 往下游傳；query 即使出現也不能改變 owner。
  - `users.configuredAdmin()` 必須先形成獨立 bootstrap publisher；在 downstream `flatMap` 前，將其 HTTP／transport／codec decode／`toUser()` projection 等任何 error signal 映射成不含原始 cause message 的 `PublicTradingRadarUnavailableException`。不得假設 `CodecException`／`DecodingException`／`ClassCastException` 都是 `WebClientException`，也不得讓它們漏成預設 500。
  - `PublicTradingRadarUnavailableException` 表示 configured admin bootstrap 失敗、不存在、不合法或非 ACTIVE。
  - `PublicTradingRadarExceptionAdvice` 必須 `@RestControllerAdvice(assignableTypes = PublicTradingRadarController.class)` 與 `@Order(Ordered.HIGHEST_PRECEDENCE)`：unavailable → 固定 ProblemDetail 503；`WebClientResponseException` → 固定 502；其他 `WebClientException` → 固定 503。body 不得包含 upstream body、URL、exception message、email 或 user id。
  - 成功 response 原始 bytes、HTTP status 與 Content-Type 保留；不得 decode 成 `Map`／`Double` 後重編碼。所有 business 3xx/4xx/5xx 都必須因 explicit status gate 進 advice 消毒；不得只依賴 `.retrieve()` 預設 4xx/5xx handler，也不能像 portfolio advice 的 `exchangeToMono` 原樣 relay status/body/header。

- [ ] **347.3 BFF 測試完整覆蓋安全與錯誤契約。**
  - Service test 以 mock HTTP server 驗 exact downstream path/method、三個 configured-admin header、成功 bytes 與 Content-Type；另驗 context 內已有另一位登入者／代看者時，tenant filter 仍不能覆寫 configured admin headers。
  - Controller test 驗只委派 service與成功 bytes。
  - Advice test 必須在同一個 reactive HTTP 測試 context 同時註冊 scoped advice 和全域 `BusinessErrorAdvice`，以帶內部 sentinel 的例外證明 sentinel 不外洩；涵蓋 downstream 3xx/4xx/5xx 全部固定 502、transport 503、unavailable 503。3xx fixture 同時帶 sentinel body 與內部 `Location` URL，斷言公開 response 不保留 upstream status、body或 header。
  - Bootstrap integration test 必須讓 `/internal/users/configured-admin` 分別回 `200 application/json` malformed JSON 與可解析但 `id` 為字串的錯型別 JSON；兩者皆斷言固定 503 ProblemDetail 且 malformed body、id sentinel、exception message 都不外洩。
  - Security test：匿名 exact GET 可進 controller；同 path POST/PUT/PATCH/DELETE、descendant、trailing slash 與相鄰 private BFF route不可因 permitAll wildcard 放行。不得新增 `/api/public/**`。

- [ ] **347.4 Nginx gateway 與 frontend 二次防線。**
  - `api-gateway/nginx.conf` 在 catch-all 前新增 `location = /api/public/trading-radar/today`；重用 `$api_allow_header`，合法方法只 GET；使用變數 `$bff_upstream`、Docker resolver 與 `$request_uri`，不得寫死上游 IP或 wildcard。
  - GET 保留 query/status/body/content-type；其他 method 405 且 `Allow: GET`；descendant、尾斜線、matrix與 unknown 404。
  - `frontend/nginx.conf` 在 generic `/api/` proxy 前新增 exact 404；在既有單一 anchored regex 的 `public` alternatives 內加入 `trading-radar(?:;...)?/today(?:;...)?`，每個 segment 的 matrix parameter 都擋，禁止另開寬鬆 regex。

- [ ] **347.5 BFF SecurityConfig 只新增 exact GET。**
  - 把 `/api/public/trading-radar/today` 加入既有 `pathMatchers(HttpMethod.GET, ...)` permitAll 清單。
  - 註解與 Requirements/Tasks 引用改為目前六支 BFF anonymous GET（quotes 仍由 Nginx直送 external）。
  - 不改 private `/api/bff/trading-radar/**` 的登入需求，不放行 `/api/trading-radar/current` 的外部直達路徑。

- [ ] **347.6 Tailscale 管理腳本由八路擴為九路且 fail closed。**
  - `scripts/configure-tailscale-api-gateway.sh` 的 `SERVE_PATHS`、`validate_owned_config.expected` 加新 path/target；所有 exact/subset/ownership/TOCTOU/partial-cleanup 訊息與判準改九條。
  - Reset 前對新 route 用 `get_200()`，再解析 JSON：root object、`ruleVersion/actionPolicyVersion/generatedAt/market/usMarket/stocks/skippedNonTwStocks/publicInformation` 八個頂層 property 都存在；market/usMarket 是 object；stocks/publicInformation 是 array。不得要求 stocks 固定非空作 Serve 所有權 preflight，以免主要管理者暫無標的時無法設定；Docker feature 驗收另使用現有資料驗非空。
  - `scripts/tests/configure-tailscale-api-gateway-test.sh` 的 fake status、curl response、URL dispatch、成功 serve count與文案改九路；新增 trading-radar `200 text/plain` fail-closed case，證明在 reset 前停止。
  - 不直接執行真實 `tailscale serve reset` 作單元驗證；真實 Tailscale 若需登入／互動授權，停止並列待辦。

- [ ] **347.7 完整 OpenAPI 3 operation 與交易雷達 nested schemas。**
  - `docs/openapi/docker-external-api.yaml` 的 title 改為「Docker 外部 API」（不得再稱全唯讀）、`info.version` 升版、top-level 說明改九條（八 GET＋一 POST）並列入交易雷達風險／configured-admin語意；仍為 OpenAPI 3.1、servers 只列 loopback與Tailscale私網、global `security: []`；明說 YAML/Swagger UI 不由 9090 提供。
  - 新增 `GET /api/public/trading-radar/today`：unique operationId、tag、summary、description、無參數、200 JSON schema與去識別化合成 example、502/503 ProblemDetail、504 Nginx timeout。description 明示 configured-admin、無 owner selector、無 refresh/export/snapshot write，以及 network boundary。
  - `TradingRadarResponse` 的固定頂層八欄全列 `properties`/`required`；新增並完整列出下列 record schema：MarketSummary、StockDecision、ExtendedIndicators、FundamentalSnapshot、ValuationComponentEvidence、RadarEvidence、AssetProfile、EvidenceGroup、EvidenceComponent、MarketFeatureEvidence、NormalizedBiasEvidence、TreasuryRateContext、PublicInformationItem。
  - 每個 Java record property 都存在於 schema 且列 required；nullable 用 OpenAPI 3.1 union；BigDecimal/Double 是 number、Integer/long 是 integer、boolean 是 boolean、list 是 typed array。只有真正 map（evidenceGroups、marketFeatures、sourceManifest）可用 typed `additionalProperties`；其他 fixed object `additionalProperties: false`。自由字串不要猜 enum；有穩定常數者才列 enum並說明。
  - 合成 example 不得包含真實代號、姓名、網址、資產金額或新聞。
  - 稽核既有八個 operations；每個有 body 的成功 response 都直接提供合成 example。至少補上現況已知缺漏的 `/api/quotes/one` 200 example，不能讓舊端點在新硬規則下豁免。

- [ ] **347.8 修正 market-index OpenAPI 既有漂移。**
  - Query `market` enum、`MarketIndexResponse.market`、`MarketOption.value` 加 `TPEX`；label 各處使用 `TWSE=台股集中市場`、`TPEX=台股櫃買市場`。
  - supportedMarkets example 依 runtime catalog 順序為 TWSE、TPEX、DJI、SPX、IXIC、SOX、FTSE、DAX、KOSPI、N225；minItems/maxItems 改 10。
  - 400 detail 的合法 values 加 TPEX；不改 runtime code與既有 10 market順序。

- [ ] **347.9 新增 9090/OpenAPI 自動防漂移並接入 spec-check。**
  - 新增 `scripts/tests/docker-external-api-openapi-test.rb`，只用 Ruby stdlib `YAML`：解析 `api-gateway/nginx.conf` 每個 `location =` 與其 `if ($request_method != METHOD)`；解析 OpenAPI `paths` operation；斷言兩邊 path+method set 精確相等。
  - 同一 test 斷言 OpenAPI 3.x、global security empty、loopback/Tailscale server、operationId 唯一、所有 local `$ref` 可解析，且 fixed object schema不使用未型別化的 `additionalProperties: true`。以測試內明確 manifest 釘住九個 path+method 各自預期的 response status 集合；每個 parameter 必須有 name/in/required/description/schema與適用 example；每個宣告有 body 的 response 必須有 media type/schema；每個有 body 的成功 response 必須有直接合成 example。不能只驗 200 schema。
  - 在 backend 新增 JUnit 的交易雷達 OpenAPI schema contract，使用 test classpath 已有 YAML parser讀契約，以 record reflection 對 14 個 DTO/schema mapping比較 `properties.keySet` 與 `required` 精確等於 record components；另比較 Java type family、list element／map value、nested `$ref` 與日期時間 format，並以顯式 nullable-field manifest（或等價可執行契約）比較 OpenAPI 3.1 union nullability。不能只抽查頂層、只手寫 field count或只比欄名。
  - `scripts/spec-check.sh` 在機械檢查尾端執行上述 Ruby test；失敗計入 BLOCK並保留完整訊息。`scripts/README.md` 加測試用途與直接執行命令。

- [ ] **347.10 規範與操作文件 current-state 同步。**
  - `CLAUDE.md`、`spec/steering/tech.md`、`spec/steering/structure.md`、`INSTALLATION.md`、`scripts/README.md`、`docs/openapi/docker-external-api.yaml`、Tailscale script/test中所有 current-state「八條／八路／七 GET＋一 POST」改為「九條／九路／八 GET＋一 POST」並列新 path。
  - `CLAUDE.md` 與 steering 明訂：所有 9090 API 必須在同 task/commit具備完整 OpenAPI 3 operation；gateway與OpenAPI path+method必須機械相等；Swagger docs 本身不掛9090。
  - 更新 `.agents/skills/run-stack/SKILL.md` 早期「five routes」內容為九條，列完整 route/method並保留 loopback/Tailscale限制；依 skill-creator 的 narrow-update原則不改其 trigger/模型/流程，執行 skill validator。
  - `spec/tasks.md` index 加入 t346/t347 並保留 t349；`spec/tasks/README.md`、`CLAUDE.md`、`spec/steering/structure.md` 的 current range 都寫為 `344–347、349`，Task 348 由在途 worktree 保留，不得寫成連續 `344–349`。Requirements 實際為 87 個，Requirement 87 由在途 worktree 保留，最新編號為 88。
  - 明確標成 Task 328／Requirement 79「當時五／八條」的歷史段落可保留；任何沒有歷史限定、聲稱現在是八條的文字必須修正。

- [ ] **347.11 不變與禁區。**
  - 不改 `TradingRadarRuleEngine`、V14分數／門檻／動作／證據資料來源、通知狀態或前端畫面。
  - 不新增 DB schema、Liquibase、排程、外部抓價、Swagger UI/API docs路由、公網/Funnel/API key/OAuth proxy。
  - 不暴露 `/refresh`、`/export`、notification settings或任意 owner selector。
  - 不把 business/BFF host port映射出來；9090仍只綁127.0.0.1。

## 驗證

```bash
# 1. SDD mechanical check（包含新增的 gateway/OpenAPI parity）
bash scripts/spec-check.sh
```

```bash
# 2. OpenAPI/gateway standalone contract
ruby scripts/tests/docker-external-api-openapi-test.rb
```

```bash
# 3. Backend + BFF tests（Java 21）
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home \
  /usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home \
  /usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f bff/pom.xml test \
  -DextraArgLine=-Dnet.bytebuddy.experimental=true
```

```bash
# 4. Tailscale fake regression、frontend build、skill validation
bash scripts/tests/configure-tailscale-api-gateway-test.sh
/Users/steven/.nvm/versions/node/v22.21.0/bin/npm --prefix frontend run build
python3 /Users/steven/.codex/skills/.system/skill-creator/scripts/quick_validate.py \
  .agents/skills/run-stack
```

```bash
# 5. Docker（從本 feature worktree；先提供 worktree 所需 .env）
cp /Users/steven/Project/asset-management/.env .env
docker compose -p asset-management build --no-cache business-services bff
docker compose -p asset-management build api-gateway frontend
docker compose -p asset-management up -d --no-deps --force-recreate \
  business-services bff api-gateway frontend
docker compose -p asset-management restart bff
```

```bash
# 6. 健康與第九條正向契約
docker exec asset-bff wget -qO- http://127.0.0.1:8080/actuator/health
curl -fsS -D /tmp/radar-public.headers \
  http://127.0.0.1:9090/api/public/trading-radar/today \
  -o /tmp/radar-public.json
jq -e '
  .ruleVersion == "TW_RULES_V14" and
  (.generatedAt | type == "string" and test("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?(?:Z|[+-]\\d{2}:\\d{2})$")) and
  (.market | type == "object") and (.usMarket | type == "object") and
  (.stocks | type == "array" and length > 0) and
  (all(.stocks[]; has("stockCode") and has("market") and has("action") and has("evidence"))) and
  (.publicInformation | type == "array")
' /tmp/radar-public.json
grep -Eiq '^content-type:[[:space:]]*application/json([;[:space:]]|$)' /tmp/radar-public.headers
```

```bash
# 7. Deny matrix 與 listener 邊界
test "$(curl -sS -o /dev/null -w '%{http_code}' -X POST \
  http://127.0.0.1:9090/api/public/trading-radar/today)" = 405
test "$(curl -sS -o /dev/null -w '%{http_code}' \
  http://127.0.0.1:9090/api/public/trading-radar/today/extra)" = 404
test "$(curl -sS -o /dev/null -w '%{http_code}' \
  'http://127.0.0.1:9090/api/public/trading-radar/today/')" = 404
test "$(curl -sS -o /dev/null -w '%{http_code}' \
  'http://localhost/api/public;x=1/trading-radar;y=2/today;z=3')" = 404
test "$(docker inspect asset-api-gateway --format '{{json .HostConfig.PortBindings}}')" = \
  '{"9090/tcp":[{"HostIp":"127.0.0.1","HostPort":"9090"}]}'
for container in asset-bff asset-business-services asset-external-materials-service; do
  test "$(docker inspect "$container" --format '{{json .HostConfig.PortBindings}}')" = '{}'
done
! lsof -nP -iTCP:8080 -sTCP:LISTEN
! lsof -nP -iTCP:8082 -sTCP:LISTEN
```

```bash
# 8. 八個截圖指數仍可經同一9090 API取回；TPEX Swagger亦已對齊
for code in TWSE TPEX DJI SPX IXIC SOX FTSE DAX; do
  curl -fsS "http://127.0.0.1:9090/api/public/market-index?market=${code}&range=1m" |
    jq -e --arg code "$code" '.market == $code and (.labels|length) > 0 and ((.labels|length) == (.closes|length))'
done
```

真實 Tailscale Serve 只能在既有設定所有權可證明且不需額外使用者決策時執行；若登入／授權需要互動，完成報告列為未驗證，不把本機 9090證據冒充遠端 HTTPS。

## 完成報告

（實作者完成後回填：修改檔案、測試筆數與結果、Docker image/container provenance、九路 allowlist/OpenAPI parity、交易雷達 runtime JSON、deny matrix、Tailscale是否因互動授權未驗，以及任何偏差。）
